package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.triedge.identity.TestProperties;
import dev.triedge.identity.data.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private final KeyRing keyRing = new KeyRing(TestProperties.identity());
    private final TokenIssuer issuer =
            new TokenIssuer(keyRing, Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.identity());

    @Test
    void issuesShortLivedEs256TokenWithUserClaims() throws Exception {
        User user = new User(UUID.randomUUID(), "efe", "efe@example.com", "hash", "ADMIN", "eu", null, 3);

        SignedJWT jwt = SignedJWT.parse(issuer.issue(user));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(keyRing.signingKey().getKeyID());
        assertThat(jwt.verify(new ECDSAVerifier(keyRing.signingKey().toECPublicKey()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("tri-edge-test");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("username")).isEqualTo("efe");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("ADMIN");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("home_region")).isEqualTo("eu");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(120));
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
    }
}
