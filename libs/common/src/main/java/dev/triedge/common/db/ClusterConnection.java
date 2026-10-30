package dev.triedge.common.db;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record ClusterConnection(String url, String username, String password, @DefaultValue("10") int maxPoolSize) {

    public ClusterConnection {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Cluster URL must be a PostgreSQL JDBC URL: " + url);
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("Pool size must be positive: " + maxPoolSize);
        }
    }

    @Override
    public String toString() {
        return "ClusterConnection[url=" + url + ", username=" + username + ", password=***, maxPoolSize="
                + maxPoolSize + "]";
    }
}
