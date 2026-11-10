package dev.triedge.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry, "platform", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordsWhenEachClusterWasRead() {
        metrics.read("us");

        assertThat(registry.get("triedge.outbox.last.read").tags("outbox", "platform", "cluster", "us").timeGauge()
                .value(TimeUnit.MILLISECONDS)).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void recordsHowLongEventsTookToArrive() {
        metrics.applied("us", event(NOW.minusMillis(1500)));
        metrics.applied("us", event(NOW.plusSeconds(1)));

        var delay = registry.get("triedge.outbox.delay").tags("cluster", "us").timer();
        assertThat(delay.count()).isEqualTo(2);
        assertThat(delay.max(TimeUnit.MILLISECONDS)).isEqualTo(1500);
        assertThat(delay.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500);
    }

    private static OutboxEvent event(Instant createdAt) {
        return new OutboxEvent(1, 1, "tenant", "shop", "TENANT_DELETED", "{}", createdAt);
    }
}
