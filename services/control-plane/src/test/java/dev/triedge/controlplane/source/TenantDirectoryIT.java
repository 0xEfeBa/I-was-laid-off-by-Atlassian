package dev.triedge.controlplane.source;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.outbox.OutboxWriter;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.tenant.TenantSpecParser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class TenantDirectoryIT {

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
    private final TransactionTemplate transactions =
            new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.dataSource()));
    private final OutboxWriter outbox = new OutboxWriter(jdbc, "platform");
    private final AtomicInteger changes = new AtomicInteger();
    private ClusterDataSources clusters;
    private TenantDirectory directory;
    private String suffix;

    @BeforeEach
    void start() {
        suffix = UUID.randomUUID().toString().substring(0, 6);
        clusters = new ClusterDataSources(Map.of("eu", TestDatabase.connection(2)));
        List<ActiveTenant> system = SystemTenants.load(System.getProperty("triedge.system-tenants"));
        directory = new TenantDirectory(clusters, system, JsonMapper.builder().build(), Duration.ofMillis(100));
        directory.onChange(changes::incrementAndGet);
    }

    @AfterEach
    void stop() {
        directory.stop();
        clusters.close();
    }

    @Test
    void loadsSystemAndStoredTenantsThenFollowsActivations() {
        UUID existing = createTenant("a" + suffix, 1);

        directory.start();

        assertThat(directory.get("platform")).hasValueSatisfying(t -> assertThat(t.system()).isTrue());
        assertThat(directory.get("a" + suffix)).hasValueSatisfying(t -> assertThat(t.version()).isEqualTo(1));
        assertThat(directory.hostOwner("a" + suffix + ".test")).contains("a" + suffix);
        assertThat(directory.hostOwner("localhost")).contains("platform");
        assertThat(changes.get()).isEqualTo(1);

        addVersion(existing, "a" + suffix, 2);
        activate(existing, "a" + suffix, 2);
        UUID later = createTenant("b" + suffix, 1);
        activate(later, "b" + suffix, 1);
        directory.follow();

        assertThat(directory.get("a" + suffix)).hasValueSatisfying(t -> assertThat(t.version()).isEqualTo(2));
        assertThat(directory.get("b" + suffix)).isPresent();
        assertThat(changes.get()).isEqualTo(2);

        jdbc.sql("UPDATE platform.tenants SET deleted_at = now() WHERE id = :id").param("id", later).update();
        transactions.executeWithoutResult(s -> outbox.append("tenant", later.toString(), "TENANT_DELETED",
                "{\"tenantId\":\"%s\",\"name\":\"b%s\"}".formatted(later, suffix)));
        transactions.executeWithoutResult(s -> outbox.append("tenant", "x", "TENANT_DELETED",
                "{\"tenantId\":\"%s\",\"name\":\"platform\"}".formatted(UUID.randomUUID())));
        directory.follow();

        assertThat(directory.get("b" + suffix)).isEmpty();
        assertThat(directory.get("platform")).isPresent();
    }

    @Test
    void storedTenantsCannotTakeOverSystemNamesAndBrokenSpecsAreSkipped() {
        createTenant("platform", 1);
        UUID broken = createTenant("c" + suffix, 1);
        jdbc.sql("""
                INSERT INTO platform.tenant_versions (tenant_id, version, spec, spec_hash, created_by)
                VALUES (:id, 2, '{"apiVersion":"tri-edge/v1","name":"broken"}', 'x', :id)
                """).param("id", broken).update();
        jdbc.sql("UPDATE platform.tenants SET active_version = 2 WHERE id = :id").param("id", broken).update();

        directory.start();

        assertThat(directory.get("platform")).hasValueSatisfying(t -> assertThat(t.system()).isTrue());
        assertThat(directory.get("c" + suffix)).isEmpty();
        assertThat(directory.isRunning()).isTrue();
        assertThat(directory.getPhase()).isEqualTo(TenantDirectory.PHASE);
        jdbc.sql("UPDATE platform.tenants SET deleted_at = now() WHERE name = 'platform'").update();
    }

    @Test
    void followingDoesNothingBeforeStart() {
        directory.follow();

        assertThat(directory.all()).isEmpty();
    }

    private UUID createTenant(String name, int version) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.tenants (id, name, owner_id, home_region, active_version)
                VALUES (:id, :name, :owner, 'eu', NULL)
                """).param("id", id).param("name", name).param("owner", UUID.randomUUID()).update();
        addVersion(id, name, version);
        jdbc.sql("UPDATE platform.tenants SET active_version = :version WHERE id = :id")
                .param("id", id).param("version", version).update();
        return id;
    }

    private void addVersion(UUID id, String name, int version) {
        TenantSpecParser.Result.Valid spec = (TenantSpecParser.Result.Valid) TenantSpecParser.parse("""
                apiVersion: tri-edge/v1
                name: %s
                hosts: [%s.test]
                backends: [{ name: main, address: "%s.svc:80" }]
                routes: [{ path: /, backend: main }]
                """.formatted(name, name, name));
        jdbc.sql("""
                INSERT INTO platform.tenant_versions (tenant_id, version, spec, spec_hash, created_by)
                VALUES (:id, :version, CAST(:spec AS jsonb), :hash, :id)
                """).param("id", id).param("version", version).param("spec", spec.canonicalJson())
                .param("hash", spec.hash()).update();
    }

    private void activate(UUID id, String name, int version) {
        transactions.executeWithoutResult(s -> {
            jdbc.sql("UPDATE platform.tenants SET active_version = :version WHERE id = :id")
                    .param("id", id).param("version", version).update();
            outbox.append("tenant", id.toString(), "TENANT_VERSION_ACTIVATED",
                    "{\"tenantId\":\"%s\",\"name\":\"%s\",\"version\":%d}".formatted(id, name, version));
        });
    }
}
