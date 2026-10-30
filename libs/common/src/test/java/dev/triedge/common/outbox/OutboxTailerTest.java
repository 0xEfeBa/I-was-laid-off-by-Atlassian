package dev.triedge.common.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class OutboxTailerTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);

    @Test
    void rejectsUnknownSchema() {
        assertThatThrownBy(() -> new OutboxTailer(jdbc, "public", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public");
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new OutboxTailer(jdbc, "platform", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
    }
}
