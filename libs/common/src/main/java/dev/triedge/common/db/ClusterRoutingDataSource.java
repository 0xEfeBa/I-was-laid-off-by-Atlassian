package dev.triedge.common.db;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public final class ClusterRoutingDataSource extends AbstractRoutingDataSource {

    public ClusterRoutingDataSource(ClusterDataSources clusters, String defaultCluster) {
        Map<Object, Object> targets = new LinkedHashMap<>();
        clusters.clusters().forEach(cluster -> targets.put(cluster, clusters.dataSource(cluster)));
        setTargetDataSources(targets);
        setDefaultTargetDataSource(clusters.dataSource(defaultCluster));
        setLenientFallback(false);
        afterPropertiesSet();
    }

    DataSource target() {
        return determineTargetDataSource();
    }

    @Override
    protected @Nullable Object determineCurrentLookupKey() {
        return ClusterContext.current().orElse(null);
    }
}
