package dev.triedge.common.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterFollower implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClusterFollower.class);

    private final List<String> clusters;
    private final Duration interval;
    private final Clock clock;
    private final Consumer<String> round;
    private final ScheduledExecutorService executor;
    private final Map<String, Instant> lastRound = new ConcurrentHashMap<>();

    public ClusterFollower(String name, Collection<String> clusters, Duration interval, Clock clock,
            Consumer<String> round) {
        if (clusters.isEmpty()) {
            throw new IllegalArgumentException("At least one cluster is required");
        }
        this.clusters = List.copyOf(clusters);
        this.interval = interval;
        this.clock = clock;
        this.round = round;
        this.executor = Executors.newScheduledThreadPool(this.clusters.size(),
                Thread.ofPlatform().name(name + "-", 0).daemon().factory());
    }

    public void start() {
        Instant now = clock.instant();
        for (String cluster : clusters) {
            lastRound.put(cluster, now);
            executor.scheduleWithFixedDelay(() -> runRound(cluster), interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public Map<String, Instant> lastRounds() {
        return Map.copyOf(lastRound);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runRound(String cluster) {
        try {
            round.accept(cluster);
        } catch (RuntimeException e) {
            log.warn("Could not follow {}: {}", cluster, e.getMessage());
        } finally {
            lastRound.put(cluster, clock.instant());
        }
    }
}
