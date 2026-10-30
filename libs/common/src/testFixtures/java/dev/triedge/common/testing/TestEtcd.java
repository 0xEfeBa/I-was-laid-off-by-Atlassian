package dev.triedge.common.testing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public final class TestEtcd {

    private static final GenericContainer<?> ETCD = new GenericContainer<>("quay.io/coreos/etcd:v3.6.15")
            .withCommand("etcd",
                    "--listen-client-urls=http://0.0.0.0:2379",
                    "--advertise-client-urls=http://localhost:2379")
            .withExposedPorts(2379)
            .waitingFor(Wait.forHttp("/health").forPort(2379));

    private TestEtcd() {
    }

    public static synchronized String url() {
        if (!ETCD.isRunning()) {
            ETCD.start();
        }
        return "http://" + ETCD.getHost() + ":" + ETCD.getMappedPort(2379);
    }

    public static RestClient client() {
        return RestClient.create(url());
    }

    public static void put(String key, String value) {
        client().post()
                .uri("/v3/kv/put")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("key", encode(key), "value", encode(value)))
                .retrieve()
                .toBodilessEntity();
    }

    public static void deletePrefix(String prefix) {
        byte[] end = prefix.getBytes(StandardCharsets.UTF_8);
        end[end.length - 1]++;
        client().post()
                .uri("/v3/kv/deleterange")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("key", encode(prefix), "range_end", Base64.getEncoder().encodeToString(end)))
                .retrieve()
                .toBodilessEntity();
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
