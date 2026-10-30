package dev.triedge.regionloss;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RemoteRegions implements Regions {

    static final String PREFIX = "triedge.remote.";

    private static final String OPTIONS = "/postgres?targetServerType=primary&connectTimeout=10&loginTimeout=15&socketTimeout=30";

    private final String everyMember;
    private final String survivors;
    private final String password;
    private final List<String> killCommand;
    private long lostAt;

    private RemoteRegions(String eu, String us, String ap, String password, List<String> killCommand) {
        this.everyMember = String.join(",", eu, us, ap);
        this.survivors = String.join(",", us, ap);
        this.password = password;
        this.killCommand = killCommand;
    }

    static RemoteRegions fromSystemProperties() {
        return new RemoteRegions(property("eu"), property("us"), property("ap"), property("password"),
                List.of(property("kill").split(" ")));
    }

    @Override
    public Connection primary() throws SQLException {
        return connect(everyMember);
    }

    @Override
    public void losePrimaryRegion() {
        try {
            Process process = new ProcessBuilder(killCommand).inheritIO().start();
            if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IllegalStateException("Could not power off the primary region: " + killCommand);
            }
            lostAt = System.nanoTime();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Connection failOver() throws SQLException {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Connection promoted = connect(survivors);
                System.out.printf("a new primary took over %d seconds after eu went down%n",
                        Duration.ofNanos(System.nanoTime() - lostAt).toSeconds());
                return promoted;
            } catch (SQLException e) {
                last = e;
                sleep();
            }
        }
        throw new SQLException("No primary was elected within three minutes", last);
    }

    private Connection connect(String members) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + members + OPTIONS, "postgres", password);
    }

    private static void sleep() {
        try {
            Thread.sleep(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String property(String name) {
        String value = System.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property " + PREFIX + name);
        }
        return value;
    }
}
