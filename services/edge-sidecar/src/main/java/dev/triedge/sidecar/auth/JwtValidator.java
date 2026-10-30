package dev.triedge.sidecar.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public class JwtValidator {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final JwksProvider keys;
    private final String issuer;
    private final Clock clock;

    public JwtValidator(JwksProvider keys, String issuer, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.clock = clock;
    }

    public Optional<AuthenticatedUser> validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm()) || jwt.getHeader().getKeyID() == null) {
                return Optional.empty();
            }
            Optional<ECKey> key = keys.key(jwt.getHeader().getKeyID());
            if (key.isEmpty() || !jwt.verify(new ECDSAVerifier(key.get().toECPublicKey()))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            Instant now = clock.instant();
            if (!issuer.equals(claims.getIssuer()) || expiration == null
                    || !expiration.toInstant().plus(CLOCK_SKEW).isAfter(now)) {
                return Optional.empty();
            }
            String subject = claims.getSubject();
            String username = claims.getStringClaim("username");
            String role = claims.getStringClaim("role");
            String homeRegion = claims.getStringClaim("home_region");
            if (subject == null || username == null || role == null || homeRegion == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(UUID.fromString(subject), username, role, homeRegion,
                    AuthenticatedUser.Kind.SESSION, AuthenticatedUser.ALL_SCOPES));
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
