package dev.triedge.common.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ClusterDataSources implements AutoCloseable {

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_SECONDS = 5;
    static final int SOCKET_TIMEOUT_SECONDS = 30;

    private final Map<String, HikariDataSource> dataSources;
    private final Map<String, JdbcClient> clients;

    public ClusterDataSources(Map<String, ClusterConnection> clusters) {
        if (clusters.isEmpty()) {
            throw new IllegalArgumentException("At least one cluster is required");
        }
        Map<String, HikariDataSource> created = new LinkedHashMap<>();
        Map<String, JdbcClient> createdClients = new LinkedHashMap<>();
        clusters.forEach((name, connection) -> {
            HikariDataSource dataSource = create(name, connection);
            created.put(name, dataSource);
            createdClients.put(name, JdbcClient.create(dataSource));
        });
        this.dataSources = Collections.unmodifiableMap(created);
        this.clients = Collections.unmodifiableMap(createdClients);
    }

    public Set<String> clusters() {
        return dataSources.keySet();
    }

    public DataSource dataSource(String cluster) {
        HikariDataSource dataSource = dataSources.get(cluster);
        if (dataSource == null) {
            throw new IllegalArgumentException("Unknown cluster: " + cluster);
        }
        return dataSource;
    }

    public JdbcClient jdbc(String cluster) {
        JdbcClient client = clients.get(cluster);
        if (client == null) {
            throw new IllegalArgumentException("Unknown cluster: " + cluster);
        }
        return client;
    }

    @Override
    public void close() {
        dataSources.values().forEach(HikariDataSource::close);
    }

    private static HikariDataSource create(String name, ClusterConnection connection) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("cluster-" + name);
        config.setJdbcUrl(connection.url());
        config.setUsername(connection.username());
        config.setPassword(connection.password());
        config.setMaximumPoolSize(connection.maxPoolSize());
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        config.addDataSourceProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        config.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        config.addDataSourceProperty("tcpKeepAlive", true);
        return new HikariDataSource(config);
    }
}
