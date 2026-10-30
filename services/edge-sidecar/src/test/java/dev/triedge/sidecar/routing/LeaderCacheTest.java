package dev.triedge.sidecar.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LeaderCacheTest {

    private Instant now = Instant.parse("2026-10-17T12:00:00Z");
    private final Deque<Supplier<Optional<String>>> answers = new ArrayDeque<>();
    private int lookups;
    private final LeaderCache cache = new LeaderCache(cluster -> {
        lookups++;
        return answers.removeFirst().get();
    }, Duration.ofSeconds(1), new Clock() {
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
    });

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
}
