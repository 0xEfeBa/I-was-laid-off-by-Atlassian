package dev.triedge.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ClusterRoutingDataSourceTest {

    private final ClusterDataSources clusters = new ClusterDataSources(Map.of(
            "eu", new ClusterConnection("jdbc:postgresql://eu.invalid/triedge", "u", "p", 1),
            "us", new ClusterConnection("jdbc:postgresql://us.invalid/triedge", "u", "p", 1)));
    private final ClusterRoutingDataSource routing = new ClusterRoutingDataSource(clusters, "eu");

    @Test
    void routesToTheClusterInContextAndFallsBackToTheDefault() {
        assertThat(routing.target()).isSameAs(clusters.dataSource("eu"));
        DataSource routed = ClusterContext.call("us", routing::target);
        assertThat(routed).isSameAs(clusters.dataSource("us"));
        assertThat(ClusterContext.current()).isEmpty();
        clusters.close();
    }

    @Test
    void anUnknownClusterIsAnError() {
        assertThatThrownBy(() -> ClusterContext.call("ap", routing::target))
                .isInstanceOf(IllegalStateException.class);
        clusters.close();
    }

    @Test
    void contextsNestAndRestore() {
        ClusterContext.run("eu", () -> {
            ClusterContext.run("us", () -> assertThat(ClusterContext.current()).contains("us"));
            assertThat(ClusterContext.current()).contains("eu");
        });
        assertThat(ClusterContext.current()).isEmpty();
        clusters.close();
    }
}
