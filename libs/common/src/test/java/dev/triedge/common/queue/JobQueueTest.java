package dev.triedge.common.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JobQueueTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);

    @Test
    void rejectsSchemaWithoutQueue() {
        assertThatThrownBy(() -> new JobQueue(jdbc, "identity", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
    }

    @Test
    void rejectsNonPositiveLockDuration() {
        assertThatThrownBy(() -> new JobQueue(jdbc, "platform", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobQueue(jdbc, "platform", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
