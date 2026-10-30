package dev.triedge.sidecar.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.Code;
import com.google.rpc.Status;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HookClientTest {

    private final AtomicReference<CheckResponse> next = new AtomicReference<>();
    private final AtomicReference<CheckRequest> received = new AtomicReference<>();
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    private Server server;
    private HookClient client;

    @BeforeEach
    void start() throws Exception {
        server = InProcessServerBuilder.forName("hook").addService(new AuthorizationGrpc.AuthorizationImplBase() {
            @Override
            public void check(CheckRequest request, StreamObserver<CheckResponse> responses) {
                received.set(request);
                try {
                    Thread.sleep(delay.get().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                responses.onNext(next.get());
                responses.onCompleted();
            }
        }).build().start();
        client = new HookClient(target -> InProcessChannelBuilder.forName(target).build(), Duration.ofMillis(200));
    }

    @AfterEach
    void stop() {
        client.close();
        server.shutdownNow();
    }

    @Test
    void allowedResponsesCarryTheHookHeaders() {
        next.set(CheckResponse.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.OK_VALUE))
                .setOkResponse(OkHttpResponse.newBuilder().addHeaders(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder().setKey("x-backend-set").setValue("shard-1"))))
                .build());
        CheckRequest request = CheckRequest.getDefaultInstance();

        assertThat(client.call("hook", request))
                .isEqualTo(new HookClient.Result.Allowed(Map.of("x-backend-set", "shard-1")));
        assertThat(received.get()).isEqualTo(request);
    }

    @Test
    void deniedResponsesKeepTheirStatus() {
        next.set(denied(StatusCode.NotFound));

        assertThat(client.call("hook", CheckRequest.getDefaultInstance())).isEqualTo(new HookClient.Result.Denied(404));
    }

    @Test
    void deniedWithoutAnHttpStatusBecomesForbidden() {
        next.set(CheckResponse.newBuilder().setStatus(Status.newBuilder().setCode(Code.PERMISSION_DENIED_VALUE)).build());

        assertThat(client.call("hook", CheckRequest.getDefaultInstance())).isEqualTo(new HookClient.Result.Denied(403));
    }

    @Test
    void slowOrMissingHooksAreUnavailable() {
        next.set(denied(StatusCode.NotFound));
        delay.set(Duration.ofSeconds(1));

        assertThat(client.call("hook", CheckRequest.getDefaultInstance()))
                .isEqualTo(new HookClient.Result.Unavailable("DEADLINE_EXCEEDED"));
        assertThat(client.call("missing", CheckRequest.getDefaultInstance()))
                .isInstanceOf(HookClient.Result.Unavailable.class);
    }

    private static CheckResponse denied(StatusCode status) {
        return CheckResponse.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.PERMISSION_DENIED_VALUE))
                .setDeniedResponse(DeniedHttpResponse.newBuilder().setStatus(HttpStatus.newBuilder().setCode(status)))
                .build();
    }
}
