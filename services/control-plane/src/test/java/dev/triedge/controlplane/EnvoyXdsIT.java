package dev.triedge.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Duration;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.sun.net.httpserver.HttpServer;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.testing.TestEtcd;
import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.render.EnvoyResources;
import dev.triedge.controlplane.report.AppliedReporter;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.source.SystemTenants;
import dev.triedge.controlplane.validate.BootstrapJson;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import dev.triedge.controlplane.xds.XdsServer;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnvoyXdsIT {

    private static final Logger log = LoggerFactory.getLogger(EnvoyXdsIT.class);
    private static final int TENANTS = 500;
    private static final String HOST = "host.testcontainers.internal";
    private static final String BOOTSTRAP = """
            node: { id: envoy-test, cluster: edge }
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
    private static final List<ActiveTenant> tenants = new CopyOnWriteArrayList<>();
    private static Server authz;
    private static HttpServer upstream;
    private static SimpleCache<String> cache;
    private static SnapshotPublisher publisher;
    private static AckTracker tracker;
    private static XdsServer xds;
    private static ConfigRenderer renderer;
    private static GenericContainer<?> envoy;

    @BeforeAll
    static void start() throws IOException {
        authz = NettyServerBuilder.forPort(0).addService(new AuthorizationGrpc.AuthorizationImplBase() {
            @Override
            public void check(CheckRequest request, StreamObserver<CheckResponse> responses) {
                responses.onNext(CheckResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.OK_VALUE))
                        .setOkResponse(OkHttpResponse.getDefaultInstance())
                        .build());
                responses.onCompleted();
            }
        }).build().start();
        upstream = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = ("served " + exchange.getRequestHeaders().getFirst("host")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        for (int i = 0; i < TENANTS; i++) {
            tenants.add(TestTenants.tenant("t" + i, "t" + i + ".test", HOST, upstream.getAddress().getPort()));
        }

        int xdsPort = freePort();
        Testcontainers.exposeHostPorts(xdsPort, authz.getPort(), upstream.getAddress().getPort());
        renderer = new ConfigRenderer("eu", 10000, HOST, authz.getPort());
        cache = new SimpleCache<>(node -> SnapshotPublisher.GROUP);
        publisher = new SnapshotPublisher(cache, renderer, () -> tenants, java.time.Duration.ofMillis(100));
        tracker = new AckTracker(publisher::version);
        xds = new XdsServer(xdsPort, cache, tracker, publisher);
        xds.start();

        envoy = new GenericContainer<>("envoyproxy/envoy:v1.39.4")
                .withCopyToContainer(Transferable.of(BOOTSTRAP.formatted(HOST, xdsPort)), "/etc/envoy/envoy.yaml")
                .withCommand("-c", "/etc/envoy/envoy.yaml", "--log-level", "warn")
                .withExposedPorts(10000, 9901)
                .waitingFor(Wait.forHttp("/ready").forPort(9901));
        envoy.start();
    }

    @AfterAll
    static void stop() {
        if (envoy != null) {
            envoy.stop();
        }
        xds.stop();
        publisher.close();
        authz.shutdownNow();
        upstream.stop(0);
    }

    @Test
    @Order(1)
    void envoyLoadsFiveHundredTenantsOverDeltaXds() throws Exception {
        Instant started = Instant.now();
        eventually(() -> status("t499.test") == 200);
        log.info("Envoy served {} tenants {} ms after start", TENANTS, java.time.Duration.between(started, Instant.now()).toMillis());

        assertThat(body("t0.test")).isEqualTo("served t0.test");
        assertThat(status("unknown.test")).isEqualTo(404);
        assertThat(status("t1.test", "/internal/x")).isEqualTo(404);
        eventually(() -> tracker.connectedNodes() == 1 && tracker.allSynced(publisher.latest().requiredAcks()));
    }

    @Test
    @Order(2)
    void appliedVersionsAreReportedOnceEveryEnvoyHasAcked() {
        AppliedReporter reporter = new AppliedReporter(publisher, tracker, new EtcdClient(TestEtcd.client()), "eu");

        reporter.report();

        EtcdClient etcd = new EtcdClient(TestEtcd.client());
        assertThat(etcd.get(AppliedReporter.key("t0", "eu"))).contains("1");
        assertThat(etcd.get(AppliedReporter.key("t499", "eu"))).contains("1");

        tenants.removeIf(t -> t.name().equals("t0"));
        tenants.replaceAll(t -> t.name().equals("t1") ? TestTenants.tenant("t1", 2, t.spec()) : t);
        publisher.requestPublish();
        eventually(() -> status("t0.test") == 404);
        eventually(() -> tracker.allSynced(publisher.latest().requiredAcks()));
        reporter.report();

        assertThat(etcd.get(AppliedReporter.key("t0", "eu"))).isEmpty();
        assertThat(etcd.get(AppliedReporter.key("t1", "eu"))).contains("2");
    }

    @Test
    @Order(3)
    void envoyRejectionsAreRecorded() {
        EnvoyResources good = renderer.render(tenants, false);
        List<Cluster> clusters = new ArrayList<>(good.clusters());
        clusters.add(Cluster.newBuilder().setName("broken")
                .setType(Cluster.DiscoveryType.STATIC)
                .setConnectTimeout(Duration.newBuilder().setSeconds(-1))
                .build());
        cache.setSnapshot(SnapshotPublisher.GROUP,
                Snapshot.create(clusters, List.of(), good.listeners(), good.routes(), List.of(), "v1000"));

        eventually(() -> !tracker.recentNacks().isEmpty());

        assertThat(tracker.recentNacks().getLast().version()).isEqualTo(1000);
        assertThat(tracker.allSynced(java.util.Map.of(io.envoyproxy.controlplane.cache.Resources.V3.CLUSTER_TYPE_URL, 1000L))).isFalse();
        publisher.publishNow();
    }

    @Test
    @Order(4)
    void validationConfigIsAcceptedByTheRealEnvoyBinary() throws Exception {
        EnvoyResources resources = renderer.render(tenants, true);

        assertThat(validate(BootstrapJson.of(resources)).getExitCode()).isZero();

        List<ActiveTenant> withPlatform = new ArrayList<>(tenants);
        withPlatform.addAll(SystemTenants.load(System.getProperty("triedge.system-tenants")));
        EnvoyResources multiRegion = new ConfigRenderer("eu", 10000, HOST, authz.getPort(),
                Map.of("us", "10.66.0.2", "ap", "{host}.ap.internal")).render(withPlatform, true);
        assertThat(multiRegion.clusters()).extracting(Cluster::getName).contains("tenant.platform.broker.us");
        assertThat(validate(BootstrapJson.of(multiRegion)).getExitCode()).isZero();

        List<Cluster> broken = new ArrayList<>(resources.clusters());
        broken.add(Cluster.newBuilder().setName("broken").setType(Cluster.DiscoveryType.STATIC)
                .setConnectTimeout(Duration.newBuilder().setSeconds(-1)).build());
        Container.ExecResult result = validate(BootstrapJson.of(new EnvoyResources(resources.listeners(), List.of(), broken)));
        assertThat(result.getExitCode()).isNotZero();
    }

    private static Container.ExecResult validate(String json) throws Exception {
        envoy.copyFileToContainer(Transferable.of(json), "/tmp/validate.json");
        return envoy.execInContainer("envoy", "--mode", "validate", "-c", "/tmp/validate.json", "--log-level", "critical");
    }

    private static int status(String host) throws Exception {
        return status(host, "/");
    }

    private static int status(String host, String path) throws Exception {
        return request(host, path).statusCode();
    }

    private static String body(String host) throws Exception {
        return request(host, "/").body();
    }

    private static HttpResponse<String> request(String host, String path) throws Exception {
        URI uri = URI.create("http://" + envoy.getHost() + ":" + envoy.getMappedPort(10000) + path);
        return http.send(HttpRequest.newBuilder(uri).header("Host", host).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void eventually(Check check) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (check.ok()) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Condition not met within 30 seconds; envoy: " + envoyState(), last);
    }

    private static String envoyState() {
        try {
            URI admin = URI.create("http://" + envoy.getHost() + ":" + envoy.getMappedPort(9901));
            String listeners = http.send(HttpRequest.newBuilder(admin.resolve("/listeners")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            String clusters = http.send(HttpRequest.newBuilder(admin.resolve("/clusters")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            HttpResponse<String> probe = request("t499.test", "/");
            return "listeners=" + listeners.strip() + " clusterLines=" + clusters.lines().count()
                    + " probe=" + probe.statusCode() + " " + probe.headers().map() + " " + probe.body()
                    + " logs=" + envoy.getLogs().lines().limit(20).toList();
        } catch (Exception e) {
            return "unavailable: " + e;
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
