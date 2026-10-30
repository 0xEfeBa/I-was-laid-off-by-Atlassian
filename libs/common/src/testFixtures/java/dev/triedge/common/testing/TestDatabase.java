package dev.triedge.common.testing;

import com.zaxxer.hikari.HikariDataSource;
import dev.triedge.common.db.ClusterConnection;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    private static HikariDataSource dataSource;

    private TestDatabase() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            dataSource.setMaximumPoolSize(20);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("filesystem:" + System.getProperty("triedge.migrations"))
                    .load()
                    .migrate();
        }
        return dataSource;
    }

    public static synchronized ClusterConnection connection(String database, int maxPoolSize) {
        if (!database.matches("[a-z_]{1,40}")) {
            throw new IllegalArgumentException("Invalid database name: " + database);
        }
        JdbcClient admin = JdbcClient.create(dataSource());
        boolean exists = admin.sql("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :name)")
                .param("name", database)
                .query(Boolean.class)
                .single();
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        if (!exists) {
            admin.sql("CREATE DATABASE " + database).update();
            Flyway.configure()
                    .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("filesystem:" + System.getProperty("triedge.migrations"))
                    .load()
                    .migrate();
        }
        return new ClusterConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword(), maxPoolSize);
    }

    public static ClusterConnection connection(int maxPoolSize) {
        dataSource();
        return new ClusterConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), maxPoolSize);
    }
}
