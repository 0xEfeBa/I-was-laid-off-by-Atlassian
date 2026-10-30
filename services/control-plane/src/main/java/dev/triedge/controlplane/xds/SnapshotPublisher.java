package dev.triedge.controlplane.xds;

import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.render.EnvoyResources;
import dev.triedge.controlplane.source.ActiveTenant;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnapshotPublisher implements AutoCloseable {

    public static final String GROUP = "edge";

    public record Published(long version, Map<String, Long> requiredAcks, Map<String, Integer> tenantVersions) {
    }

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

    private final SimpleCache<String> cache;
    private final ConfigRenderer renderer;
    private final Supplier<Collection<ActiveTenant>> tenants;
    private final Duration coalesce;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "snapshot-publisher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Map<String, Integer> typeHashes = new HashMap<>();
    private final Map<String, Long> lastChanged = new HashMap<>();
    private long version;
    private volatile @Nullable Published latest;

    public SnapshotPublisher(SimpleCache<String> cache, ConfigRenderer renderer,
            Supplier<Collection<ActiveTenant>> tenants, Duration coalesce) {
        this.cache = cache;
        this.renderer = renderer;
        this.tenants = tenants;
        this.coalesce = coalesce;
    }

    public void requestPublish() {
        if (scheduled.compareAndSet(false, true)) {
            executor.schedule(this::publishNow, coalesce.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized Published publishNow() {
        scheduled.set(false);
        Collection<ActiveTenant> current = tenants.get();
        EnvoyResources resources = renderer.render(current, false);
        version++;
        track(Resources.V3.CLUSTER_TYPE_URL, resources.clusters());
        track(Resources.V3.LISTENER_TYPE_URL, resources.listeners());
        track(Resources.V3.ROUTE_TYPE_URL, resources.routes());
        cache.setSnapshot(GROUP, Snapshot.create(resources.clusters(), List.of(), resources.listeners(),
                resources.routes(), List.of(), "v" + version));
        Map<String, Integer> tenantVersions = new HashMap<>();
        current.stream().filter(t -> !t.system()).forEach(t -> tenantVersions.put(t.name(), t.version()));
        Published published = new Published(version, Map.copyOf(lastChanged), Map.copyOf(tenantVersions));
        latest = published;
        log.debug("Published configuration v{} with {} tenants", version, current.size());
        return published;
    }

    public @Nullable Published latest() {
        return latest;
    }

    public synchronized long version() {
        return version;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void track(String typeUrl, List<?> resources) {
        int hash = resources.hashCode();
        Integer previous = typeHashes.put(typeUrl, hash);
        if (previous == null || previous != hash) {
            lastChanged.put(typeUrl, version);
        }
    }
}
