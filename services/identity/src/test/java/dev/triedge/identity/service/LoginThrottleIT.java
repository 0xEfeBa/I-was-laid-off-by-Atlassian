package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.testing.TestDatabase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class LoginThrottleIT {

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
    private final LoginThrottle throttle = new LoginThrottle(jdbc);

    @Test
    void firstFailuresAreFree() {
        String user = unique();
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS - 1; i++) {
            throttle.recordFailure(user);
        }

        assertThat(throttle.lockedFor(user)).isEmpty();
    }

    @Test
    void lockGrowsWithEachFailureAndIsCapped() {
        String user = unique();
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) {
            throttle.recordFailure(user);
        }
        assertThat(throttle.lockedFor(user)).hasValueSatisfying(d -> assertThat(d).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2)));

        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(user);
        }
        assertThat(throttle.lockedFor(user)).hasValueSatisfying(d -> assertThat(d).isBetween(Duration.ofSeconds(7), Duration.ofSeconds(9)));

        for (int i = 0; i < 20; i++) {
            throttle.recordFailure(user);
        }
        assertThat(throttle.lockedFor(user)).hasValueSatisfying(d -> assertThat(d).isLessThanOrEqualTo(LoginThrottle.MAX_LOCK));
    }

    @Test
    void successClearsHistory() {
        String user = unique();
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) {
            throttle.recordFailure(user);
        }

        throttle.recordSuccess(user);

        assertThat(throttle.lockedFor(user)).isEmpty();
    }

    @Test
    void oldFailuresAreForgotten() {
        String user = unique();
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS - 1; i++) {
            throttle.recordFailure(user);
        }
        age(user, LoginThrottle.FAILURE_WINDOW.plusMinutes(1));

        throttle.recordFailure(user);

        assertThat(throttle.lockedFor(user)).isEmpty();
        assertThat(failures(user)).isEqualTo(1);
    }

    @Test
    void recentFailuresKeepCounting() {
        String user = unique();
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS - 1; i++) {
            throttle.recordFailure(user);
        }
        age(user, LoginThrottle.FAILURE_WINDOW.minusMinutes(1));

        throttle.recordFailure(user);

        assertThat(throttle.lockedFor(user)).isPresent();
        assertThat(failures(user)).isEqualTo(LoginThrottle.FREE_ATTEMPTS);
    }

    @Test
    void purgeRemovesOnlyStaleRows() {
        String stale = unique();
        String recent = unique();
        throttle.recordFailure(stale);
        throttle.recordFailure(recent);
        age(stale, LoginThrottle.FAILURE_WINDOW.plusMinutes(1));

        assertThat(throttle.purgeStale()).isGreaterThanOrEqualTo(1);

        assertThat(exists(stale)).isFalse();
        assertThat(exists(recent)).isTrue();
    }

    private void age(String user, Duration by) {
        jdbc.sql("""
                UPDATE identity.login_attempts
                SET last_failure_at = last_failure_at - make_interval(secs => :seconds),
                    locked_until = locked_until - make_interval(secs => :seconds)
                WHERE username = :username
                """)
                .param("seconds", (double) by.toSeconds())
                .param("username", user)
                .update();
    }

    private int failures(String user) {
        return jdbc.sql("SELECT failures FROM identity.login_attempts WHERE username = :username")
                .param("username", user)
                .query(Integer.class)
                .single();
    }

    private boolean exists(String user) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM identity.login_attempts WHERE username = :username)")
                .param("username", user)
                .query(Boolean.class)
                .single();
    }

    private static String unique() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }
}
