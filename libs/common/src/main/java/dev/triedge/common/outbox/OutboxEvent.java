package dev.triedge.common.outbox;

import java.time.Instant;

public record OutboxEvent(
        long id,
        long txid,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        Instant createdAt) {
}
