package dev.triedge.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.triedge.sidecar.auth.JwksProvider;
import dev.triedge.sidecar.auth.JwtValidator;
import dev.triedge.sidecar.auth.PatValidator;
import dev.triedge.sidecar.authz.AuthPolicy;
import dev.triedge.sidecar.directory.IdentityIndex;
import dev.triedge.sidecar.grpc.AuthorizationService;
import dev.triedge.sidecar.grpc.GrpcServer;
import dev.triedge.sidecar.hooks.HeaderAllowlist;
import dev.triedge.sidecar.hooks.HookClient;
import dev.triedge.sidecar.routing.PlatformRouting;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

class EnvoyExtAuthzIT {

    private static final String ENVOY_CONFIG = """
            static_resources:
              listeners:
                - name: public
                  address:
                    socket_address: { address: 0.0.0.0, port_value: 10000 }
                  filter_chains:
                    - filters:
                        - name: envoy.filters.network.http_connection_manager
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                            stat_prefix: public
                            route_config:
                              virtual_hosts:
                                - name: test
                                  domains: ["*"]
                                  routes:
                                    - match: { prefix: "/internal/" }
                                      direct_response: { status: 404 }
                                      typed_per_filter_config:
                                        envoy.filters.http.ext_authz:
                                          "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute
                                          disabled: true
                                    - match: { prefix: "/open" }
                                      metadata:
                                        filter_metadata:
                                          triedge.auth: { tenant: test, auth: none, regions: [eu, us] }
                                      route: { cluster_header: x-target-region }
                                    - match: { prefix: "/private" }
                                      metadata:
                                        filter_metadata:
                                          triedge.auth: { tenant: test, auth: jwt, regions: [eu, us], route_by: home }
                                      route: { cluster_header: x-target-region }
                            http_filters:
                              - name: envoy.filters.http.ext_authz
                                typed_config:
                                  "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz
                                  transport_api_version: V3
                                  failure_mode_allow: false
                                  clear_route_cache: true
                                  route_metadata_context_namespaces: [triedge.auth]
                                  grpc_service:
                                    envoy_grpc: { cluster_name: sidecar }
                                    timeout: 1s
                              - name: envoy.filters.http.router
                                typed_config:
                                  "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
              clusters:
                - name: sidecar
                  type: STRICT_DNS
                  typed_extension_protocol_options:
                    envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                      "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
                      explicit_http_config: { http2_protocol_options: {} }
                  load_assignment:
                    cluster_name: sidecar
                    endpoints: [{ lb_endpoints: [{ endpoint: { address: { socket_address: { address: host.testcontainers.internal, port_value: %d } } } }] }]
                - name: eu
                  type: STRICT_DNS
                  load_assignment:
                    cluster_name: eu
                    endpoints: [{ lb_endpoints: [{ endpoint: { address: { socket_address: { address: host.testcontainers.internal, port_value: %d } } } }] }]
                - name: us
                  type: STRICT_DNS
                  load_assignment:
                    cluster_name: us
                    endpoints: [{ lb_endpoints: [{ endpoint: { address: { socket_address: { address: host.testcontainers.internal, port_value: %d } } } }] }]
            """;

    private static TestTokens tokens;
    private static GrpcServer sidecar;
    private static HttpServer eu;
    private static HttpServer us;
    private static GenericContainer<?> envoy;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        tokens = new TestTokens();
        IdentityIndex index = new IdentityIndex();
        AuthorizationService service = new AuthorizationService(
                new AuthPolicy(new JwtValidator(new JwksProvider(RestClient.create(), tokens.jwksUrl(),
                        Duration.ofSeconds(30), Clock.systemUTC()), TestTokens.ISSUER, Clock.systemUTC()),
                        new PatValidator(index, Clock.systemUTC()), index),
                new PlatformRouting("eu"),
                new HookClient(target -> {
                    throw new IllegalStateException("no hooks in this test");
                }, Duration.ofMillis(50)),
                new HeaderAllowlist());
        int grpcPort = freePort();
        sidecar = new GrpcServer(grpcPort, service);
        sidecar.start();
        eu = upstream("eu");
        us = upstream("us");
        Testcontainers.exposeHostPorts(grpcPort, eu.getAddress().getPort(), us.getAddress().getPort());

        envoy = new GenericContainer<>("envoyproxy/envoy:v1.39.4")
                .withCopyToContainer(Transferable.of(ENVOY_CONFIG.formatted(
                        grpcPort, eu.getAddress().getPort(), us.getAddress().getPort())), "/etc/envoy/envoy.yaml")
                .withCommand("-c", "/etc/envoy/envoy.yaml", "--log-level", "warn")
                .withExposedPorts(10000)
                .waitingFor(Wait.forListeningPort());
        envoy.start();
        awaitSidecarReachable();
    }

    private static void awaitSidecarReachable() {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (send(HttpRequest.newBuilder(uri("/private"))).statusCode() == 401) {
                    return;
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (Exception e) {
                continue;
            }
        }
        throw new IllegalStateException("Envoy never reached the sidecar");
    }

    @AfterAll
    static void stop() {
        if (envoy != null) {
            envoy.stop();
        }
        sidecar.stop();
        eu.stop(0);
        us.stop(0);
        tokens.close();
    }

    @Test
    void routeMetadataReachesTheSidecarAndSpoofedIdentityNeverReachesTheBackend() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/open"))
                .header("x-user-id", "00000000-0000-0000-0000-000000000000")
                .header("x-user-role", "ADMIN"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("served-by=eu").contains("x-user-id=null").contains("x-user-role=null");
    }

    @Test
    void protectedRouteRequiresASession() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/private")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
    }

    @Test
    void headerFromTheSidecarSelectsTheBackendRegion() throws Exception {
        UUID userId = UUID.randomUUID();
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/private"))
                .header("authorization", "Bearer " + tokens.token(userId, "us"))
                .header("x-target-region", "eu"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("served-by=us").contains("x-user-id=" + userId);
    }

    @Test
    void internalPathsAreNeverExposed() throws Exception {
        assertThat(send(HttpRequest.newBuilder(uri("/internal/anything"))).statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://" + envoy.getHost() + ":" + envoy.getMappedPort(10000) + path);
    }

    private static HttpServer upstream(String region) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", exchange -> {
            String body = "served-by=" + region
                    + "\nx-user-id=" + exchange.getRequestHeaders().getFirst("x-user-id")
                    + "\nx-user-role=" + exchange.getRequestHeaders().getFirst("x-user-role");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
