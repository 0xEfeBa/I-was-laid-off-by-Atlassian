package dev.triedge.common.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JobQueue {

    private static final Set<String> SCHEMAS = Set.of("platform");
    private static final long MAX_BACKOFF_SECONDS = Duration.ofMinutes(5).toSeconds();
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;
    private final double lockSeconds;
    private final String enqueueSql;
    private final String expireSql;
    private final String claimSql;
    private final String completeSql;
    private final String failSql;
    private final String extendSql;

    public JobQueue(JdbcClient jdbc, String schema, Duration lockDuration) {
        if (!SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException("Unknown job queue schema: " + schema);
        }
        if (lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("Lock duration must be positive: " + lockDuration);
        }
        this.jdbc = jdbc;
        this.lockSeconds = lockDuration.toMillis() / 1000.0;
        String table = schema + ".job_queue";
        this.enqueueSql = """
                INSERT INTO %s (queue, dedup_key, payload)
                VALUES (:queue, :dedupKey, CAST(:payload AS jsonb))
                ON CONFLICT (queue, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING
                """.formatted(table);
        this.expireSql = """
                UPDATE %s
                SET status = 'FAILED', locked_until = NULL, updated_at = now(),
                    last_error = coalesce(last_error, 'Lock expired on the final attempt')
                WHERE queue = :queue AND status = 'RUNNING'
                  AND locked_until < now() AND attempts >= max_attempts
                """.formatted(table);
        this.claimSql = """
                UPDATE %1$s
                SET status = 'RUNNING', attempts = attempts + 1,
                    locked_until = now() + make_interval(secs => :lockSeconds), updated_at = now()
                WHERE id = (
                    SELECT id FROM %1$s
                    WHERE queue = :queue
                      AND attempts < max_attempts
                      AND ((status = 'PENDING' AND run_after <= now())
                        OR (status = 'RUNNING' AND locked_until < now()))
                    ORDER BY run_after, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
                RETURNING id, queue, payload::text AS payload, attempts, max_attempts
                """.formatted(table);
        this.completeSql = """
                UPDATE %s
                SET status = 'DONE', locked_until = NULL, last_error = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts
                """.formatted(table);
        this.failSql = """
                UPDATE %s
                SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    run_after = now() + make_interval(secs => :backoffSeconds),
                    locked_until = NULL, last_error = :error, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts
                """.formatted(table);
        this.extendSql = """
                UPDATE %s
                SET locked_until = now() + make_interval(secs => :lockSeconds), updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts
                """.formatted(table);
    }

    public boolean enqueue(String queue, @Nullable String dedupKey, String payload) {
        return jdbc.sql(enqueueSql)
                .param("queue", queue)
                .param("dedupKey", dedupKey)
                .param("payload", payload)
                .update() == 1;
    }

    public Optional<Job> claim(String queue) {
        jdbc.sql(expireSql).param("queue", queue).update();
        return jdbc.sql(claimSql)
                .param("queue", queue)
                .param("lockSeconds", lockSeconds)
                .query(JobQueue::toJob)
                .optional();
    }

    public boolean complete(Job job) {
        return jdbc.sql(completeSql)
                .param("id", job.id())
                .param("attempts", job.attempts())
                .update() == 1;
    }

    public boolean fail(Job job, String error) {
        long backoffSeconds = Math.min(1L << Math.min(job.attempts(), 20), MAX_BACKOFF_SECONDS);
        String trimmed = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        return jdbc.sql(failSql)
                .param("id", job.id())
                .param("attempts", job.attempts())
                .param("backoffSeconds", (double) backoffSeconds)
                .param("error", trimmed)
                .update() == 1;
    }

    public boolean extendLock(Job job) {
        return jdbc.sql(extendSql)
                .param("id", job.id())
                .param("attempts", job.attempts())
                .param("lockSeconds", lockSeconds)
                .update() == 1;
    }

    private static Job toJob(ResultSet row, int rowNumber) throws SQLException {
        return new Job(
                row.getLong("id"),
                row.getString("queue"),
                row.getString("payload"),
                row.getInt("attempts"),
                row.getInt("max_attempts"));
    }
}
