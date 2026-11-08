package dev.triedge.sidecar.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LeaderCacheTest {

    private Instant now = Instant.parse("2026-10-17T12:00:00Z");
    private final Deque<Supplier<Optional<String>>> answers = new ArrayDeque<>();
    private int lookups;
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final LeaderCache cache = new LeaderCache(cluster -> {
        lookups++;
        return answers.removeFirst().get();
    }, Duration.ofSeconds(1), clock);

    @Test
    void leadersAreCachedForTheTtl() {
        answers.add(() -> Optional.of("eu"));
        answers.add(() -> Optional.of("us"));

        assertThat(cache.apply("eu")).contains("eu");
        assertThat(cache.apply("eu")).contains("eu");
        now = now.plusSeconds(2);
        assertThat(cache.apply("eu")).contains("us");
        assertThat(lookups).isEqualTo(2);
    }

    @Test
    void lookupFailuresKeepTheLastKnownLeader() {
        answers.add(() -> Optional.of("ap"));
        answers.add(() -> {
            throw new IllegalStateException("etcd is down");
        });
        answers.add(() -> {
            throw new IllegalStateException("etcd is down");
        });

        assertThat(cache.apply("ap")).contains("ap");
        now = now.plusSeconds(2);
        assertThat(cache.apply("ap")).contains("ap");
        assertThat(cache.apply("us")).isEmpty();
    }

    @Test
    void aFailedLookupIsNotRetriedUntilTheTtlPasses() {
        answers.add(() -> Optional.of("eu"));
        answers.add(() -> {
            throw new IllegalStateException("etcd is down");
        });
        answers.add(() -> Optional.of("us"));

        assertThat(cache.apply("eu")).contains("eu");
        now = now.plusSeconds(2);
        assertThat(cache.apply("eu")).contains("eu");
        assertThat(cache.apply("eu")).contains("eu");
        assertThat(cache.apply("eu")).contains("eu");
        assertThat(lookups).isEqualTo(2);
        now = now.plusSeconds(2);
        assertThat(cache.apply("eu")).contains("us");
        assertThat(lookups).isEqualTo(3);
    }

    @Test
    void concurrentCallersShareOneLookup() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        LeaderCache shared = new LeaderCache(cluster -> {
            calls.incrementAndGet();
            await(release);
            return Optional.of("ap");
        }, Duration.ofSeconds(1), Clock.systemUTC());

        List<Thread> callers = new ArrayList<>();
        List<Optional<String>> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 20; i++) {
            callers.add(Thread.ofVirtual().start(() -> results.add(shared.apply("ap"))));
        }
        Thread.sleep(200);
        release.countDown();
        for (Thread caller : callers) {
            caller.join();
        }

        assertThat(calls).hasValue(1);
        assertThat(results).hasSize(20).allMatch(result -> result.equals(Optional.of("ap")));
    }

    @Test
    void callersDoNotWaitForARefreshWhenALeaderIsKnown() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LeaderCache slow = new LeaderCache(cluster -> {
            if (calls.incrementAndGet() == 1) {
                return Optional.of("eu");
            }
            await(release);
            return Optional.of("us");
        }, Duration.ofSeconds(1), clock);

        assertThat(slow.apply("eu")).contains("eu");
        now = now.plusSeconds(2);
        List<Optional<String>> refreshed = new CopyOnWriteArrayList<>();
        Thread refresher = Thread.ofVirtual().start(() -> refreshed.add(slow.apply("eu")));
        while (calls.get() < 2) {
            Thread.onSpinWait();
        }

        assertThat(slow.apply("eu")).contains("eu");
        assertThat(calls).hasValue(2);
        release.countDown();
        refresher.join();
        assertThat(refreshed).containsExactly(Optional.of("us"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
