package dev.triedge.sidecar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JwksProviderTest {

    private static final String URL = "http://identity/.well-known/jwks.json";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final JwksProvider provider = new JwksProvider(builder.build(), URL, Duration.ofMinutes(5), Clock.systemUTC());

    @Test
    void keysAreLoadedInTheBackgroundUntilTheFirstSetArrives() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));

        provider.keepFresh();
        provider.keepFresh();
        provider.keepFresh();

        assertThat(provider.key("k1")).isPresent();
        server.verify();
    }

    @Test
    void unknownKeyIdsDoNotRefetchBeforeTheMinimumInterval() {
        server.expect(twice(), requestTo(URL)).andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));

        assertThat(provider.key("missing")).isEmpty();
        assertThat(provider.key("missing")).isEmpty();
        provider.keepFresh();

        server.verify();
    }
}
