package dev.triedge.identity.service;

import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoginThrottle {

    static final int FREE_ATTEMPTS = 5;
    static final Duration MAX_LOCK = Duration.ofMinutes(15);
    static final Duration FAILURE_WINDOW = Duration.ofHours(1);

    private final JdbcClient jdbc;

    public LoginThrottle(JdbcClient homeJdbc) {
        this.jdbc = homeJdbc;
    }

    public Optional<Duration> lockedFor(String username) {
        return jdbc.sql("""
                SELECT ceil(extract(epoch FROM locked_until - now()))::bigint FROM identity.login_attempts
                WHERE username = :username AND locked_until > now()
                """)
                .param("username", username)
                .query(Long.class)
                .optional()
                .map(seconds -> Duration.ofSeconds(Math.max(1, seconds)));
    }

    public void recordFailure(String username) {
        jdbc.sql("""
                INSERT INTO identity.login_attempts AS a (username, failures, last_failure_at)
                VALUES (:username, 1, now())
                ON CONFLICT (username) DO UPDATE
                SET (failures, last_failure_at, locked_until) = (
                    SELECT next.failures, now(), CASE
                        WHEN next.failures >= :free
                        THEN now() + least(
                            make_interval(secs => power(2, next.failures - :free)),
                            make_interval(secs => :maxSeconds))
                    END
                    FROM (SELECT CASE
                        WHEN a.last_failure_at > now() - make_interval(secs => :windowSeconds)
                        THEN a.failures + 1
                        ELSE 1
                    END AS failures) AS next)
                """)
                .param("username", username)
                .param("free", FREE_ATTEMPTS)
                .param("maxSeconds", (double) MAX_LOCK.toSeconds())
                .param("windowSeconds", (double) FAILURE_WINDOW.toSeconds())
                .update();
    }

    public void recordSuccess(String username) {
        jdbc.sql("DELETE FROM identity.login_attempts WHERE username = :username")
                .param("username", username)
                .update();
    }

    @Scheduled(fixedDelayString = "${triedge.identity.janitor-interval:PT5M}")
    public int purgeStale() {
        return jdbc.sql("""
                DELETE FROM identity.login_attempts
                WHERE last_failure_at < now() - make_interval(secs => :windowSeconds)
                """)
                .param("windowSeconds", (double) FAILURE_WINDOW.toSeconds())
                .update();
    }
}
