package dev.triedge.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class RestClientsTest {

    @Test
    void readsAResponseWithinTheTimeout() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RestClient client = RestClients.create(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
            assertThat(client.get().uri("/ping").retrieve().body(String.class)).isEqualTo("pong");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void givesUpOnAServerThatNeverAnswers() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> accepted = new ArrayList<>();
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException closed) {
                    accepted.forEach(socket -> {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    });
                }
            });
            RestClient client = RestClients.create(RestClient.builder(),
                    "http://127.0.0.1:" + silent.getLocalPort(), Duration.ofMillis(300));
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.get().uri("/").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThatThrownBy(() -> RestClients.create(RestClient.builder(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
