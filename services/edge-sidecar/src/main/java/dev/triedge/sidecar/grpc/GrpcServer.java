package dev.triedge.sidecar.grpc;

import dev.triedge.sidecar.directory.IdentityDirectory;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class GrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final int port;
    private final BindableService service;
    private @Nullable Server server;

    public GrpcServer(int port, BindableService service) {
        this.port = port;
        this.service = service;
    }

    @Override
    public synchronized void start() {
        try {
            server = NettyServerBuilder.forPort(port).addService(service).build().start();
            log.info("ext_authz gRPC server listening on port {}", server.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the gRPC server on port " + port, e);
        }
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
        return IdentityDirectory.PHASE + 1;
    }

    public synchronized int port() {
        return server == null ? port : server.getPort();
    }
}
