package dev.triedge.sidecar.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("triedge.sidecar")
public record SidecarProperties(
        @DefaultValue("9001") int grpcPort,
        String issuer,
        String jwksUrl,
        @DefaultValue("PT30S") Duration jwksMinRefresh,
        @DefaultValue("PT0.2S") Duration pollInterval,
        @DefaultValue("PT0.05S") Duration hookTimeout,
        @Nullable String hookSourceAddress,
        @Nullable String etcdUrl,
        @DefaultValue("/triedge/db") String leaderNamespace,
        @DefaultValue("PT1S") Duration leaderCacheTtl,
        @DefaultValue("PT2S") Duration jwksTimeout,
        @DefaultValue("PT2S") Duration etcdTimeout,
        @DefaultValue("128") int grpcThreads,
        @Nullable String etcdUsername,
        @Nullable String etcdPassword,
        @DefaultValue("0.0.0.0") String grpcAddress) {

    public SidecarProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("triedge.sidecar.issuer must be set");
        }
        requireHttpUrl("triedge.sidecar.jwks-url", jwksUrl);
        if (grpcThreads < 1) {
            throw new IllegalArgumentException("triedge.sidecar.grpc-threads must be positive");
        }
    }

    private static void requireHttpUrl(String name, @Nullable String value) {
        URI uri = null;
        if (value != null) {
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                uri = null;
            }
        }
        if (uri == null || uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException(name + " must be an http or https URL, got '" + value + "'");
        }
    }
}
