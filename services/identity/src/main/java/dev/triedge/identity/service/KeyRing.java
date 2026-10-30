package dev.triedge.identity.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import dev.triedge.identity.config.IdentityProperties;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KeyRing {

    private static final Logger log = LoggerFactory.getLogger(KeyRing.class);

    private final ECKey signingKey;
    private final JWKSet publicKeys;

    public KeyRing(IdentityProperties properties) {
        this.signingKey = loadSigningKey(properties);
        List<JWK> keys = new ArrayList<>();
        keys.add(signingKey.toPublicJWK());
        for (String previous : properties.previousPublicJwks()) {
            keys.add(parseEcKey(previous).toPublicJWK());
        }
        this.publicKeys = new JWKSet(keys);
    }

    public ECKey signingKey() {
        return signingKey;
    }

    public Map<String, Object> publicJwks() {
        return publicKeys.toJSONObject(true);
    }

    private static ECKey loadSigningKey(IdentityProperties properties) {
        String configured = properties.signingJwk();
        if (configured != null && !configured.isBlank()) {
            ECKey key = parseEcKey(configured);
            if (!key.isPrivate()) {
                throw new IllegalStateException("The signing JWK must contain the private key");
            }
            return key;
        }
        if (!properties.allowEphemeralKey()) {
            throw new IllegalStateException("triedge.identity.signing-jwk is not set");
        }
        log.warn("No signing key configured; generating a temporary one. Tokens will not survive a restart.");
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .keyIDFromThumbprint(true)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a signing key", e);
        }
    }

    private static ECKey parseEcKey(String json) {
        try {
            ECKey key = ECKey.parse(json);
            if (!Curve.P_256.equals(key.getCurve())) {
                throw new IllegalStateException("Signing keys must use the P-256 curve");
            }
            return key;
        } catch (ParseException e) {
            throw new IllegalStateException("Invalid JWK", e);
        }
    }
}
