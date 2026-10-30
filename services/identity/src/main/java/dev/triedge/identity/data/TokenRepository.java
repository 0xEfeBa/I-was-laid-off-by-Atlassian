package dev.triedge.identity.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TokenRepository {

    private static final String PAT_COLUMNS =
            "id, user_id, name, token_hash, scopes, expires_at, last_used_at, revoked_at, created_at";
    private static final String REFRESH_COLUMNS = "id, user_id, family_id, expires_at, used_at, revoked_at";

    private final JdbcClient jdbc;

    public TokenRepository(JdbcClient homeJdbc) {
        this.jdbc = homeJdbc;
    }

    public void insertPat(PersonalAccessToken pat) {
        jdbc.sql("""
                INSERT INTO identity.personal_access_tokens (id, user_id, name, token_hash, scopes, expires_at)
                VALUES (:id, :userId, :name, :tokenHash, :scopes, :expiresAt)
                """)
                .param("id", pat.id())
                .param("userId", pat.userId())
                .param("name", pat.name())
                .param("tokenHash", pat.tokenHash())
                .param("scopes", pat.scopes().toArray(String[]::new))
                .param("expiresAt", toOffset(pat.expiresAt()))
                .update();
    }

    public List<PersonalAccessToken> activePats(UUID userId) {
        return jdbc.sql("""
                SELECT %s FROM identity.personal_access_tokens
                WHERE user_id = :userId AND revoked_at IS NULL
                ORDER BY created_at
                """.formatted(PAT_COLUMNS))
                .param("userId", userId)
                .query(TokenRepository::toPat)
                .list();
    }

    public Optional<PersonalAccessToken> revokePat(UUID userId, UUID patId) {
        return jdbc.sql("""
                UPDATE identity.personal_access_tokens SET revoked_at = now()
                WHERE id = :id AND user_id = :userId AND revoked_at IS NULL
                RETURNING %s
                """.formatted(PAT_COLUMNS))
                .param("id", patId)
                .param("userId", userId)
                .query(TokenRepository::toPat)
                .optional();
    }

    public List<PersonalAccessToken> revokeAllPats(UUID userId) {
        return jdbc.sql("""
                UPDATE identity.personal_access_tokens SET revoked_at = now()
                WHERE user_id = :userId AND revoked_at IS NULL
                RETURNING %s
                """.formatted(PAT_COLUMNS))
                .param("userId", userId)
                .query(TokenRepository::toPat)
                .list();
    }

    public void insertRefresh(UUID id, UUID userId, UUID familyId, byte[] tokenHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO identity.refresh_tokens (id, user_id, family_id, token_hash, expires_at)
                VALUES (:id, :userId, :familyId, :tokenHash, :expiresAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("familyId", familyId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", toOffset(expiresAt))
                .update();
    }

    public Optional<RefreshToken> lockRefresh(byte[] tokenHash) {
        return jdbc.sql("SELECT " + REFRESH_COLUMNS + " FROM identity.refresh_tokens WHERE token_hash = :hash FOR UPDATE")
                .param("hash", tokenHash)
                .query(TokenRepository::toRefresh)
                .optional();
    }

    public void markRefreshUsed(UUID id) {
        jdbc.sql("UPDATE identity.refresh_tokens SET used_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void revokeRefreshFamily(UUID familyId) {
        jdbc.sql("UPDATE identity.refresh_tokens SET revoked_at = now() WHERE family_id = :familyId AND revoked_at IS NULL")
                .param("familyId", familyId)
                .update();
    }

    public void revokeAllRefresh(UUID userId) {
        jdbc.sql("UPDATE identity.refresh_tokens SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
                .param("userId", userId)
                .update();
    }

    private static @Nullable OffsetDateTime toOffset(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static PersonalAccessToken toPat(ResultSet row, int rowNumber) throws SQLException {
        String[] scopes = (String[]) row.getArray("scopes").getArray();
        return new PersonalAccessToken(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getString("name"),
                row.getBytes("token_hash"),
                Arrays.asList(scopes),
                instant(row, "expires_at"),
                instant(row, "last_used_at"),
                instant(row, "revoked_at"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static RefreshToken toRefresh(ResultSet row, int rowNumber) throws SQLException {
        return new RefreshToken(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getObject("family_id", UUID.class),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                instant(row, "used_at"),
                instant(row, "revoked_at"));
    }
}
