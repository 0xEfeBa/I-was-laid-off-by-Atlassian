package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.testing.TestDatabase;
import dev.triedge.identity.TestProperties;
import dev.triedge.identity.data.TokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefreshTokenServiceIT {

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final RefreshTokenService service = new RefreshTokenService(
            new TokenRepository(jdbc),
            new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.dataSource())),
            clock,
            TestProperties.identity(),
            TestProperties.clusters("eu", "us"));
    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO identity.users (id, username, email, password_hash, home_region)
                VALUES (:id, :username, :email, 'x', 'eu')
                """)
                .param("id", userId)
                .param("username", "r" + userId.toString().substring(0, 8))
                .param("email", userId + "@example.com")
                .update();
    }

    @Test
    void rotationReplacesTheToken() {
        RefreshTokenService.IssuedToken first = service.issue(userId);

        RefreshTokenService.Rotation rotation = service.rotate(first.value());

        assertThat(rotation).isInstanceOfSatisfying(RefreshTokenService.Rotation.Rotated.class, rotated -> {
            assertThat(rotated.userId()).isEqualTo(userId);
            assertThat(rotated.token().value()).isNotEqualTo(first.value());
        });
    }

    @Test
    void reusingAnOldTokenRevokesTheWholeFamily() {
        RefreshTokenService.IssuedToken first = service.issue(userId);
        RefreshTokenService.Rotation.Rotated second =
                (RefreshTokenService.Rotation.Rotated) service.rotate(first.value());

        assertThat(service.rotate(first.value())).isEqualTo(new RefreshTokenService.Rotation.Rejected("reused"));
        assertThat(service.rotate(second.token().value())).isEqualTo(new RefreshTokenService.Rotation.Rejected("reused"));
    }

    @Test
    void expiredAndUnknownTokensAreRejected() {
        RefreshTokenService.IssuedToken token = service.issue(userId);
        now.set(now.get().plus(service.ttl()).plusSeconds(1));

        assertThat(service.rotate(token.value())).isEqualTo(new RefreshTokenService.Rotation.Rejected("expired"));
        assertThat(service.rotate("made-up")).isEqualTo(new RefreshTokenService.Rotation.Rejected("unknown"));
    }

    @Test
    void revokeEndsTheSession() {
        RefreshTokenService.IssuedToken token = service.issue(userId);

        service.revoke(token.value());

        assertThat(service.rotate(token.value())).isEqualTo(new RefreshTokenService.Rotation.Rejected("reused"));
    }

    @Test
    void tokensNameTheClusterThatHoldsThem() {
        RefreshTokenService.IssuedToken token = service.issue(userId);

        assertThat(token.value()).startsWith("eu.");
        assertThat(service.clusterOf(token.value())).isEqualTo("eu");
        assertThat(service.clusterOf("us.abc")).isEqualTo("us");
        assertThat(service.clusterOf("mars.abc")).isEqualTo("eu");
        assertThat(service.clusterOf("no-dot")).isEqualTo("eu");
    }
}
