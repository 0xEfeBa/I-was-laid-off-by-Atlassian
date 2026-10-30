package dev.triedge.broker.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {

    public record Holding(boolean live, boolean busy, String activeHosts) {
    }

    private static final String TENANT_COLUMNS = "id, name, owner_id, home_region, active_version, deleted_at";
    private static final String INSTANCE_COLUMNS = """
            instance_id, tenant_id, service_id, plan_id, parameters_hash,
            operation, operation_state, operation_description, updated_at""";

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> findLive(String name) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + " FROM platform.tenants WHERE name = :name AND deleted_at IS NULL")
                .param("name", name)
                .query(TenantRepository::tenant)
                .optional();
    }

    public Optional<Tenant> findById(UUID id) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + " FROM platform.tenants WHERE id = :id")
                .param("id", id)
                .query(TenantRepository::tenant)
                .optional();
    }

    public Optional<Tenant> lockLive(UUID id) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS
                        + " FROM platform.tenants WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(TenantRepository::tenant)
                .optional();
    }

    public List<Tenant> liveOwnedBy(UUID ownerId) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS
                        + " FROM platform.tenants WHERE owner_id = :owner AND deleted_at IS NULL ORDER BY name")
                .param("owner", ownerId)
                .query(TenantRepository::tenant)
                .list();
    }

    public List<Tenant> allLive() {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + " FROM platform.tenants WHERE deleted_at IS NULL ORDER BY name")
                .query(TenantRepository::tenant)
                .list();
    }

    public int countLiveOwnedBy(UUID ownerId) {
        return jdbc.sql("SELECT count(*) FROM platform.tenants WHERE owner_id = :owner AND deleted_at IS NULL")
                .param("owner", ownerId)
                .query(Integer.class)
                .single();
    }

    public void insertTenant(UUID id, String name, UUID ownerId, String homeRegion) {
        jdbc.sql("""
                INSERT INTO platform.tenants (id, name, owner_id, home_region)
                VALUES (:id, :name, :owner, :region)
                """)
                .param("id", id)
                .param("name", name)
                .param("owner", ownerId)
                .param("region", homeRegion)
                .update();
    }

    public void markDeleted(UUID id) {
        jdbc.sql("UPDATE platform.tenants SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void insertVersion(UUID tenantId, int version, String spec, String hash, UUID createdBy, String reason) {
        jdbc.sql("""
                INSERT INTO platform.tenant_versions (tenant_id, version, spec, spec_hash, created_by, reason)
                VALUES (:tenant, :version, CAST(:spec AS jsonb), :hash, :createdBy, :reason)
                """)
                .param("tenant", tenantId)
                .param("version", version)
                .param("spec", spec)
                .param("hash", hash)
                .param("createdBy", createdBy)
                .param("reason", reason)
                .update();
    }

    public int latestVersion(UUID tenantId) {
        return jdbc.sql("SELECT coalesce(max(version), 0) FROM platform.tenant_versions WHERE tenant_id = :tenant")
                .param("tenant", tenantId)
                .query(Integer.class)
                .single();
    }

    public Optional<String> spec(UUID tenantId, int version) {
        return jdbc.sql("SELECT spec::text FROM platform.tenant_versions WHERE tenant_id = :tenant AND version = :version")
                .param("tenant", tenantId)
                .param("version", version)
                .query(String.class)
                .optional();
    }

    public Optional<String> specHash(UUID tenantId, int version) {
        return jdbc.sql("SELECT spec_hash FROM platform.tenant_versions WHERE tenant_id = :tenant AND version = :version")
                .param("tenant", tenantId)
                .param("version", version)
                .query(String.class)
                .optional();
    }

    public List<TenantVersion> versions(UUID tenantId) {
        return jdbc.sql("""
                SELECT v.version, v.spec_hash, v.reason, v.created_at, s.state, s.detail
                FROM platform.tenant_versions v
                LEFT JOIN platform.tenant_version_status s USING (tenant_id, version)
                WHERE v.tenant_id = :tenant
                ORDER BY v.version DESC
                """)
                .param("tenant", tenantId)
                .query((row, n) -> new TenantVersion(
                        row.getInt("version"),
                        row.getString("spec_hash"),
                        row.getString("reason"),
                        row.getString("state"),
                        row.getString("detail"),
                        instant(row, "created_at")))
                .list();
    }

    public void setStatus(UUID tenantId, int version, String state, @Nullable String detail) {
        jdbc.sql("""
                INSERT INTO platform.tenant_version_status (tenant_id, version, state, detail)
                VALUES (:tenant, :version, :state, :detail)
                ON CONFLICT (tenant_id, version)
                DO UPDATE SET state = EXCLUDED.state, detail = EXCLUDED.detail, updated_at = now()
                """)
                .param("tenant", tenantId)
                .param("version", version)
                .param("state", state)
                .param("detail", detail)
                .update();
    }

    public void activate(UUID tenantId, int version) {
        jdbc.sql("""
                UPDATE platform.tenant_version_status SET state = 'SUPERSEDED', updated_at = now()
                WHERE tenant_id = :tenant AND state = 'ACTIVE' AND version <> :version
                """)
                .param("tenant", tenantId)
                .param("version", version)
                .update();
        setStatus(tenantId, version, "ACTIVE", null);
        jdbc.sql("UPDATE platform.tenants SET active_version = :version, updated_at = now() WHERE id = :tenant")
                .param("tenant", tenantId)
                .param("version", version)
                .update();
    }

    public Optional<ServiceInstance> findInstance(String instanceId) {
        return jdbc.sql("SELECT " + INSTANCE_COLUMNS + " FROM platform.service_instances WHERE instance_id = :id")
                .param("id", instanceId)
                .query(TenantRepository::instance)
                .optional();
    }

    public Optional<ServiceInstance> instanceOf(UUID tenantId) {
        return jdbc.sql("SELECT " + INSTANCE_COLUMNS + " FROM platform.service_instances WHERE tenant_id = :tenant")
                .param("tenant", tenantId)
                .query(TenantRepository::instance)
                .optional();
    }

    public void insertInstance(String instanceId, UUID tenantId, String serviceId, String planId, String hash,
            String description) {
        jdbc.sql("""
                INSERT INTO platform.service_instances
                    (instance_id, tenant_id, service_id, plan_id, parameters_hash, operation, operation_description)
                VALUES (:id, :tenant, :service, :plan, :hash, 'provision', :description)
                """)
                .param("id", instanceId)
                .param("tenant", tenantId)
                .param("service", serviceId)
                .param("plan", planId)
                .param("hash", hash)
                .param("description", description)
                .update();
    }

    public void startOperation(UUID tenantId, String operation, String hash, String description) {
        jdbc.sql("""
                UPDATE platform.service_instances
                SET operation = :operation, operation_state = 'in progress', operation_description = :description,
                    parameters_hash = :hash, updated_at = now()
                WHERE tenant_id = :tenant
                """)
                .param("tenant", tenantId)
                .param("operation", operation)
                .param("hash", hash)
                .param("description", description)
                .update();
    }

    public void finishOperation(UUID tenantId, String state, String description) {
        jdbc.sql("""
                UPDATE platform.service_instances
                SET operation_state = :state, operation_description = :description, updated_at = now()
                WHERE tenant_id = :tenant
                """)
                .param("tenant", tenantId)
                .param("state", state)
                .param("description", description)
                .update();
    }

    public void deleteInstance(UUID tenantId) {
        jdbc.sql("DELETE FROM platform.service_instances WHERE tenant_id = :tenant")
                .param("tenant", tenantId)
                .update();
    }

    public Optional<Holding> holding(UUID tenantId) {
        return jdbc.sql("""
                SELECT t.deleted_at IS NULL AS live,
                       coalesce(si.operation_state = 'in progress', false) AS busy,
                       coalesce(v.spec -> 'hosts', '[]'::jsonb)::text AS hosts
                FROM platform.tenants t
                LEFT JOIN platform.service_instances si ON si.tenant_id = t.id
                LEFT JOIN platform.tenant_versions v ON v.tenant_id = t.id AND v.version = t.active_version
                WHERE t.id = :tenant
                """)
                .param("tenant", tenantId)
                .query((row, n) -> new Holding(row.getBoolean("live"), row.getBoolean("busy"), row.getString("hosts")))
                .optional();
    }

    private static Tenant tenant(ResultSet row, int rowNumber) throws SQLException {
        Timestamp deletedAt = row.getTimestamp("deleted_at");
        return new Tenant(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getObject("owner_id", UUID.class),
                row.getString("home_region"),
                row.getObject("active_version", Integer.class),
                deletedAt == null ? null : deletedAt.toInstant());
    }

    private static ServiceInstance instance(ResultSet row, int rowNumber) throws SQLException {
        return new ServiceInstance(
                row.getString("instance_id"),
                row.getObject("tenant_id", UUID.class),
                row.getString("service_id"),
                row.getString("plan_id"),
                row.getString("parameters_hash"),
                row.getString("operation"),
                row.getString("operation_state"),
                row.getString("operation_description"),
                instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }
}
