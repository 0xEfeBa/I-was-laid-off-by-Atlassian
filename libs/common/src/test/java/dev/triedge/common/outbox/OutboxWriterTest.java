package dev.triedge.common.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class OutboxWriterTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);

    @Test
    void rejectsUnknownSchema() {
        assertThatThrownBy(() -> new OutboxWriter(jdbc, "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public");
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        OutboxWriter writer = new OutboxWriter(jdbc, "platform");

        assertThatThrownBy(() -> writer.append("repo", "1", "REF_UPDATED", "{}"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
}
