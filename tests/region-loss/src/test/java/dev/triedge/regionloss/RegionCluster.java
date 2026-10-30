package dev.triedge.regionloss;

import com.github.dockerjava.api.DockerClient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

final class RegionCluster implements Regions, AutoCloseable {

    static final String IMAGE = "postgres:17.11";
    static final String PASSWORD = "region-loss";

    private static final String PRIMARY_INIT = """
            #!/bin/bash
            set -e
            echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"
            psql -v ON_ERROR_STOP=1 -U postgres -c "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$POSTGRES_PASSWORD'"
            """;

    private static final String STANDBY_START = """
            set -e
            D=/tmp/pgdata
            until pg_basebackup -h eu -U replicator -D "$D" -R -X stream; do rm -rf "$D"; sleep 1; done
            chmod 700 "$D"
            exec postgres -D "$D" -c cluster_name="$REGION" -c hot_standby=on
            """;

    private final Network network = Network.newNetwork();
    private final DockerClient docker = DockerClientFactory.instance().client();
    private final GenericContainer<?> primary;
    private final List<GenericContainer<?>> standbys;

    @SuppressWarnings("resource")
    RegionCluster(boolean synchronous) {
        primary = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("eu")
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withCopyToContainer(Transferable.of(PRIMARY_INIT, 0755), "/docker-entrypoint-initdb.d/replication.sh")
                .withCommand("postgres", "-c", "wal_level=replica", "-c", "cluster_name=eu")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
        primary.start();
        standbys = List.of(standby("us"), standby("ap"));
        standbys.forEach(GenericContainer::start);
        if (synchronous) {
            execute(primary, "ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (us, ap)'");
            execute(primary, "SELECT pg_reload_conf()");
        }
        execute(primary, "CREATE TABLE writes (id bigint PRIMARY KEY)");
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> standby(String region) {
        return new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases(region)
                .withEnv("PGPASSWORD", PASSWORD)
                .withEnv("REGION", region)
                .withCreateContainerCmdModifier(cmd -> cmd.withUser("postgres").withEntrypoint("bash", "-c", STANDBY_START))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*started streaming WAL.*", 1));
    }

    @Override
    public Connection primary() throws SQLException {
        return connect(primary);
    }

    @Override
    public void losePrimaryRegion() {
        docker.killContainerCmd(primary.getContainerId()).exec();
    }

    void pause(int standby) {
        docker.pauseContainerCmd(standbys.get(standby).getContainerId()).exec();
    }

    void resume(int standby) {
        docker.unpauseContainerCmd(standbys.get(standby).getContainerId()).exec();
    }

    void stopReplication() {
        pause(0);
        pause(1);
        execute(primary, "SELECT pg_terminate_backend(pid) FROM pg_stat_replication");
    }

    @Override
    public Connection failOver() throws SQLException {
        GenericContainer<?> chosen = standbys.stream()
                .max(Comparator.comparingLong(RegionCluster::receivedLsn))
                .orElseThrow();
        try (Connection connection = connect(chosen); Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_promote(true, 60)");
        }
        return connect(chosen);
    }

    private static long receivedLsn(GenericContainer<?> standby) {
        try (Connection connection = connect(standby); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT pg_wal_lsn_diff(coalesce(pg_last_wal_receive_lsn(), '0/0'), '0/0')::bigint")) {
            row.next();
            return row.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(GenericContainer<?> container, String sql) {
        try (Connection connection = connect(container); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection connect(GenericContainer<?> container) throws SQLException {
        String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/postgres";
        return DriverManager.getConnection(url, "postgres", PASSWORD);
    }

    @Override
    public void close() {
        standbys.forEach(GenericContainer::stop);
        primary.stop();
        network.close();
    }
}
