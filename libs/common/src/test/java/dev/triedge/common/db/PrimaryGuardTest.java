package dev.triedge.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class PrimaryGuardTest {

    @Test
    void standbyRefusesWrites() {
        JdbcClient standby = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(standby.sql(anyString()).query(Boolean.class).single()).thenReturn(true);
        PrimaryGuard guard = new PrimaryGuard(standby);

        assertThat(guard.isPrimary()).isFalse();
        assertThatThrownBy(guard::requirePrimary).isInstanceOf(NotPrimaryException.class);
    }
}
