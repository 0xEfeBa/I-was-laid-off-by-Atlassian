package dev.triedge.controlplane.health;

import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.controlplane.xds.XdsServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.scheduling.annotation.Scheduled;

public class ControlPlaneHealth implements HealthIndicator {

    private final TenantDirectory directory;
    private final XdsServer xds;
    private final Clock clock;
    private final Duration maxFollowAge;

    public ControlPlaneHealth(TenantDirectory directory, XdsServer xds, Clock clock, Duration maxFollowAge) {
        this.directory = directory;
        this.xds = xds;
        this.clock = clock;
        this.maxFollowAge = maxFollowAge;
    }

    @Override
    public Health health() {
        Map<String, Instant> rounds = directory.lastRounds();
        List<String> stuck = directory.clusters().stream().filter(cluster -> stuck(rounds.get(cluster))).toList();
        Health.Builder health = !stuck.isEmpty() || !xds.isRunning() ? Health.down() : Health.up();
        return health
                .withDetail("xds", xds.isRunning() ? "running" : "stopped")
                .withDetail("stuck", stuck)
                .withDetail("lastRounds", rounds)
                .withDetail("caughtUp", directory.caughtUp())
                .build();
    }

    @Scheduled(fixedDelayString = "${triedge.control-plane.health-interval:PT1S}")
    public void publish() {
        xds.serving(Status.UP.equals(health().getStatus()));
    }

    private boolean stuck(@Nullable Instant lastRound) {
        return lastRound == null || lastRound.plus(maxFollowAge).isBefore(clock.instant());
    }
}
