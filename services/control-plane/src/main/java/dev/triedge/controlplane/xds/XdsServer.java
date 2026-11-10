package dev.triedge.controlplane.xds;

import dev.triedge.controlplane.source.TenantDirectory;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class XdsServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(XdsServer.class);

    private final int port;
    private final SimpleCache<String> cache;
    private final AckTracker tracker;
    private final SnapshotPublisher publisher;
    private @Nullable HealthStatusManager health;
    private @Nullable Server server;
    private boolean running;

    public XdsServer(int port, SimpleCache<String> cache, AckTracker tracker, SnapshotPublisher publisher) {
        this.port = port;
        this.cache = cache;
        this.tracker = tracker;
        this.publisher = publisher;
    }

    @Override
    public synchronized void start() {
        publisher.publishNow();
        running = true;
        listen();
    }

    public synchronized void serving(boolean serving) {
        if (!running) {
            return;
        }
        if (serving && server == null) {
            log.info("Serving xDS again");
            listen();
        } else if (!serving && server != null) {
            log.warn("Not healthy; closing the xDS port so Envoy moves to another control plane");
            close(0);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        close(5);
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean listening() {
        return server != null;
    }

    @Override
    public int getPhase() {
        return TenantDirectory.PHASE + 1;
    }

    public synchronized int port() {
        return server == null ? port : server.getPort();
    }

    private void listen() {
        V3DiscoveryServer discovery = new V3DiscoveryServer(List.of(tracker), cache);
        health = new HealthStatusManager();
        try {
            server = NettyServerBuilder.forPort(port)
                    .addService(discovery.getAggregatedDiscoveryServiceImpl())
                    .addService(health.getHealthService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the xDS server on port " + port, e);
        }
        health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING);
        log.info("xDS server listening on port {}", server.getPort());
    }

    private void close(int graceSeconds) {
        if (health != null) {
            health.enterTerminalState();
            health = null;
        }
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(graceSeconds, TimeUnit.SECONDS)) {
                server.shutdownNow();
                server.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        server = null;
    }
}
