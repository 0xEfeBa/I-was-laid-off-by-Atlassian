package dev.triedge.broker.service;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

public class ControlPlaneClient {

    public record Outcome(boolean valid, List<String> errors) {
    }

    private final List<RestClient> replicas;

    public ControlPlaneClient(List<RestClient> replicas) {
        if (replicas.isEmpty()) {
            throw new IllegalArgumentException("At least one control plane URL is required");
        }
        this.replicas = List.copyOf(replicas);
    }

    public Outcome validate(JsonNode spec) {
        RestClientException last = null;
        for (RestClient replica : replicas) {
            try {
                return validate(replica, spec);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
            }
        }
        throw last;
    }

    private static Outcome validate(RestClient replica, JsonNode spec) {
        Outcome outcome = replica.post()
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
