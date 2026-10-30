package dev.triedge.sidecar.directory;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.outbox.Checkpoint;
import dev.triedge.common.outbox.OutboxEvent;
import dev.triedge.common.outbox.OutboxTailer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;

public class IdentityDirectory implements SmartLifecycle {

    public static final int PHASE = 0;

    private static final Logger log = LoggerFactory.getLogger(IdentityDirectory.class);
    private static final int BATCH = 500;

    private final ClusterDataSources clusters;
    private final InitialLoader loader;
    private final IdentityEventApplier applier;
    private final Duration retryDelay;
    private final Map<String, Checkpoint> positions = new ConcurrentHashMap<>();
    private final Map<String, OutboxTailer> tailers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public IdentityDirectory(ClusterDataSources clusters, InitialLoader loader, IdentityEventApplier applier,
            Duration retryDelay) {
        this.clusters = clusters;
        this.loader = loader;
        this.applier = applier;
        this.retryDelay = retryDelay;
    }

    @Override
    public void start() {
        for (String cluster : clusters.clusters()) {
            positions.put(cluster, loadWithRetry(cluster));
            tailers.put(cluster, new OutboxTailer(clusters.jdbc(cluster), "identity", BATCH));
        }
        running.set(true);
        log.info("Identity directory loaded from {}", clusters.clusters());
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Scheduled(fixedDelayString = "${triedge.sidecar.poll-interval:PT0.2S}")
    public void follow() {
        if (!running.get()) {
            return;
        }
        for (String cluster : clusters.clusters()) {
            try {
                catchUp(cluster);
            } catch (RuntimeException e) {
                log.warn("Could not read identity events from {}: {}", cluster, e.getMessage());
            }
        }
    }

    int catchUp(String cluster) {
        int applied = 0;
        List<OutboxEvent> batch;
        do {
            batch = tailers.get(cluster).poll(positions.get(cluster));
            for (OutboxEvent event : batch) {
                applier.apply(event);
                positions.put(cluster, Checkpoint.of(event));
                applied++;
            }
        } while (batch.size() == BATCH);
        return applied;
    }

    private Checkpoint loadWithRetry(String cluster) {
        while (true) {
            try {
                return loader.load(clusters.dataSource(cluster));
            } catch (RuntimeException e) {
                log.warn("Identity data in {} is not reachable yet: {}", cluster, e.getMessage());
                try {
                    Thread.sleep(retryDelay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while loading identity data", interrupted);
                }
            }
        }
    }
}
