package dev.triedge.identity.service;

import dev.triedge.common.outbox.OutboxWriter;
import dev.triedge.identity.data.PersonalAccessToken;
import dev.triedge.identity.data.User;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IdentityEvents {

    private final OutboxWriter outbox;
    private final JsonMapper json;

    public IdentityEvents(OutboxWriter outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void userCreated(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.id().toString());
        payload.put("username", user.username());
        payload.put("role", user.role());
        payload.put("homeRegion", user.homeRegion());
        payload.put("banned", false);
        payload.put("version", user.version());
        append("user", user.id().toString(), "USER_CREATED", payload);
    }

    public void userBanned(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.id().toString());
        payload.put("version", user.version());
        append("user", user.id().toString(), "USER_BANNED", payload);
    }

    public void patCreated(PersonalAccessToken pat) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", pat.id().toString());
        payload.put("userId", pat.userId().toString());
        payload.put("tokenHash", SecretTokens.hex(pat.tokenHash()));
        payload.put("scopes", pat.scopes());
        payload.put("expiresAt", pat.expiresAt() == null ? null : pat.expiresAt().toString());
        append("pat", pat.id().toString(), "PAT_CREATED", payload);
    }

    public void patRevoked(PersonalAccessToken pat) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", pat.id().toString());
        payload.put("userId", pat.userId().toString());
        payload.put("tokenHash", SecretTokens.hex(pat.tokenHash()));
        append("pat", pat.id().toString(), "PAT_REVOKED", payload);
    }

    private void append(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        outbox.append(aggregateType, aggregateId, eventType, json.writeValueAsString(payload));
    }
}
