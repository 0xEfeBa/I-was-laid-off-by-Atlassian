package dev.triedge.identity.data;

import dev.triedge.common.db.ClusterDataSources;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLUMNS =
            "id, username, email, password_hash, role, home_region, banned_at, version";

    private final JdbcClient home;
    private final ClusterDataSources clusters;

    public UserRepository(JdbcClient homeJdbc, ClusterDataSources clusters) {
        this.home = homeJdbc;
        this.clusters = clusters;
    }

    public void insert(User user) {
        home.sql("""
                INSERT INTO identity.users (id, username, email, password_hash, role, home_region)
                VALUES (:id, :username, :email, :passwordHash, :role, :homeRegion)
                """)
                .param("id", user.id())
                .param("username", user.username())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("role", user.role())
                .param("homeRegion", user.homeRegion())
                .update();
    }

    public Optional<User> findById(UUID id) {
        return home.sql("SELECT " + COLUMNS + " FROM identity.users WHERE id = :id")
                .param("id", id)
                .query(UserRepository::toUser)
                .optional();
    }

    public Optional<User> findByUsername(String region, String username) {
        return clusters.jdbc(region)
                .sql("SELECT " + COLUMNS + " FROM identity.users WHERE username = :username")
                .param("username", username)
                .query(UserRepository::toUser)
                .optional();
    }

    public Optional<String> locate(UUID id) {
        return clusters.clusters().stream()
                .filter(cluster -> clusters.jdbc(cluster)
                        .sql("SELECT EXISTS (SELECT 1 FROM identity.users WHERE id = :id)")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .findFirst();
    }

    public Optional<User> ban(UUID id) {
        return home.sql("""
                UPDATE identity.users
                SET banned_at = now(), version = version + 1, updated_at = now()
                WHERE id = :id AND banned_at IS NULL
                RETURNING %s
                """.formatted(COLUMNS))
                .param("id", id)
                .query(UserRepository::toUser)
                .optional();
    }

    private static User toUser(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime bannedAt = row.getObject("banned_at", OffsetDateTime.class);
        return new User(
                row.getObject("id", UUID.class),
                row.getString("username"),
                row.getString("email"),
                row.getString("password_hash"),
                row.getString("role"),
                row.getString("home_region"),
                bannedAt == null ? null : bannedAt.toInstant(),
                row.getLong("version"));
    }
}
