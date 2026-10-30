package dev.triedge.sidecar;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TestTokens implements AutoCloseable {

    public static final String ISSUER = "tri-edge-test";

    private final ECKey key;
    private final HttpServer server;
    private final AtomicInteger fetches = new AtomicInteger();

    public TestTokens() {
        try {
            this.key = new ECKeyGenerator(Curve.P_256).keyID("k1").algorithm(JWSAlgorithm.ES256).generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] body = new JWKSet(key.toPublicJWK()).toString(true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String jwksUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    public int fetches() {
        return fetches.get();
    }

    public String token(UUID userId, String homeRegion) {
        return token(userId, homeRegion, claims -> { });
    }

    public String token(UUID userId, String homeRegion, Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("username", "user-" + userId.toString().substring(0, 4))
                .claim("role", "USER")
                .claim("home_region", homeRegion)
                .expirationTime(Date.from(Instant.now().plusSeconds(120)));
        customizer.accept(claims);
        return sign(key, claims.build());
    }

    public static String sign(ECKey signingKey, JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.getKeyID()).build(),
                claims);
        try {
            jwt.sign(new ECDSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
