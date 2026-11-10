package dev.triedge.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import dev.triedge.controlplane.xds.XdsServer;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnvoyFailoverIT {

    private static final String HOST = "host.testcontainers.internal";
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final List<ActiveTenant> tenants = new CopyOnWriteArrayList<>();
    private static final ConfigRenderer renderer = new ConfigRenderer("eu", 10000, HOST, 9001);

    private static Replica first;
    private static Replica second;
    private static GenericContainer<?> envoy;

    private record Replica(int port, SimpleCache<String> cache, SnapshotPublisher publisher, AckTracker tracker,
            XdsServer xds) {

        static Replica start(int port) {
            SimpleCache<String> cache = new SimpleCache<>(node -> SnapshotPublisher.GROUP);
            SnapshotPublisher publisher = new SnapshotPublisher(cache, renderer, () -> tenants, Duration.ofMillis(50));
            AckTracker tracker = new AckTracker(publisher::version);
            return new Replica(port, cache, publisher, tracker, started(port, cache, tracker, publisher));
        }

        Replica restart() {
            return new Replica(port, cache, publisher, tracker, started(port, cache, tracker, publisher));
        }

        boolean connected() {
            return tracker.connectedNodes() == 1;
        }

        private static XdsServer started(int port, SimpleCache<String> cache, AckTracker tracker,
                SnapshotPublisher publisher) {
            XdsServer xds = new XdsServer(port, cache, tracker, publisher);
            xds.start();
            return xds;
        }
    }

    @BeforeAll
    static void start() throws IOException {
        tenants.add(TestTenants.tenant("t0", "t0.test", HOST, 80));
        first = Replica.start(freePort());
        second = Replica.start(freePort());
        Testcontainers.exposeHostPorts(first.port(), second.port());

        String bootstrap = Files.readString(Path.of(System.getProperty("triedge.envoy-bootstrap")));
        bootstrap = replace(bootstrap, "address: 127.0.0.1, port_value: 9901", "address: 0.0.0.0, port_value: 9901");
        bootstrap = replace(bootstrap, "address: control-plane, port_value: 18000",
                "address: " + HOST + ", port_value: " + first.port());
        bootstrap = replace(bootstrap, "address: control-plane-2, port_value: 18001",
                "address: " + HOST + ", port_value: " + second.port());

        envoy = new GenericContainer<>("envoyproxy/envoy:v1.39.4")
                .withCopyToContainer(Transferable.of(bootstrap), "/etc/envoy/envoy.yaml")
                .withCommand("-c", "/etc/envoy/envoy.yaml", "--log-level", "warn")
                .withExposedPorts(9901)
                .waitingFor(Wait.forHttp("/ready").forPort(9901));
        envoy.start();
    }

    @AfterAll
    static void stop() {
        if (envoy != null) {
            envoy.stop();
        }
        for (Replica replica : List.of(first, second)) {
            replica.xds().stop();
            replica.publisher().close();
        }
    }

    @Test
    @Order(1)
    void envoyMovesToTheOtherReplicaWhenOneStops() {
        eventually(() -> clusters().contains("tenant.t0.main::"));
        eventually(() -> first.connected() || second.connected());
        Replica stopped = first.connected() ? first : second;
        Replica other = stopped == first ? second : first;

        stopped.xds().stop();
        publish("t1");

        eventually(() -> other.connected() && clusters().contains("tenant.t1.main::"));
        if (stopped == first) {
            first = first.restart();
        } else {
            second = second.restart();
        }
    }

    @Test
    @Order(2)
    void envoyLeavesAReplicaThatTurnsUnhealthy() throws Exception {
        eventually(() -> healthyHosts() == 2);
        Replica unhealthy = first.connected() ? first : second;
        Replica other = unhealthy == first ? second : first;

        unhealthy.xds().serving(false);
        publish("t2");

        eventually(() -> other.connected() && !unhealthy.connected() && clusters().contains("tenant.t2.main::"));
        eventually(() -> healthyHosts() == 1);

        unhealthy.xds().serving(true);
        eventually(() -> healthyHosts() == 2);
    }

    private static void publish(String name) {
        tenants.add(TestTenants.tenant(name, name + ".test", HOST, 80));
        first.publisher().publishNow();
        second.publisher().publishNow();
    }

    private static long healthyHosts() throws Exception {
        return clusters().lines()
                .filter(line -> line.startsWith("xds::") && line.contains("::health_flags::healthy"))
                .count();
    }

    private static List<String> xdsHosts() {
        try {
            return clusters().lines().filter(line -> line.startsWith("xds::") && line.contains("health_flags")).toList();
        } catch (Exception e) {
            return List.of(e.toString());
        }
    }

    private static String clusters() throws Exception {
        URI admin = URI.create("http://" + envoy.getHost() + ":" + envoy.getMappedPort(9901) + "/clusters");
        return http.send(HttpRequest.newBuilder(admin).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String replace(String text, String from, String to) {
        assertThat(text).as("bootstrap contains %s", from).contains(from);
        return text.replace(from, to);
    }

    private static void eventually(Check check) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Condition not met within 30 seconds; xds hosts: " + xdsHosts()
                + " envoy logs: " + envoy.getLogs().lines().limit(30).toList(), last);
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
