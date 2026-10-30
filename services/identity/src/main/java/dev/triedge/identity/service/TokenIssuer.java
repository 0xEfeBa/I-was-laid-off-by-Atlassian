package dev.triedge.identity.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.triedge.identity.config.IdentityProperties;
import dev.triedge.identity.data.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {

    private final KeyRing keyRing;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;

    public TokenIssuer(KeyRing keyRing, Clock clock, IdentityProperties properties) {
        this.keyRing = keyRing;
        this.clock = clock;
        this.issuer = properties.issuer();
        this.ttl = properties.accessTokenTtl();
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user.id().toString())
                .claim("username", user.username())
                .claim("role", user.role())
                .claim("home_region", user.homeRegion())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keyRing.signingKey().getKeyID())
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new ECDSASigner(keyRing.signingKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the access token", e);
        }
        return jwt.serialize();
    }
}
