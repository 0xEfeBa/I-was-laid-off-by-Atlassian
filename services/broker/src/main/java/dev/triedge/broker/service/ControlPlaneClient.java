package dev.triedge.broker.service;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class ControlPlaneClient {

    public record Outcome(boolean valid, List<String> errors) {
    }

    private final RestClient rest;

    public ControlPlaneClient(RestClient rest) {
        this.rest = rest;
    }

    public Outcome validate(JsonNode spec) {
        Outcome outcome = rest.post()
                .uri("/internal/v1/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(spec)
                .retrieve()
                .body(Outcome.class);
        if (outcome == null) {
            throw new IllegalStateException("The control plane returned an empty validation result");
        }
        return outcome;
    }
}
