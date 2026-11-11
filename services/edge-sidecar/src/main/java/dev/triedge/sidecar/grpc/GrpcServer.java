package dev.triedge.sidecar.grpc;

import dev.triedge.sidecar.directory.IdentityDirectory;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class GrpcServer implements SmartLifecycle {

    public static final String ALL_ADDRESSES = "0.0.0.0";

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final String address;
    private final int port;
    private final BindableService service;
    private final int threads;
    private @Nullable Server server;
    private @Nullable ExecutorService executor;

    public GrpcServer(int port, BindableService service, int threads) {
        this(ALL_ADDRESSES, port, service, threads);
    }

    public GrpcServer(String address, int port, BindableService service, int threads) {
        this.address = address;
        this.port = port;
        this.service = service;
        this.threads = threads;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("ext-authz-", 0).daemon().factory());
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(address, port))
                    .addService(service)
                    .executor(executor)
                    .build()
                    .start();
            log.info("ext_authz gRPC server listening on {}:{} with {} threads", address, server.getPort(), threads);
        } catch (IOException e) {
            executor.shutdownNow();
            executor = null;
            throw new UncheckedIOException("Could not start the gRPC server on " + address + ":" + port, e);
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
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
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

    public synchronized List<? extends SocketAddress> listenAddresses() {
        return server == null ? List.of() : server.getListenSockets();
    }
}
