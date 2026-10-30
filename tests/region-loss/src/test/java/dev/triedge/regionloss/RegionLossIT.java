package dev.triedge.regionloss;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RegionLossIT {

    @Test
    void losingARegionLosesNoAcknowledgedWrite() throws Exception {
        try (RegionCluster cluster = new RegionCluster(true)) {
            RegionLossCheck.Result result = RegionLossCheck.run(cluster, Duration.ofSeconds(3), 1);

            System.out.println("synchronous: " + result);
            assertThat(result.acknowledged()).isNotEmpty();
            assertThat(result.lost()).isZero();
        }
    }

    @Test
    void aWriteIsNotAcknowledgedUntilAnotherRegionHasIt() throws Exception {
        try (RegionCluster cluster = new RegionCluster(true); Connection primary = cluster.primary()) {
            cluster.pause(1);
            RegionLossCheck.insert(primary, 1);

            cluster.pause(0);
            CompletableFuture<Void> blocked = CompletableFuture.runAsync(() -> {
                try (Connection connection = cluster.primary()) {
                    RegionLossCheck.insert(connection, 2);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(Duration.ofSeconds(3));
            assertThat(blocked).isNotDone();

            cluster.resume(0);
            blocked.get(30, TimeUnit.SECONDS);
            cluster.resume(1);
            assertThat(RegionLossCheck.ids(primary)).containsExactly(1L, 2L);
        }
    }

    @Test
    void withoutSynchronousReplicationAcknowledgedWritesAreLost() throws Exception {
        try (RegionCluster cluster = new RegionCluster(false)) {
            List<Long> acknowledged = new CopyOnWriteArrayList<>();
            try (Connection primary = cluster.primary()) {
                cluster.stopReplication();
                for (long id = 1; id <= 100; id++) {
                    RegionLossCheck.insert(primary, id);
                    acknowledged.add(id);
                }
            }

            cluster.losePrimaryRegion();
            cluster.resume(0);
            cluster.resume(1);

            try (Connection promoted = cluster.failOver()) {
                RegionLossCheck.Result result = new RegionLossCheck.Result(acknowledged, RegionLossCheck.ids(promoted));
                System.out.println("asynchronous: " + result);
                assertThat(result.lost()).isPositive();
            }
        }
    }
}
