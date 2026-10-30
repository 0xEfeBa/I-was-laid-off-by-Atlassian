package dev.triedge.regionloss;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class RegionLossCheck {

    record Result(List<Long> acknowledged, List<Long> survived) {

        long lost() {
            Set<Long> present = new HashSet<>(survived);
            return acknowledged.stream().filter(id -> !present.contains(id)).count();
        }

        @Override
        public String toString() {
            return "acknowledged %d, survived %d, lost %d".formatted(acknowledged.size(), survived.size(), lost());
        }
    }

    private RegionLossCheck() {
    }

    static Result run(Regions regions, Duration writeFor, int writers) throws Exception {
        List<Long> acknowledged = new CopyOnWriteArrayList<>();
        AtomicLong ids = new AtomicLong();
        List<CompletableFuture<Void>> running = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            running.add(CompletableFuture.runAsync(() -> write(regions, ids, acknowledged)));
        }
        Thread.sleep(writeFor);

        regions.losePrimaryRegion();
        CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);

        try (Connection promoted = regions.failOver()) {
            return new Result(List.copyOf(acknowledged), ids(promoted));
        }
    }

    static void insert(Connection connection, long id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO writes VALUES (" + id + ")");
        }
    }

    static List<Long> ids(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM writes ORDER BY id")) {
            List<Long> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
            return ids;
        }
    }

    private static void write(Regions regions, AtomicLong ids, List<Long> acknowledged) {
        try (Connection connection = regions.primary()) {
            while (true) {
                long id = ids.incrementAndGet();
                insert(connection, id);
                acknowledged.add(id);
            }
        } catch (SQLException e) {
            return;
        }
    }
}
