package dev.triedge.broker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

class ControlPlaneClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://control-plane");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ControlPlaneClient client = new ControlPlaneClient(builder.build());

    @Test
    void sendsTheSpecAndReadsTheOutcome() {
        server.expect(requestTo("http://control-plane/internal/v1/validate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"name\":\"shop\"}"))
                .andRespond(withSuccess("{\"valid\":false,\"errors\":[\"/hosts: taken\"]}", MediaType.APPLICATION_JSON));

        ControlPlaneClient.Outcome outcome = client.validate(JsonMapper.builder().build().readTree("{\"name\":\"shop\"}"));

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.errors()).containsExactly("/hosts: taken");
        server.verify();
    }

    @Test
    void failuresAreThrown() {
        server.expect(requestTo("http://control-plane/internal/v1/validate")).andRespond(withServerError());

        assertThatThrownBy(() -> client.validate(JsonMapper.builder().build().readTree("{}")))
                .isInstanceOf(RestClientException.class);
    }
}
