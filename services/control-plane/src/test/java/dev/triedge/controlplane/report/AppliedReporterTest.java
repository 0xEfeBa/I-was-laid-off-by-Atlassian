package dev.triedge.controlplane.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class AppliedReporterTest {

    private final SnapshotPublisher publisher = mock(SnapshotPublisher.class);
    private final AckTracker tracker = mock(AckTracker.class);
    private final EtcdClient etcd = mock(EtcdClient.class);
    private final AppliedReporter reporter = new AppliedReporter(publisher, tracker, etcd, "eu");

    @BeforeEach
    void proxiesAreSynced() {
        when(tracker.connectedNodes()).thenReturn(1);
        when(tracker.allSynced(any())).thenReturn(true);
    }

    @Test
    void removesWhatWasLeftBehindWhileTheControlPlaneWasDown() {
        live(Map.of("shop", 2));
        when(etcd.getPrefix(AppliedReporter.PREFIX)).thenReturn(Map.of(
                AppliedReporter.key("ghost", "eu"), "5",
                AppliedReporter.key("shop", "eu"), "2",
                AppliedReporter.key("ghost", "us"), "5"));

        reporter.report();

        verify(etcd).apply(Map.of(), List.of(AppliedReporter.key("ghost", "eu")));
    }

    @Test
    void writesEveryChangeInOneCall() {
        when(etcd.getPrefix(AppliedReporter.PREFIX)).thenReturn(Map.of());
        live(Map.of("a", 1, "b", 3));

        reporter.report();
        reporter.report();

        verify(etcd, times(1)).apply(Map.of(AppliedReporter.key("a", "eu"), "1", AppliedReporter.key("b", "eu"), "3"),
                List.of());
    }

    @Test
    void triesAgainAfterEtcdFails() {
        when(etcd.getPrefix(AppliedReporter.PREFIX)).thenReturn(Map.of());
        live(Map.of("a", 1));
        doThrow(new ResourceAccessException("etcd is down")).doNothing().when(etcd).apply(anyMap(), anyCollection());

        reporter.report();
        reporter.report();

        verify(etcd, times(2)).apply(Map.of(AppliedReporter.key("a", "eu"), "1"), List.of());
    }

    @Test
    void waitsUntilTheProxiesHaveTheConfiguration() {
        live(Map.of("a", 1));
        when(tracker.allSynced(any())).thenReturn(false);

        reporter.report();

        verify(etcd, never()).apply(anyMap(), anyCollection());
    }

    private void live(Map<String, Integer> tenants) {
        when(publisher.latest()).thenReturn(new SnapshotPublisher.Published(1, Map.of(), tenants));
    }
}
