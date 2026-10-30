package dev.triedge.sidecar.directory;

import dev.triedge.common.outbox.Checkpoint;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class InitialLoader {

    private final IdentityIndex index;

    public InitialLoader(IdentityIndex index) {
        this.index = index;
    }

    public Checkpoint load(DataSource dataSource) {
        TransactionTemplate snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        return snapshot.execute(status -> {
            Checkpoint checkpoint = jdbc.sql("""
                    SELECT txid::text::bigint AS txid, id FROM identity.outbox
                    WHERE txid < pg_snapshot_xmin(pg_current_snapshot())
                    ORDER BY txid DESC, id DESC
                    LIMIT 1
                    """)
                    .query((row, n) -> new Checkpoint(row.getLong("txid"), row.getLong("id")))
                    .optional()
                    .orElse(Checkpoint.START);
            jdbc.sql("""
                    SELECT id, username, role, home_region, banned_at IS NOT NULL AS banned, version
                    FROM identity.users
                    """)
                    .query((row, n) -> new IdentityIndex.UserEntry(
                            row.getObject("id", UUID.class),
                            row.getString("username"),
                            row.getString("role"),
                            row.getString("home_region"),
                            row.getBoolean("banned"),
                            row.getLong("version")))
                    .list()
                    .forEach(index::putUser);
            jdbc.sql("""
                    SELECT id, user_id, token_hash, scopes, expires_at
                    FROM identity.personal_access_tokens
                    WHERE revoked_at IS NULL
                    """)
                    .query((row, n) -> {
                        OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
                        String[] scopes = (String[]) row.getArray("scopes").getArray();
                        index.putPat(HexFormat.of().formatHex(row.getBytes("token_hash")), new IdentityIndex.PatEntry(
                                row.getObject("id", UUID.class),
                                row.getObject("user_id", UUID.class),
                                Arrays.asList(scopes),
                                expiresAt == null ? null : expiresAt.toInstant()));
                        return true;
                    })
                    .list();
            return checkpoint;
        });
    }
}
