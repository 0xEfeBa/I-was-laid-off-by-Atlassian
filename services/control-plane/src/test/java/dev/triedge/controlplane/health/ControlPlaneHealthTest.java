package dev.triedge.controlplane.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.controlplane.xds.XdsServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class ControlPlaneHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private final TenantDirectory directory = mock(TenantDirectory.class);
    private final XdsServer xds = mock(XdsServer.class);
    private final ControlPlaneHealth health = new ControlPlaneHealth(directory, xds,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

    @BeforeEach
    void healthyByDefault() {
        when(xds.isRunning()).thenReturn(true);
        when(directory.lastFollow()).thenReturn(NOW.minusSeconds(1));
        when(directory.caughtUp()).thenReturn(Map.of("eu", NOW.minusSeconds(1), "us", NOW.minusSeconds(600)));
    }

    @Test
    void isUpWhileTenantEventsAreFollowedEvenIfAClusterIsBehind() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        health.publish();

        verify(xds).serving(true);
    }

    @Test
    void isDownWhenFollowingStopped() {
        when(directory.lastFollow()).thenReturn(NOW.minusSeconds(31));

        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        health.publish();

        verify(xds).serving(false);
    }

    @Test
    void isDownBeforeTheFirstLoadAndWithoutTheXdsServer() {
        when(directory.lastFollow()).thenReturn(null);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        when(directory.lastFollow()).thenReturn(NOW);
        when(xds.isRunning()).thenReturn(false);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
