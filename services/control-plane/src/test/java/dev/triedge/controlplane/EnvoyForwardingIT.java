package dev.triedge.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.Code;
import com.google.rpc.Status;
import com.sun.net.httpserver.HttpServer;
import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import dev.triedge.controlplane.xds.XdsServer;
import dev.triedge.tenant.TenantSpec;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

class EnvoyForwardingIT {

    private static final String HOST = "host.testcontainers.internal";
    private static final int LISTENER = 10000;
    private static final String HOME = "x-test-home";
    private static final String BOOTSTRAP = """
            node: { id: envoy-%s, cluster: edge }
            admin:
              address: { socket_address: { address: 0.0.0.0, port_value: 9901 } }
            dynamic_resources:
              ads_config:
                api_type: DELTA_GRPC
                transport_api_version: V3
                grpc_services: [{ envoy_grpc: { cluster_name: xds } }]
              cds_config: { ads: {}, resource_api_version: V3 }
              lds_config: { ads: {}, resource_api_version: V3 }
            static_resources:
              clusters:
                - name: xds
                  type: STRICT_DNS
                  typed_extension_protocol_options:
                    envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                      "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
                      explicit_http_config: { http2_protocol_options: {} }
                  load_assignment:
                    cluster_name: xds
                    endpoints: [{ lb_endpoints: [{ endpoint: { address: { socket_address: { address: %s, port_value: %d } } } }] }]
            """;

    private static final HttpClient http = HttpClient.newHttpClient();
    private static Network network;
    private static Server authz;
    private static HttpServer upstream;
    private static Region eu;
    private static Region us;

    private record Region(String name, List<ActiveTenant> tenants, SnapshotPublisher publisher, XdsServer xds,
            GenericContainer<?> envoy) {

        void stop() {
            envoy.stop();
            xds.stop();
            publisher.close();
        }
    }

    @BeforeAll
    static void start() throws IOException {
        authz = NettyServerBuilder.forPort(0).addService(new AuthorizationGrpc.AuthorizationImplBase() {
            @Override
            public void check(CheckRequest request, StreamObserver<CheckResponse> responses) {
                OkHttpResponse.Builder ok = OkHttpResponse.newBuilder();
                String home = request.getAttributes().getRequest().getHttp().getHeadersMap().get(HOME);
                if (home != null) {
                    ok.addHeaders(HeaderValueOption.newBuilder().setHeader(HeaderValue.newBuilder()
                            .setKey(ConfigRenderer.TARGET_REGION_HEADER).setValue(home)));
                }
                responses.onNext(CheckResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.OK_VALUE))
                        .setOkResponse(ok)
                        .build());
                responses.onCompleted();
            }
        }).build().start();
        upstream = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        network = Network.newNetwork();
        String subnet = DockerClientFactory.instance().client().inspectNetworkCmd().withNetworkId(network.getId())
                .exec().getIpam().getConfig().getFirst().getSubnet();
        int backend = upstream.getAddress().getPort();
        eu = region("eu", Map.of("us", "envoy-us"), subnet, List.of(
                tenant("blog", List.of("us"), TenantSpec.RouteBy.LOCAL, backend),
                tenant("shop", List.of("eu", "us"), TenantSpec.RouteBy.HOME, backend),
                tenant("loop", List.of("us"), TenantSpec.RouteBy.LOCAL, backend)));
        us = region("us", Map.of("eu", "envoy-eu"), subnet, List.of(
                tenant("blog", List.of("us"), TenantSpec.RouteBy.LOCAL, backend),
                tenant("shop", List.of("eu", "us"), TenantSpec.RouteBy.HOME, backend),
                tenant("loop", List.of("eu"), TenantSpec.RouteBy.LOCAL, backend)));
    }

    @AfterAll
    static void stop() {
        for (Region region : List.of(eu, us)) {
            if (region != null) {
                region.stop();
            }
        }
        network.close();
        authz.shutdownNow();
        upstream.stop(0);
    }

    @Test
    void aTenantThatDoesNotRunHereIsServedByItsRegion() throws Exception {
        HttpResponse<String> response = eventually(eu, "blog.test", Map.of(), 200);

        assertThat(response.headers().firstValue(ConfigRenderer.SERVED_BY_HEADER)).contains("us");
        assertThat(get(us, "blog.test", Map.of()).headers().firstValue(ConfigRenderer.SERVED_BY_HEADER)).contains("us");
    }

    @Test
    void homeRoutesFollowTheCallersRegion() throws Exception {
        eventually(eu, "shop.test", Map.of(HOME, "us"), 200);

        assertThat(get(eu, "shop.test", Map.of(HOME, "us")).headers().firstValue(ConfigRenderer.SERVED_BY_HEADER))
                .contains("us");
        assertThat(get(eu, "shop.test", Map.of(HOME, "eu")).headers().firstValue(ConfigRenderer.SERVED_BY_HEADER))
                .contains("eu");
        assertThat(get(eu, "shop.test", Map.of()).headers().firstValue(ConfigRenderer.SERVED_BY_HEADER))
                .contains("eu");
    }

    @Test
    void aRequestIsForwardedAtMostOnce() throws Exception {
        eventually(eu, "loop.test", Map.of(), 508);

        assertThat(get(us, "loop.test", Map.of()).statusCode()).isEqualTo(508);
        assertThat(get(us, "blog.test", Map.of(ConfigRenderer.HOP_HEADER, "eu")).statusCode()).isEqualTo(200);
    }

    private static Region region(String name, Map<String, String> remotes, String subnet, List<ActiveTenant> tenants)
            throws IOException {
        List<ActiveTenant> list = new CopyOnWriteArrayList<>(tenants);
        ConfigRenderer renderer = new ConfigRenderer(name, LISTENER, HOST, authz.getPort(), remotes, List.of(subnet));
        SimpleCache<String> cache = new SimpleCache<>(node -> SnapshotPublisher.GROUP);
        SnapshotPublisher publisher = new SnapshotPublisher(cache, renderer, () -> list, Duration.ofMillis(50));
        int port = freePort();
        XdsServer xds = new XdsServer(port, cache, new AckTracker(publisher::version), publisher);
        xds.start();
        Testcontainers.exposeHostPorts(port, authz.getPort(), upstream.getAddress().getPort());
        GenericContainer<?> envoy = new GenericContainer<>("envoyproxy/envoy:v1.39.4")
                .withNetwork(network)
                .withNetworkAliases("envoy-" + name)
                .withCopyToContainer(Transferable.of(BOOTSTRAP.formatted(name, HOST, port)), "/etc/envoy/envoy.yaml")
                .withCommand("-c", "/etc/envoy/envoy.yaml", "--log-level", "warn")
                .withExposedPorts(LISTENER, 9901)
                .waitingFor(Wait.forHttp("/ready").forPort(9901));
        envoy.start();
        return new Region(name, list, publisher, xds, envoy);
    }

    private static ActiveTenant tenant(String name, List<String> regions, TenantSpec.RouteBy routeBy, int port) {
        return TestTenants.tenant(name, 1, new TenantSpec(name, regions, List.of(name + ".test"),
                List.of(new TenantSpec.Backend("web", HOST, port)),
                List.of(new TenantSpec.Route("/", TenantSpec.Match.PREFIX, List.of(), "web", TenantSpec.Auth.NONE,
                        List.of(), routeBy, null, null)),
                null));
    }

    private static HttpResponse<String> get(Region region, String host, Map<String, String> headers) throws Exception {
        URI uri = URI.create("http://" + region.envoy().getHost() + ":" + region.envoy().getMappedPort(LISTENER) + "/");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Host", host).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> eventually(Region region, String host, Map<String, String> headers,
            int status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<String> last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = get(region, host, headers);
                if (last.statusCode() == status) {
                    return last;
                }
            } catch (IOException e) {
                last = null;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Expected " + status + " from " + host + " in " + region.name() + " but got "
                + (last == null ? "no response" : last.statusCode() + " " + last.headers().map())
                + "; logs: " + region.envoy().getLogs().lines().limit(20).toList());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
