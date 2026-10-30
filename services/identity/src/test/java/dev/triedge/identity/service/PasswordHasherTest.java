package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.identity.TestProperties;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(TestProperties.identity());

    @Test
    void hashesWithArgon2idAndVerifies() {
        String hash = hasher.hash("correct horse battery");

        assertThat(hash).startsWith("$argon2id$").doesNotContain("correct horse battery");
        assertThat(hasher.matches("correct horse battery", hash)).isTrue();
        assertThat(hasher.matches("wrong horse battery", hash)).isFalse();
    }

    @Test
    void samePasswordGetsDifferentSalt() {
        assertThat(hasher.hash("correct horse battery")).isNotEqualTo(hasher.hash("correct horse battery"));
    }

    @Test
    void burningTimeNeverThrows() {
        hasher.burnTime("anything at all");
    }
}
