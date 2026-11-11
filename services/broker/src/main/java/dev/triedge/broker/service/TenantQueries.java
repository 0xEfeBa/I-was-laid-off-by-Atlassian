package dev.triedge.broker.service;

import dev.triedge.broker.data.Tenant;
import dev.triedge.broker.data.TenantOverview;
import dev.triedge.broker.data.TenantRepository;
import dev.triedge.broker.data.TenantVersion;
import dev.triedge.common.etcd.EtcdClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TenantQueries {

    public static final String APPLIED_PREFIX = "/triedge/tenants/";

    public record Summary(String name, String homeRegion, @Nullable Integer activeVersion, int latestVersion,
            @Nullable String operation, @Nullable String operationState, @Nullable String operationDescription) {
    }

    public record Detail(Summary tenant, Map<String, Integer> applied, List<TenantVersion> versions) {
    }

    public record VersionSpec(int version, String reason, @Nullable String state, @Nullable String detail,
            Instant createdAt, JsonNode spec) {
    }

    public record Diff(int from, int to, List<SpecDiff.Change> changes) {
    }

    private final TenantRepository tenants;
    private final EtcdClient etcd;
    private final JsonMapper json;

    public TenantQueries(TenantRepository tenants, EtcdClient etcd, JsonMapper json) {
        this.tenants = tenants;
        this.etcd = etcd;
        this.json = json;
    }

    public List<Summary> list(Actor actor) {
        return tenants.overviews(actor.admin() ? null : actor.id()).stream().map(TenantQueries::summary).toList();
    }

    public Detail detail(Actor actor, String name) {
        Tenant tenant = visible(actor, name);
        Summary summary = tenants.overview(tenant.id()).map(TenantQueries::summary)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Tenant not found"));
        return new Detail(summary, applied(name), tenants.versions(tenant.id()));
    }

    public VersionSpec version(Actor actor, String name, int version) {
        Tenant tenant = visible(actor, name);
        TenantVersion row = tenants.version(tenant.id(), version)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Version not found"));
        return new VersionSpec(row.version(), row.reason(), row.state(), row.detail(), row.createdAt(),
                json.readTree(tenants.spec(tenant.id(), version).orElseThrow()));
    }

    public Diff diff(Actor actor, String name, int from, int to) {
        Tenant tenant = visible(actor, name);
        JsonNode before = json.readTree(tenants.spec(tenant.id(), from)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Version " + from + " not found")));
        JsonNode after = json.readTree(tenants.spec(tenant.id(), to)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Version " + to + " not found")));
        return new Diff(from, to, SpecDiff.between(before, after));
    }

    public Map<String, Integer> applied(String name) {
        String prefix = APPLIED_PREFIX + name + "/applied/";
        Map<String, Integer> result = new TreeMap<>();
        etcd.getPrefix(prefix).forEach((key, value) -> result.put(key.substring(prefix.length()), Integer.valueOf(value)));
        return result;
    }

    private Tenant visible(Actor actor, String name) {
        return tenants.findLive(name).filter(actor::canSee)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Tenant not found"));
    }

    private static Summary summary(TenantOverview overview) {
        return new Summary(overview.name(), overview.homeRegion(), overview.activeVersion(), overview.latestVersion(),
                overview.operation(), overview.operationState(), overview.operationDescription());
    }
}
