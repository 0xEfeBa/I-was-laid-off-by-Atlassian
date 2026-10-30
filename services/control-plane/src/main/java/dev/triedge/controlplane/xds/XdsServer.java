package dev.triedge.controlplane.xds;

import dev.triedge.controlplane.source.TenantDirectory;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
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
    private @Nullable Server server;

    public XdsServer(int port, SimpleCache<String> cache, AckTracker tracker, SnapshotPublisher publisher) {
        this.port = port;
        this.cache = cache;
        this.tracker = tracker;
        this.publisher = publisher;
    }

    @Override
    public synchronized void start() {
        publisher.publishNow();
        V3DiscoveryServer discovery = new V3DiscoveryServer(List.of(tracker), cache);
        try {
            server = NettyServerBuilder.forPort(port)
                    .addService(discovery.getAggregatedDiscoveryServiceImpl())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the xDS server on port " + port, e);
        }
        log.info("xDS server listening on port {}", server.getPort());
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
            server = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null;
    }

    @Override
    public int getPhase() {
        return TenantDirectory.PHASE + 1;
    }

    public synchronized int port() {
        return server == null ? port : server.getPort();
    }
}
