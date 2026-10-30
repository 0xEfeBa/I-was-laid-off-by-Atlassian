package dev.triedge.controlplane.source;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.outbox.Checkpoint;
import dev.triedge.common.outbox.OutboxEvent;
import dev.triedge.common.outbox.OutboxTailer;
import dev.triedge.tenant.TenantSpecParser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class TenantDirectory implements SmartLifecycle {

    public static final int PHASE = 0;

    private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);
    private static final int BATCH = 500;
    private static final String ACTIVE_TENANTS = """
            SELECT t.id, t.name, t.active_version, v.spec::text AS spec
            FROM platform.tenants t
            JOIN platform.tenant_versions v ON v.tenant_id = t.id AND v.version = t.active_version
            WHERE t.deleted_at IS NULL
            """;

    private final ClusterDataSources clusters;
    private final List<ActiveTenant> systemTenants;
    private final JsonMapper json;
    private final Duration retryDelay;
    private final Map<String, ActiveTenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, Checkpoint> positions = new ConcurrentHashMap<>();
    private final Map<String, OutboxTailer> tailers = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public TenantDirectory(ClusterDataSources clusters, List<ActiveTenant> systemTenants, JsonMapper json,
            Duration retryDelay) {
        this.clusters = clusters;
        this.systemTenants = systemTenants;
        this.json = json;
        this.retryDelay = retryDelay;
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public Collection<ActiveTenant> all() {
        return List.copyOf(tenants.values());
    }

    public Optional<ActiveTenant> get(String name) {
        return Optional.ofNullable(tenants.get(name));
    }

    public Optional<String> hostOwner(String host) {
        return tenants.values().stream()
                .filter(t -> t.spec().hosts().contains(host))
                .map(ActiveTenant::name)
                .findFirst();
    }

    @Override
    public void start() {
        systemTenants.forEach(tenant -> tenants.put(tenant.name(), tenant));
        for (String cluster : clusters.clusters()) {
            positions.put(cluster, loadWithRetry(cluster));
            tailers.put(cluster, new OutboxTailer(clusters.jdbc(cluster), "platform", BATCH));
        }
        running.set(true);
        log.info("Loaded {} tenants ({} system)", tenants.size(), systemTenants.size());
        notifyListeners();
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

    @Scheduled(fixedDelayString = "${triedge.control-plane.poll-interval:PT0.2S}")
    public void follow() {
        if (!running.get()) {
            return;
        }
        boolean changed = false;
        for (String cluster : clusters.clusters()) {
            try {
                changed |= catchUp(cluster);
            } catch (RuntimeException e) {
                log.warn("Could not read tenant events from {}: {}", cluster, e.getMessage());
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    boolean catchUp(String cluster) {
        boolean changed = false;
        List<OutboxEvent> batch;
        do {
            batch = tailers.get(cluster).poll(positions.get(cluster));
            for (OutboxEvent event : batch) {
                changed |= apply(cluster, event);
                positions.put(cluster, Checkpoint.of(event));
            }
        } while (batch.size() == BATCH);
        return changed;
    }

    private boolean apply(String cluster, OutboxEvent event) {
        JsonNode payload = json.readTree(event.payload());
        return switch (event.eventType()) {
            case "TENANT_VERSION_ACTIVATED" -> {
                UUID id = UUID.fromString(payload.get("tenantId").asString());
                Optional<ActiveTenant> tenant = clusters.jdbc(cluster)
                        .sql(ACTIVE_TENANTS + " AND t.id = :id")
                        .param("id", id)
                        .query(this::toTenant)
                        .list()
                        .stream()
                        .filter(Objects::nonNull)
                        .findFirst();
                tenant.ifPresent(this::put);
                yield tenant.isPresent();
            }
            case "TENANT_DELETED" -> {
                ActiveTenant removed = tenants.get(payload.get("name").asString());
                if (removed != null && !removed.system()) {
                    tenants.remove(removed.name());
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private void put(ActiveTenant tenant) {
        ActiveTenant current = tenants.get(tenant.name());
        if (current != null && current.system()) {
            log.error("Ignoring tenant {} because the name belongs to a system tenant", tenant.name());
            return;
        }
        tenants.put(tenant.name(), tenant);
    }

    private Checkpoint loadWithRetry(String cluster) {
        while (true) {
            try {
                return load(cluster);
            } catch (RuntimeException e) {
                log.warn("Tenant data in {} is not reachable yet: {}", cluster, e.getMessage());
                try {
                    Thread.sleep(retryDelay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while loading tenants", interrupted);
                }
            }
        }
    }

    private Checkpoint load(String cluster) {
        TransactionTemplate snapshot = new TransactionTemplate(new DataSourceTransactionManager(clusters.dataSource(cluster)));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        JdbcClient jdbc = clusters.jdbc(cluster);
        return snapshot.execute(status -> {
            Checkpoint checkpoint = jdbc.sql("""
                    SELECT txid::text::bigint AS txid, id FROM platform.outbox
                    WHERE txid < pg_snapshot_xmin(pg_current_snapshot())
                    ORDER BY txid DESC, id DESC LIMIT 1
                    """)
                    .query((row, n) -> new Checkpoint(row.getLong("txid"), row.getLong("id")))
                    .optional()
                    .orElse(Checkpoint.START);
            jdbc.sql(ACTIVE_TENANTS).query(this::toTenant).list().forEach(tenant -> {
                if (tenant != null) {
                    put(tenant);
                }
            });
            return checkpoint;
        });
    }

    private @Nullable ActiveTenant toTenant(ResultSet row, int rowNumber) throws SQLException {
        String name = row.getString("name");
        TenantSpecParser.Result result = TenantSpecParser.parse(row.getString("spec"));
        if (result instanceof TenantSpecParser.Result.Valid valid) {
            return new ActiveTenant(row.getObject("id", UUID.class), name, row.getInt("active_version"), valid.spec(), false);
        }
        log.error("Stored spec of tenant {} no longer parses; keeping it out of the configuration", name);
        return null;
    }

    private void notifyListeners() {
        listeners.forEach(Runnable::run);
    }
}
