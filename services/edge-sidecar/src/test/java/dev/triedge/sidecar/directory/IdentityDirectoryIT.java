package dev.triedge.sidecar.directory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.outbox.OutboxWriter;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.sidecar.auth.PatValidator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class IdentityDirectoryIT {

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
    private final TransactionTemplate transactions =
            new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.dataSource()));
    private final OutboxWriter outbox = new OutboxWriter(jdbc, "identity");
    private final IdentityIndex index = new IdentityIndex();
    private ClusterDataSources clusters;
    private IdentityDirectory directory;

    @BeforeEach
    void start() {
        clusters = new ClusterDataSources(Map.of("eu", TestDatabase.connection(2)));
        directory = new IdentityDirectory(clusters, new InitialLoader(index),
                new IdentityEventApplier(index, JsonMapper.builder().build()), Duration.ofMillis(100));
    }

    @AfterEach
    void stop() {
        directory.stop();
        clusters.close();
    }

    @Test
    void loadsExistingDataThenFollowsNewEvents() {
        UUID existing = createUser("present");
        String existingToken = "tep_" + UUID.randomUUID();
        createPat(existing, existingToken);

        directory.start();

        assertThat(directory.isRunning()).isTrue();
        assertThat(index.user(existing)).isPresent();
        assertThat(index.pat(PatValidator.hash(existingToken))).isPresent();

        UUID later = UUID.randomUUID();
        transactions.executeWithoutResult(status -> outbox.append("user", later.toString(), "USER_CREATED", """
                {"id":"%s","username":"later","role":"USER","homeRegion":"eu","banned":false,"version":1}
                """.formatted(later)));
        transactions.executeWithoutResult(status -> outbox.append("user", existing.toString(), "USER_BANNED",
                "{\"id\":\"%s\",\"version\":2}".formatted(existing)));

        directory.follow();

        assertThat(index.user(later)).isPresent();
        assertThat(index.user(existing)).hasValueSatisfying(user -> assertThat(user.banned()).isTrue());
        assertThat(directory.catchUp("eu")).isZero();
    }

    @Test
    void followingDoesNothingBeforeStart() {
        transactions.executeWithoutResult(status -> outbox.append("user", "x", "USER_BANNED", "{}"));

        directory.follow();

        assertThat(directory.isRunning()).isFalse();
        assertThat(directory.getPhase()).isEqualTo(IdentityDirectory.PHASE);
    }

    private UUID createUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO identity.users (id, username, email, password_hash, home_region)
                VALUES (:id, :username, :email, 'x', 'eu')
                """)
                .param("id", id)
                .param("username", name + "-" + id.toString().substring(0, 8))
                .param("email", id + "@example.com")
                .update();
        return id;
    }

    private void createPat(UUID userId, String token) {
        jdbc.sql("""
                INSERT INTO identity.personal_access_tokens (id, user_id, name, token_hash, scopes)
                VALUES (:id, :userId, 'test', decode(:hash, 'hex'), ARRAY['git:read'])
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("hash", PatValidator.hash(token))
                .update();
    }
}
