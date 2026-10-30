package dev.triedge.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.sidecar.directory.IdentityDirectory;
import dev.triedge.sidecar.grpc.GrpcServer;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SidecarApplicationIT {

    @Autowired
    private IdentityDirectory directory;

    @Autowired
    private GrpcServer grpc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ClusterConnection connection = TestDatabase.connection(2);
        registry.add("triedge.region", () -> "eu");
        registry.add("triedge.clusters.eu.url", connection::url);
        registry.add("triedge.clusters.eu.username", connection::username);
        registry.add("triedge.clusters.eu.password", connection::password);
        registry.add("triedge.sidecar.grpc-port", () -> "0");
        registry.add("triedge.sidecar.issuer", () -> "tri-edge-test");
        registry.add("triedge.sidecar.jwks-url", () -> "http://127.0.0.1:1/jwks");
        registry.add("server.port", () -> "0");
    }

    @Test
    void loadsIdentityDataBeforeServingChecks() {
        assertThat(directory.isRunning()).isTrue();
        assertThat(grpc.isRunning()).isTrue();

        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            CheckResponse response = AuthorizationGrpc.newBlockingStub(channel).check(CheckRequest.getDefaultInstance());
            assertThat(response.getDeniedResponse().getStatus().getCodeValue()).isEqualTo(403);
        } finally {
            channel.shutdownNow();
        }
    }
}
