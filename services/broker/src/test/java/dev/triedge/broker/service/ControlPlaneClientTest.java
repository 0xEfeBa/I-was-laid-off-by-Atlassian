package dev.triedge.broker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

class ControlPlaneClientTest {

    private static final String RESULT = "{\"valid\":false,\"errors\":[\"/hosts: taken\"]}";

    private final JsonMapper json = JsonMapper.builder().build();
    private final RestClient.Builder first = RestClient.builder().baseUrl("http://control-plane");
    private final RestClient.Builder second = RestClient.builder().baseUrl("http://control-plane-2");
    private final MockRestServiceServer firstServer = MockRestServiceServer.bindTo(first).build();
    private final MockRestServiceServer secondServer = MockRestServiceServer.bindTo(second).build();
    private final ControlPlaneClient client = new ControlPlaneClient(List.of(first.build(), second.build()));

    @Test
    void sendsTheSpecAndReadsTheOutcome() {
        firstServer.expect(requestTo("http://control-plane/internal/v1/validate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"name\":\"shop\"}"))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

        ControlPlaneClient.Outcome outcome = client.validate(json.readTree("{\"name\":\"shop\"}"));

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.errors()).containsExactly("/hosts: taken");
        firstServer.verify();
        secondServer.verify();
    }

    @Test
    void asksTheOtherReplicaWhenOneIsDown() {
        firstServer.expect(requestTo("http://control-plane/internal/v1/validate"))
                .andRespond(withException(new IOException("Connection refused")));
        secondServer.expect(requestTo("http://control-plane-2/internal/v1/validate"))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

        assertThat(client.validate(json.readTree("{}")).errors()).containsExactly("/hosts: taken");
        firstServer.verify();
        secondServer.verify();
    }

    @Test
    void doesNotAskAgainWhenTheRequestIsRefused() {
        firstServer.expect(requestTo("http://control-plane/internal/v1/validate")).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.validate(json.readTree("{}"))).isInstanceOf(HttpClientErrorException.class);
        secondServer.verify();
    }

    @Test
    void failsWhenEveryReplicaFails() {
        firstServer.expect(requestTo("http://control-plane/internal/v1/validate")).andRespond(withServerError());
        secondServer.expect(requestTo("http://control-plane-2/internal/v1/validate")).andRespond(withServerError());

        assertThatThrownBy(() -> client.validate(json.readTree("{}"))).isInstanceOf(RestClientException.class);
        firstServer.verify();
        secondServer.verify();
    }
}
