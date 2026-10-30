package dev.triedge.sidecar.config;

import java.time.Duration;
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
        @Nullable String etcdUrl,
        @DefaultValue("/triedge/db") String leaderNamespace,
        @DefaultValue("PT1S") Duration leaderCacheTtl) {
}
