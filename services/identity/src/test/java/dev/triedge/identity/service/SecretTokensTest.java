package dev.triedge.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretTokensTest {

    @Test
    void personalAccessTokensCarryPrefixAnd256RandomBits() {
        String token = SecretTokens.newPat();

        assertThat(token).startsWith("tep_").hasSize(4 + 43).matches("tep_[A-Za-z0-9_-]+");
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(SecretTokens.newRefreshToken())).isTrue();
        }
    }

    @Test
    void hashIsStableSha256() {
        assertThat(SecretTokens.hex(SecretTokens.hash("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
