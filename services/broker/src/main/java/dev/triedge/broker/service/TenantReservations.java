package dev.triedge.broker.service;

import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.etcd.EtcdClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class TenantReservations {

    public static final String NAMES = "/triedge/tenant-names/";
    public static final String HOSTS = "/triedge/tenant-hosts/";

    public record Holder(UUID tenantId, String region, Instant createdAt) {

        String encode() {
            return tenantId + "|" + region + "|" + createdAt.getEpochSecond();
        }

        static Holder decode(String value) {
            String[] parts = value.split("\\|");
            return new Holder(UUID.fromString(parts[0]), parts[1], Instant.ofEpochSecond(Long.parseLong(parts[2])));
        }
    }

    private final EtcdClient etcd;
    private final Clock clock;
    private final String region;

    public TenantReservations(EtcdClient etcd, Clock clock, ClusterProperties clusters) {
        this.etcd = etcd;
        this.clock = clock;
        this.region = clusters.region();
    }

    public List<String> reserve(UUID tenantId, @Nullable String name, Collection<String> hosts) {
        String value = new Holder(tenantId, ClusterContext.current().orElse(region), clock.instant()).encode();
        Map<String, String> create = new LinkedHashMap<>();
        Map<String, String> unchanged = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        if (name != null) {
            claim(NAMES + name, tenantId, value, create, unchanged)
                    .ifPresent(taken -> conflicts.add("/name: '" + name + "' is already taken"));
        }
        for (String host : hosts) {
            claim(HOSTS + host, tenantId, value, create, unchanged)
                    .ifPresent(taken -> conflicts.add("/hosts: " + host + " is already used by another tenant"));
        }
        if (!conflicts.isEmpty()) {
            return conflicts;
        }
        if (!etcd.putAllIfUnchanged(create, unchanged)) {
            return List.of("Another change claimed the same name or host at the same time, try again");
        }
        return List.of();
    }

    public void releaseName(UUID tenantId, String name) {
        release(NAMES + name, tenantId);
    }

    public int releaseHosts(UUID tenantId, Collection<String> keep) {
        int released = 0;
        for (var entry : hosts().entrySet()) {
            if (entry.getValue().tenantId().equals(tenantId) && !keep.contains(entry.getKey())
                    && release(HOSTS + entry.getKey(), tenantId)) {
                released++;
            }
        }
        return released;
    }

    public Map<String, Holder> names() {
        return list(NAMES);
    }

    public Map<String, Holder> hosts() {
        return list(HOSTS);
    }

    private Optional<Holder> claim(String key, UUID tenantId, String value, Map<String, String> create,
            Map<String, String> unchanged) {
        Optional<String> current = etcd.get(key);
        if (current.isEmpty()) {
            create.put(key, value);
            return Optional.empty();
        }
        Holder holder = Holder.decode(current.get());
        if (holder.tenantId().equals(tenantId)) {
            unchanged.put(key, current.get());
            return Optional.empty();
        }
        return Optional.of(holder);
    }

    private boolean release(String key, UUID tenantId) {
        Optional<String> current = etcd.get(key);
        if (current.isEmpty() || !Holder.decode(current.get()).tenantId().equals(tenantId)) {
            return false;
        }
        return etcd.deleteIfValue(key, current.get());
    }

    private Map<String, Holder> list(String prefix) {
        Map<String, Holder> result = new LinkedHashMap<>();
        etcd.getPrefix(prefix).forEach((key, value) -> result.put(key.substring(prefix.length()), Holder.decode(value)));
        return result;
    }
}
