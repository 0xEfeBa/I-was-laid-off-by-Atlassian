package dev.triedge.sidecar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import dev.triedge.sidecar.TestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class JwtValidatorTest {

    private final TestTokens tokens = new TestTokens();
    private final JwksProvider jwks =
            new JwksProvider(RestClient.create(), tokens.jwksUrl(), Duration.ofSeconds(30), Clock.systemUTC());
    private final JwtValidator validator = new JwtValidator(jwks, TestTokens.ISSUER, Clock.systemUTC());

    @AfterEach
    void close() {
        tokens.close();
    }

    @Test
    void acceptsValidTokenAndReadsClaims() {
        UUID id = UUID.randomUUID();

        assertThat(validator.validate(tokens.token(id, "us"))).hasValueSatisfying(user -> {
            assertThat(user.id()).isEqualTo(id);
            assertThat(user.homeRegion()).isEqualTo("us");
            assertThat(user.kind()).isEqualTo(AuthenticatedUser.Kind.SESSION);
            assertThat(user.scopes()).containsAll(AuthenticatedUser.ALL_SCOPES);
        });
    }

    @Test
    void rejectsExpiredTokensAndForeignIssuers() {
        UUID id = UUID.randomUUID();

        assertThat(validator.validate(tokens.token(id, "eu",
                claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(60)))))).isEmpty();
        assertThat(validator.validate(tokens.token(id, "eu", claims -> claims.issuer("someone-else")))).isEmpty();
        assertThat(validator.validate(tokens.token(id, "eu", claims -> claims.claim("role", null)))).isEmpty();
        assertThat(validator.validate(tokens.token(id, "eu", claims -> claims.expirationTime(null)))).isEmpty();
    }

    @Test
    void rejectsForgedUnsignedAndGarbageTokens() throws Exception {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).keyID("k1").algorithm(JWSAlgorithm.ES256).generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(TestTokens.ISSUER).subject(UUID.randomUUID().toString())
                .claim("username", "x").claim("role", "ADMIN").claim("home_region", "eu")
                .expirationTime(Date.from(Instant.now().plusSeconds(60))).build();

        assertThat(validator.validate(TestTokens.sign(attacker, claims))).isEmpty();
        assertThat(validator.validate(new PlainJWT(claims).serialize())).isEmpty();
        assertThat(validator.validate("not.a.jwt")).isEmpty();
        assertThat(validator.validate("")).isEmpty();
    }

    @Test
    void unknownKeyTriggersAtMostOneRefreshPerInterval() throws Exception {
        ECKey other = new ECKeyGenerator(Curve.P_256).keyID("unknown").algorithm(JWSAlgorithm.ES256).generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(TestTokens.ISSUER).subject(UUID.randomUUID().toString())
                .expirationTime(Date.from(Instant.now().plusSeconds(60))).build();

        validator.validate(TestTokens.sign(other, claims));
        validator.validate(TestTokens.sign(other, claims));
        validator.validate(TestTokens.sign(other, claims));

        assertThat(tokens.fetches()).isEqualTo(1);
    }

    @Test
    void keepsLastKnownKeysWhenTheKeyServiceIsDown() {
        String token = tokens.token(UUID.randomUUID(), "eu");
        assertThat(validator.validate(token)).isPresent();

        JwksProvider broken = new JwksProvider(RestClient.create(), "http://127.0.0.1:1/jwks", Duration.ZERO,
                Clock.systemUTC());
        broken.refreshIfDue();

        assertThat(new JwtValidator(broken, TestTokens.ISSUER, Clock.systemUTC()).validate(token)).isEmpty();
        assertThat(validator.validate(token)).isPresent();
    }
}
