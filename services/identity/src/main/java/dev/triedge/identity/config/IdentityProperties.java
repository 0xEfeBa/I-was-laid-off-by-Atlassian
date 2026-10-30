package dev.triedge.identity.config;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("triedge.identity")
public record IdentityProperties(
        String issuer,
        String etcdUrl,
        @DefaultValue("PT2M") Duration accessTokenTtl,
        @DefaultValue("P30D") Duration refreshTokenTtl,
        @Nullable String signingJwk,
        @DefaultValue List<String> previousPublicJwks,
        @DefaultValue("false") boolean allowEphemeralKey,
        @DefaultValue("true") boolean secureCookies,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("65536") int argon2MemoryKib,
        @DefaultValue("3") int argon2Iterations) {
}
