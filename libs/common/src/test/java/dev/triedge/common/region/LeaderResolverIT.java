package dev.triedge.common.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.testing.TestEtcd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaderResolverIT {

    private final LeaderResolver resolver = new LeaderResolver(new EtcdClient(TestEtcd.client()), "/service");

    @BeforeEach
    void clean() {
        TestEtcd.deletePrefix("/service/");
    }

    @Test
    void resolvesRegionFromPatroniLeaderKey() {
        TestEtcd.put("/service/eu/leader", "eu-1");
        TestEtcd.put("/service/us/leader", "us");

        assertThat(resolver.leaderRegion("eu")).contains("eu");
        assertThat(resolver.leaderRegion("us")).contains("us");
    }

    @Test
    void missingLeaderMeansNoLeader() {
        assertThat(resolver.leaderRegion("ap")).isEmpty();
    }

    @Test
    void aRegionLeadsWhereverPatroniPutTheLeader() {
        TestEtcd.put("/service/eu/leader", "us-eu");

        assertThat(resolver.leads("eu", "us")).isTrue();
        assertThat(resolver.leads("eu", "eu")).isFalse();
        assertThat(resolver.leads("ap", "ap")).isTrue();
        assertThat(resolver.leads("ap", "eu")).isFalse();
    }

    @Test
    void rejectsSuspiciousClusterNames() {
        assertThatThrownBy(() -> resolver.leaderRegion("eu/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
