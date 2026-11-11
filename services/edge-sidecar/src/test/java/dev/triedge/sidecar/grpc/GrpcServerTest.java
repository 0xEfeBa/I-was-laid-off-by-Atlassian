package dev.triedge.sidecar.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListenableFuture;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcServerTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger mostAtOnce = new AtomicInteger();
    private final Set<String> threads = ConcurrentHashMap.newKeySet();
    private final GrpcServer server = new GrpcServer(0, new AuthorizationGrpc.AuthorizationImplBase() {
        @Override
        public void check(CheckRequest request, StreamObserver<CheckResponse> responses) {
            threads.add(Thread.currentThread().getName());
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            responses.onNext(CheckResponse.getDefaultInstance());
            responses.onCompleted();
        }
    }, 2);
    private ManagedChannel channel;

    @AfterEach
    void stop() {
        release.countDown();
        if (channel != null) {
            channel.shutdownNow();
        }
        server.stop();
    }

    @Test
    void callsRunOnAFixedNumberOfThreads() throws Exception {
        server.start();
        channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        AuthorizationGrpc.AuthorizationFutureStub stub = AuthorizationGrpc.newFutureStub(channel);

        List<ListenableFuture<CheckResponse>> calls = IntStream.range(0, 6).mapToObj(i -> stub.check(CheckRequest.getDefaultInstance())).toList();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (running.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        Thread.sleep(200);

        assertThat(running.get()).isEqualTo(2);
        release.countDown();
        for (ListenableFuture<CheckResponse> call : calls) {
            call.get(5, TimeUnit.SECONDS);
        }
        assertThat(mostAtOnce.get()).isEqualTo(2);
        assertThat(threads).hasSize(2).allMatch(name -> name.startsWith("ext-authz-"));
    }
}
