package dev.triedge.controlplane.xds;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.Status;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AckTrackerTest {

    private static final String CDS = Resources.V3.CLUSTER_TYPE_URL;
    private static final String RDS = Resources.V3.ROUTE_TYPE_URL;

    private final AckTracker tracker = new AckTracker(() -> 7);

    @Test
    void nodeIsSyncedOnceEveryChangedTypeIsAcked() {
        tracker.onStreamOpen(1, "");
        tracker.onV3StreamDeltaRequest(1, deltaRequest("", null).toBuilder().setNode(Node.newBuilder().setId("envoy-1")).build());
        tracker.onV3StreamDeltaResponse(1, deltaRequest("", null), deltaResponse(CDS, "n1", "v3"));
        tracker.onV3StreamDeltaResponse(1, deltaRequest("", null), deltaResponse(RDS, "n2", "v3"));

        assertThat(tracker.connectedNodes()).isEqualTo(1);
        assertThat(tracker.allSynced(Map.of(CDS, 3L, RDS, 3L))).isFalse();

        tracker.onV3StreamDeltaRequest(1, deltaRequest("n1", null));
        assertThat(tracker.allSynced(Map.of(CDS, 3L, RDS, 3L))).isFalse();

        tracker.onV3StreamDeltaRequest(1, deltaRequest("n2", null));
        assertThat(tracker.allSynced(Map.of(CDS, 3L, RDS, 3L))).isTrue();
        assertThat(tracker.allSynced(Map.of(CDS, 4L))).isFalse();
    }

    @Test
    void rejectedConfigurationIsRecordedAndNotAcked() {
        tracker.onV3StreamDeltaRequest(2, deltaRequest("", null).toBuilder().setNode(Node.newBuilder().setId("envoy-2")).build());
        tracker.onV3StreamDeltaResponse(2, deltaRequest("", null), deltaResponse(CDS, "n1", "v5"));

        tracker.onV3StreamDeltaRequest(2, deltaRequest("n1", "port out of range"));

        assertThat(tracker.allSynced(Map.of(CDS, 5L))).isFalse();
        assertThat(tracker.recentNacks()).singleElement()
                .isEqualTo(new AckTracker.Nack("envoy-2", CDS, 5, "port out of range"));
    }

    @Test
    void stateOfTheWorldStreamsAreTrackedToo() {
        tracker.onV3StreamRequest(3, DiscoveryRequest.newBuilder().setNode(Node.newBuilder().setId("envoy-3")).build());
        tracker.onV3StreamResponse(3, DiscoveryRequest.getDefaultInstance(),
                DiscoveryResponse.newBuilder().setTypeUrl(CDS).setNonce("a").setVersionInfo("not-a-version").build());
        tracker.onV3StreamRequest(3, DiscoveryRequest.newBuilder().setResponseNonce("a").build());
        tracker.onV3StreamRequest(3, DiscoveryRequest.newBuilder().setResponseNonce("unknown").build());

        assertThat(tracker.allSynced(Map.of(CDS, 7L))).isTrue();
    }

    @Test
    void closedStreamsNoLongerCount() {
        tracker.onV3StreamDeltaRequest(4, deltaRequest("", null).toBuilder().setNode(Node.newBuilder().setId("envoy-4")).build());
        tracker.onV3StreamDeltaRequest(5, deltaRequest("", null).toBuilder().setNode(Node.newBuilder().setId("envoy-5")).build());

        tracker.onStreamClose(4, "");
        tracker.onStreamCloseWithError(5, "", new RuntimeException("gone"));

        assertThat(tracker.connectedNodes()).isZero();
        assertThat(tracker.allSynced(Map.of(CDS, 99L))).isTrue();
    }

    private static DeltaDiscoveryRequest deltaRequest(String nonce, String error) {
        DeltaDiscoveryRequest.Builder request = DeltaDiscoveryRequest.newBuilder().setResponseNonce(nonce);
        if (error != null) {
            request.setErrorDetail(Status.newBuilder().setMessage(error));
        }
        return request.build();
    }

    private static DeltaDiscoveryResponse deltaResponse(String type, String nonce, String version) {
        return DeltaDiscoveryResponse.newBuilder().setTypeUrl(type).setNonce(nonce).setSystemVersionInfo(version).build();
    }
}
