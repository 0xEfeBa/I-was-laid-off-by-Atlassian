package dev.triedge.common.outbox;

import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OutboxWriter {

    private static final Set<String> SCHEMAS = Set.of("identity", "platform");

    private final JdbcClient jdbc;
    private final String insertSql;

    public OutboxWriter(JdbcClient jdbc, String schema) {
        if (!SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException("Unknown outbox schema: " + schema);
        }
        this.jdbc = jdbc;
        this.insertSql = """
                INSERT INTO %s.outbox (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                """.formatted(schema);
    }

    public void append(String aggregateType, String aggregateId, String eventType, String payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be written inside the transaction that changes the data");
        }
        jdbc.sql(insertSql)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payload)
                .update();
    }
}
