package dev.triedge.common.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class OutboxMetrics {

    private final MeterRegistry registry;
    private final String outbox;
    private final Clock clock;
    private final Map<String, AtomicLong> lastRead = new ConcurrentHashMap<>();
    private final Map<String, Timer> delays = new ConcurrentHashMap<>();

    public OutboxMetrics(MeterRegistry registry, String outbox, Clock clock) {
        this.registry = registry;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void read(String cluster) {
        lastRead.computeIfAbsent(cluster, this::lastReadGauge).set(clock.millis());
    }

    public void applied(String cluster, OutboxEvent event) {
        Duration delay = Duration.between(event.createdAt(), clock.instant());
        delays.computeIfAbsent(cluster, this::delayTimer).record(delay.isNegative() ? Duration.ZERO : delay);
    }

    private AtomicLong lastReadGauge(String cluster) {
        AtomicLong millis = new AtomicLong();
        TimeGauge.builder("triedge.outbox.last.read", millis, TimeUnit.MILLISECONDS, AtomicLong::get)
                .description("When the outbox of a cluster was last read, as a Unix time")
                .tags("outbox", outbox, "cluster", cluster)
                .register(registry);
        return millis;
    }

    private Timer delayTimer(String cluster) {
        return Timer.builder("triedge.outbox.delay")
                .description("Time from writing an outbox event to applying it here")
                .tags("outbox", outbox, "cluster", cluster)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofMinutes(1))
                .register(registry);
    }
}
