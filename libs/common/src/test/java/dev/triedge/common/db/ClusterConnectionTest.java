package dev.triedge.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClusterConnectionTest {

    @Test
    void neverPrintsThePassword() {
        ClusterConnection connection = new ClusterConnection("jdbc:postgresql://db/triedge", "app", "s3cret", 5);

        assertThat(connection.toString()).doesNotContain("s3cret").contains("password=***");
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new ClusterConnection("jdbc:mysql://db/x", "u", "p", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClusterConnection(null, "u", "p", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClusterConnection("jdbc:postgresql://db/x", "u", "p", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
