package dev.triedge.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import dev.triedge.common.testing.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class PrimaryGuardIT {

    private final PrimaryGuard guard = new PrimaryGuard(JdbcClient.create(TestDatabase.dataSource()));

    @Test
    void primaryDatabaseIsRecognised() {
        assertThat(guard.isPrimary()).isTrue();
        assertThatNoException().isThrownBy(guard::requirePrimary);
    }
}
