package dev.triedge.sidecar.directory;

import dev.triedge.common.outbox.OutboxEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class IdentityEventApplier {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventApplier.class);

    private final IdentityIndex index;
    private final JsonMapper json;

    public IdentityEventApplier(IdentityIndex index, JsonMapper json) {
        this.index = index;
        this.json = json;
    }

    public void apply(OutboxEvent event) {
        JsonNode payload = json.readTree(event.payload());
        switch (event.eventType()) {
            case "USER_CREATED" -> index.putUser(new IdentityIndex.UserEntry(
                    UUID.fromString(payload.get("id").asString()),
                    payload.get("username").asString(),
                    payload.get("role").asString(),
                    payload.get("homeRegion").asString(),
                    payload.get("banned").asBoolean(),
                    payload.get("version").asLong()));
            case "USER_BANNED" -> index.banUser(
                    UUID.fromString(payload.get("id").asString()),
                    payload.get("version").asLong());
            case "PAT_CREATED" -> {
                List<String> scopes = new ArrayList<>();
                payload.get("scopes").forEach(scope -> scopes.add(scope.asString()));
                JsonNode expiresAt = payload.get("expiresAt");
                index.putPat(payload.get("tokenHash").asString(), new IdentityIndex.PatEntry(
                        UUID.fromString(payload.get("id").asString()),
                        UUID.fromString(payload.get("userId").asString()),
                        List.copyOf(scopes),
                        expiresAt == null || expiresAt.isNull() ? null : Instant.parse(expiresAt.asString())));
            }
            case "PAT_REVOKED" -> index.removePat(payload.get("tokenHash").asString());
            default -> log.debug("Ignoring identity event {}", event.eventType());
        }
    }
}
