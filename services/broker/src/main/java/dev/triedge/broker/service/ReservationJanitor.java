package dev.triedge.broker.service;

import dev.triedge.broker.data.TenantRepository;
import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.region.LeaderResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReservationJanitor {

    static final Duration GRACE = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ReservationJanitor.class);

    private final TenantReservations reservations;
    private final TenantRepository tenants;
    private final EtcdClient etcd;
    private final JsonMapper json;
    private final Clock clock;
    private final LeaderResolver leaders;
    private final String region;

    public ReservationJanitor(TenantReservations reservations, TenantRepository tenants, EtcdClient etcd,
            JsonMapper json, Clock clock, LeaderResolver leaders, ClusterProperties clusters) {
        this.reservations = reservations;
        this.tenants = tenants;
        this.etcd = etcd;
        this.json = json;
        this.clock = clock;
        this.leaders = leaders;
        this.region = clusters.region();
    }

    @Scheduled(fixedDelayString = "${triedge.broker.janitor-interval:PT5M}")
    public int releaseAbandoned() {
        Instant cutoff = clock.instant().minus(GRACE);
        int released = sweep(TenantReservations.NAMES, reservations.names(), cutoff, false)
                + sweep(TenantReservations.HOSTS, reservations.hosts(), cutoff, true);
        if (released > 0) {
            log.info("Released {} abandoned tenant reservations", released);
        }
        return released;
    }

    private int sweep(String prefix, Map<String, TenantReservations.Holder> held, Instant cutoff, boolean hosts) {
        int released = 0;
        for (var entry : held.entrySet()) {
            TenantReservations.Holder holder = entry.getValue();
            if (!holder.createdAt().isBefore(cutoff) || !leaders.leads(holder.region(), region)) {
                continue;
            }
            Optional<TenantRepository.Holding> holding =
                    ClusterContext.call(holder.region(), () -> tenants.holding(holder.tenantId()));
            boolean abandoned = holding.isEmpty() || !holding.get().live()
                    || (hosts && !holding.get().busy() && !activeHosts(holding.get()).contains(entry.getKey()));
            String key = prefix + entry.getKey();
            if (abandoned && etcd.get(key)
                    .filter(value -> TenantReservations.Holder.decode(value).tenantId().equals(holder.tenantId()))
                    .map(value -> etcd.deleteIfValue(key, value)).orElse(false)) {
                released++;
            }
        }
        return released;
    }

    private List<String> activeHosts(TenantRepository.Holding holding) {
        JsonNode hosts = json.readTree(holding.activeHosts());
        return hosts.valueStream().map(JsonNode::asString).toList();
    }
}
