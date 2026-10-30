package dev.triedge.common.db;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("triedge")
public record ClusterProperties(String region, Map<String, ClusterConnection> clusters) {

    public ClusterProperties {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("triedge.region must be set");
        }
        if (clusters == null || !clusters.containsKey(region)) {
            throw new IllegalArgumentException("triedge.clusters must contain the local region " + region);
        }
    }
}
