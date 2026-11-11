package dev.triedge.controlplane.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class XdsServerTest {

    @SuppressWarnings("unchecked")
    private final SimpleCache<String> cache = mock(SimpleCache.class);

    @Test
    void listensOnlyOnTheGivenAddress() {
        XdsServer local = new XdsServer("127.0.0.1", 0, cache, mock(AckTracker.class), mock(SnapshotPublisher.class));
        XdsServer everywhere = new XdsServer(0, cache, mock(AckTracker.class), mock(SnapshotPublisher.class));
        local.start();
        everywhere.start();
        try {
            assertThat(local.listenAddresses()).singleElement()
                    .satisfies(address -> assertThat(((InetSocketAddress) address).getAddress().isLoopbackAddress()).isTrue());
            assertThat(everywhere.listenAddresses()).singleElement()
                    .satisfies(address -> assertThat(((InetSocketAddress) address).getAddress().isAnyLocalAddress()).isTrue());
        } finally {
            local.stop();
            everywhere.stop();
        }
    }
}
