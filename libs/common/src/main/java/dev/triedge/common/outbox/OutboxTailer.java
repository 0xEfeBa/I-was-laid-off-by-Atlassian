package dev.triedge.common.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

public class OutboxTailer {

    private static final Set<String> SCHEMAS = Set.of("identity", "platform");

    private final JdbcClient jdbc;
    private final String selectSql;
    private final int batchSize;

    public OutboxTailer(JdbcClient jdbc, String schema, int batchSize) {
        if (!SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException("Unknown outbox schema: " + schema);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive: " + batchSize);
        }
        this.jdbc = jdbc;
        this.batchSize = batchSize;
        this.selectSql = """
                SELECT id, txid::text::bigint AS txid, aggregate_type, aggregate_id,
                       event_type, payload::text AS payload, created_at
                FROM %s.outbox
                WHERE (txid, id) > (CAST(CAST(:txid AS text) AS xid8), :id)
                  AND txid < pg_snapshot_xmin(pg_current_snapshot())
                ORDER BY txid, id
                LIMIT :limit
                """.formatted(schema);
    }

    public List<OutboxEvent> poll(Checkpoint after) {
        return jdbc.sql(selectSql)
                .param("txid", after.txid())
                .param("id", after.id())
                .param("limit", batchSize)
                .query(OutboxTailer::toEvent)
                .list();
    }

    private static OutboxEvent toEvent(ResultSet row, int rowNumber) throws SQLException {
        return new OutboxEvent(
                row.getLong("id"),
                row.getLong("txid"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"),
                row.getString("event_type"),
                row.getString("payload"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
