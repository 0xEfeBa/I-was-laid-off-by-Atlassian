package dev.triedge.common.http;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

public final class RestClients {

    private RestClients() {
    }

    public static RestClient create(RestClient.Builder builder, Duration timeout) {
        return withTimeout(builder, timeout).build();
    }

    public static RestClient create(RestClient.Builder builder, String baseUrl, Duration timeout) {
        return withTimeout(builder, timeout).baseUrl(baseUrl).build();
    }

    private static RestClient.Builder withTimeout(RestClient.Builder builder, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive: " + timeout);
        }
        return builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults().withTimeouts(timeout, timeout)));
    }
}
