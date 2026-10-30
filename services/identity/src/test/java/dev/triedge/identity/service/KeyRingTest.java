package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import dev.triedge.identity.TestProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeyRingTest {

    @Test
    void generatesTemporaryKeyOnlyWhenAllowed() {
        KeyRing ring = new KeyRing(TestProperties.identity(null, true));

        assertThat(ring.signingKey().isPrivate()).isTrue();
        assertThat(ring.signingKey().getKeyID()).isNotBlank();
        assertThatThrownBy(() -> new KeyRing(TestProperties.identity(null, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesConfiguredKey() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("configured").algorithm(JWSAlgorithm.ES256)
                .keyUse(KeyUse.SIGNATURE).generate();

        KeyRing ring = new KeyRing(TestProperties.identity(key.toJSONString(), false));

        assertThat(ring.signingKey().getKeyID()).isEqualTo("configured");
    }

    @Test
    void refusesPublicOnlyOrWrongCurveKeys() throws Exception {
        ECKey publicOnly = new ECKeyGenerator(Curve.P_256).keyID("public").generate().toPublicJWK();
        ECKey wrongCurve = new ECKeyGenerator(Curve.P_384).keyID("p384").generate();

        assertThatThrownBy(() -> new KeyRing(TestProperties.identity(publicOnly.toJSONString(), false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyRing(TestProperties.identity(wrongCurve.toJSONString(), false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyRing(TestProperties.identity("not json", false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesOnlyPublicKeys() {
        Map<String, Object> jwks = new KeyRing(TestProperties.identity()).publicJwks();

        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst()).containsEntry("kty", "EC").doesNotContainKey("d");
    }
}
