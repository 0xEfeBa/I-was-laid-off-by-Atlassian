package dev.triedge.sidecar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialsTest {

    @Test
    void bearerTokensAreSessionsUnlessTheyArePersonalAccessTokens() {
        assertThat(Credentials.parse("Bearer eyJhbGc")).contains(new Credentials.Session("eyJhbGc"));
        assertThat(Credentials.parse("bearer tep_abc")).contains(new Credentials.AccessToken("tep_abc"));
    }

    @Test
    void basicAuthCarriesTheAccessTokenAsPassword() {
        String header = "Basic " + Base64.getEncoder().encodeToString("efe:tep_secret".getBytes(StandardCharsets.UTF_8));

        assertThat(Credentials.parse(header)).contains(new Credentials.AccessToken("tep_secret"));
    }

    @Test
    void ignoresMissingOrMalformedHeaders() {
        assertThat(Credentials.parse(null)).isEmpty();
        assertThat(Credentials.parse("Bearer ")).isEmpty();
        assertThat(Credentials.parse("Basic !!!")).isEmpty();
        assertThat(Credentials.parse("Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8)))).isEmpty();
        assertThat(Credentials.parse("Basic " + Base64.getEncoder().encodeToString("user:".getBytes(StandardCharsets.UTF_8)))).isEmpty();
        assertThat(Credentials.parse("Digest abc")).isEmpty();
    }
}
