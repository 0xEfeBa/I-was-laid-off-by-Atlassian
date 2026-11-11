package dev.triedge.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClusterFollowerTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final Map<String, AtomicInteger> rounds = new ConcurrentHashMap<>();
    private ClusterFollower follower;

    @AfterEach
    void stop() {
        release.countDown();
        follower.close();
    }

    @Test
    void aStuckClusterDoesNotHoldBackTheOthers() {
        follower = follow(cluster -> {
            count(cluster);
            if (cluster.equals("eu")) {
                block(release);
            }
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> rounds("us") >= 5);
        assertThat(rounds("eu")).isEqualTo(1);
    }

    @Test
    void keepsFollowingAfterARoundFails() {
        follower = follow(cluster -> {
            count(cluster);
            throw new IllegalStateException("database is down");
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> rounds("eu") >= 3);
    }

    @Test
    void recordsWhenEachClusterLastFinishedARound() throws Exception {
        follower = follow(cluster -> {
            count(cluster);
            if (cluster.equals("eu")) {
                block(release);
            }
        });
        Instant started = follower.lastRounds().get("eu");

        await().atMost(Duration.ofSeconds(5)).until(() -> follower.lastRounds().get("us").isAfter(started));
        assertThat(follower.lastRounds().get("eu")).isEqualTo(started);
    }

    private ClusterFollower follow(Consumer<String> round) {
        ClusterFollower created = new ClusterFollower("test", List.of("eu", "us"), Duration.ofMillis(10),
                Clock.systemUTC(), round);
        created.start();
        return created;
    }

    private void count(String cluster) {
        rounds.computeIfAbsent(cluster, c -> new AtomicInteger()).incrementAndGet();
    }

    private int rounds(String cluster) {
        return rounds.getOrDefault(cluster, new AtomicInteger()).get();
    }

    private static void block(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
