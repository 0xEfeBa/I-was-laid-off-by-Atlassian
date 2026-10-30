package dev.triedge.broker.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.testing.TestEtcd;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantReservationsIT {

    private final EtcdClient etcd = new EtcdClient(TestEtcd.client());
    private final TenantReservations reservations = new TenantReservations(etcd, Clock.systemUTC(),
            new ClusterProperties("eu", Map.of("eu", new ClusterConnection("jdbc:postgresql://unused/triedge", "u", "p", 1))));
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void namesAndHostsAreClaimedTogetherOrNotAtAll() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(reservations.reserve(first, "a" + suffix, List.of(host("one"), host("two")))).isEmpty();
        assertThat(reservations.reserve(first, null, List.of(host("one"), host("three")))).isEmpty();

        assertThat(reservations.reserve(second, "b" + suffix, List.of(host("two"))))
                .containsExactly("/hosts: " + host("two") + " is already used by another tenant");
        assertThat(reservations.reserve(second, "a" + suffix, List.of()))
                .containsExactly("/name: 'a" + suffix + "' is already taken");
        assertThat(reservations.names()).doesNotContainKey("b" + suffix);
        assertThat(reservations.hosts().get(host("three")).tenantId()).isEqualTo(first);
    }

    @Test
    void releasingOnlyTouchesOwnReservations() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        reservations.reserve(owner, "c" + suffix, List.of(host("keep"), host("drop")));

        reservations.releaseName(other, "c" + suffix);
        assertThat(reservations.releaseHosts(other, List.of())).isZero();
        assertThat(reservations.releaseHosts(owner, List.of(host("keep")))).isEqualTo(1);
        reservations.releaseName(owner, "c" + suffix);

        assertThat(reservations.hosts()).containsKey(host("keep")).doesNotContainKey(host("drop"));
        assertThat(reservations.names()).doesNotContainKey("c" + suffix);
        assertThat(reservations.reserve(other, "c" + suffix, List.of(host("drop")))).isEmpty();
    }

    private String host(String label) {
        return label + "." + suffix + ".test";
    }
}
