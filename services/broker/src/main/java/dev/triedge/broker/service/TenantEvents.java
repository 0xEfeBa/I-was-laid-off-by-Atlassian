package dev.triedge.broker.service;

import dev.triedge.common.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TenantEvents {

    private final OutboxWriter outbox;
    private final JsonMapper json;

    public TenantEvents(OutboxWriter outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void versionActivated(UUID tenantId, String name, int version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("name", name);
        payload.put("version", version);
        outbox.append("tenant", tenantId.toString(), "TENANT_VERSION_ACTIVATED", json.writeValueAsString(payload));
    }

    public void deleted(UUID tenantId, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("name", name);
        outbox.append("tenant", tenantId.toString(), "TENANT_DELETED", json.writeValueAsString(payload));
    }
}
