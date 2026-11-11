package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.outbox.OutboxWriter;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.identity.api.ApiException;
import dev.triedge.identity.data.TokenRepository;
import dev.triedge.identity.data.UserRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class PatBanRaceIT {

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
    private final ClusterDataSources clusters = new ClusterDataSources(Map.of("eu", TestDatabase.connection(1)));
    private final PatService service = new PatService(
            new TokenRepository(jdbc),
            new UserRepository(jdbc, clusters),
            new IdentityEvents(new OutboxWriter(jdbc, "identity"), JsonMapper.builder().build()),
            new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.dataSource())),
            Clock.systemUTC());
    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO identity.users (id, username, email, password_hash, home_region)
                VALUES (:id, :username, :email, 'x', 'eu')
                """)
                .param("id", userId)
                .param("username", "p" + userId.toString().substring(0, 8))
                .param("email", userId + "@example.com")
                .update();
    }

    @AfterEach
    void close() {
        clusters.close();
    }

    @Test
    void aBannedUserCannotCreateTokens() {
        jdbc.sql("UPDATE identity.users SET banned_at = now() WHERE id = :id").param("id", userId).update();

        assertThatThrownBy(() -> service.create(userId, "ci", List.of("edge:read"), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.list(userId)).isEmpty();
    }

    @Test
    void creatingATokenWaitsForARunningBanAndIsThenRefused() throws Exception {
        try (Connection ban = TestDatabase.dataSource().getConnection()) {
            ban.setAutoCommit(false);
            try (PreparedStatement update = ban.prepareStatement(
                    "UPDATE identity.users SET banned_at = now() WHERE id = ?")) {
                update.setObject(1, userId);
                update.executeUpdate();
            }

            CompletableFuture<PatService.CreatedPat> creation =
                    CompletableFuture.supplyAsync(() -> service.create(userId, "ci", List.of("edge:read"), null));
            Thread.sleep(300);
            assertThat(creation).isNotDone();

            ban.commit();

            assertThatThrownBy(() -> creation.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ApiException.class);
        }
        assertThat(service.list(userId)).isEmpty();
    }
}
