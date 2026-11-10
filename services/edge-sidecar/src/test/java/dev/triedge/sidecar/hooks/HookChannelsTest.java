package dev.triedge.sidecar.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.Code;
import com.google.rpc.Status;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HookChannelsTest {

    private Server server;

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new AuthorizationGrpc.AuthorizationImplBase() {
            @Override
            public void check(CheckRequest request, StreamObserver<CheckResponse> responses) {
                responses.onNext(CheckResponse.newBuilder().setStatus(Status.newBuilder().setCode(Code.OK_VALUE)).build());
                responses.onCompleted();
            }
        }).build().start();
    }

    @AfterEach
    void stop() {
        server.shutdownNow();
    }

    @Test
    void hooksAreCalledFromTheGivenSourceAddress() {
        String target = "127.0.0.1:" + server.getPort();

        try (HookClient local = new HookClient(t -> HookChannels.create(t, "127.0.0.1"), Duration.ofSeconds(2));
                HookClient missing = new HookClient(t -> HookChannels.create(t, "192.0.2.1"), Duration.ofSeconds(2))) {
            assertThat(local.call(target, CheckRequest.getDefaultInstance())).isInstanceOf(HookClient.Result.Allowed.class);
            assertThat(missing.call(target, CheckRequest.getDefaultInstance()))
                    .isInstanceOf(HookClient.Result.Unavailable.class);
        }
    }
}
