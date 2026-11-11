package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.testing.TestEtcd;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UsernameReservationIT {

    private final UsernameReservation reservations = new UsernameReservation(new EtcdClient(TestEtcd.client()));

    @BeforeEach
    void clean() {
        TestEtcd.deletePrefix(UsernameReservation.PREFIX);
        TestEtcd.deletePrefix(UsernameReservation.EMAILS);
    }

    @Test
    void onlyOneRegionCanReserveAName() {
        UUID eu = UUID.randomUUID();
        UUID ap = UUID.randomUUID();
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        assertThat(reservations.reserve("efe", "efe@x.test", new UsernameReservation.Reservation("eu", eu, now))).isTrue();
        assertThat(reservations.reserve("efe", "other@x.test", new UsernameReservation.Reservation("ap", ap, now)))
                .isFalse();
        assertThat(reservations.find("efe")).contains(new UsernameReservation.Reservation("eu", eu, now));
        assertThat(reservations.findEmail("other@x.test")).isEmpty();
    }

    @Test
    void anEmailBelongsToOneAccountInEveryRegion() {
        UUID eu = UUID.randomUUID();
        UUID us = UUID.randomUUID();
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        assertThat(reservations.reserve("ali", "ali@x.test", new UsernameReservation.Reservation("eu", eu, now))).isTrue();
        assertThat(reservations.reserve("ali2", "ALI@x.test", new UsernameReservation.Reservation("us", us, now)))
                .isFalse();

        assertThat(reservations.find("ali2")).isEmpty();
        assertThat(reservations.findEmail("Ali@X.test")).contains(new UsernameReservation.Reservation("eu", eu, now));
        assertThat(reservations.emails()).containsOnlyKeys(UsernameReservation.emailKey("ali@x.test"));
        assertThat(reservations.emails().keySet().iterator().next()).doesNotContain("ali");
    }

    @Test
    void onlyTheOwnerCanReleaseAReservation() {
        UUID owner = UUID.randomUUID();
        reservations.reserve("efe", "efe@x.test", new UsernameReservation.Reservation("eu", owner, Instant.now()));

        assertThat(reservations.release("efe", UUID.randomUUID())).isFalse();
        assertThat(reservations.releaseEmail("efe@x.test", UUID.randomUUID())).isFalse();
        assertThat(reservations.find("efe")).isPresent();
        assertThat(reservations.release("efe", owner)).isTrue();
        assertThat(reservations.releaseEmail("efe@x.test", owner)).isTrue();
        assertThat(reservations.find("efe")).isEmpty();
        assertThat(reservations.findEmail("efe@x.test")).isEmpty();
        assertThat(reservations.release("efe", owner)).isFalse();
    }

    @Test
    void listsAllReservations() {
        reservations.reserve("a", "a@x.test", new UsernameReservation.Reservation("eu", UUID.randomUUID(), Instant.now()));
        reservations.reserve("b", "b@x.test", new UsernameReservation.Reservation("us", UUID.randomUUID(), Instant.now()));

        assertThat(reservations.all()).containsOnlyKeys("a", "b");
    }
}
