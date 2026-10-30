package dev.triedge.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import dev.triedge.common.testing.TestDatabase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ClusterDataSourcesIT {

    @Test
    void connectsToEveryConfiguredCluster() {
        Map<String, ClusterConnection> clusters = new LinkedHashMap<>();
        clusters.put("eu", TestDatabase.connection(2));
        clusters.put("us", TestDatabase.connection(2));

        try (ClusterDataSources dataSources = new ClusterDataSources(clusters)) {
            assertThat(dataSources.clusters()).containsExactly("eu", "us");
            for (String cluster : dataSources.clusters()) {
                assertThat(dataSources.jdbc(cluster).sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
            }
        }
    }

    @Test
    void unreachableClusterDoesNotBlockStartup() {
        ClusterConnection down = new ClusterConnection("jdbc:postgresql://127.0.0.1:1/none", "u", "p", 1);

        try (ClusterDataSources dataSources = new ClusterDataSources(Map.of("ap", down))) {
            ((HikariDataSource) dataSources.dataSource("ap")).setConnectionTimeout(250);
            assertThatThrownBy(() -> dataSources.jdbc("ap").sql("SELECT 1").query(Integer.class).single())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void closeShutsDownEveryPool() {
        ClusterDataSources dataSources = new ClusterDataSources(Map.of("eu", TestDatabase.connection(1)));
        HikariDataSource pool = (HikariDataSource) dataSources.dataSource("eu");

        dataSources.close();

        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void rejectsUnknownClusterAndEmptyConfiguration() {
        try (ClusterDataSources dataSources = new ClusterDataSources(Map.of("eu", TestDatabase.connection(1)))) {
            assertThatThrownBy(() -> dataSources.jdbc("mars")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> dataSources.dataSource("mars")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ClusterDataSources(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
