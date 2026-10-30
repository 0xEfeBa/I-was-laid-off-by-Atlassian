package dev.triedge.sidecar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.sidecar.directory.IdentityIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private final IdentityIndex index = new IdentityIndex();
    private final PatValidator validator = new PatValidator(index, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    @Test
    void validTokenResolvesToItsOwnerWithItsScopes() {
        index.putUser(new IdentityIndex.UserEntry(userId, "efe", "USER", "ap", false, 1));
        index.putPat(PatValidator.hash("tep_good"), new IdentityIndex.PatEntry(UUID.randomUUID(), userId, List.of("git:read"), null));

        assertThat(validator.validate("tep_good")).hasValueSatisfying(user -> {
            assertThat(user.id()).isEqualTo(userId);
            assertThat(user.homeRegion()).isEqualTo("ap");
            assertThat(user.scopes()).containsExactly("git:read");
            assertThat(user.kind()).isEqualTo(AuthenticatedUser.Kind.PERSONAL_ACCESS_TOKEN);
        });
    }

    @Test
    void rejectsUnknownExpiredAndBannedTokens() {
        index.putUser(new IdentityIndex.UserEntry(userId, "efe", "USER", "eu", false, 1));
        index.putPat(PatValidator.hash("tep_old"), new IdentityIndex.PatEntry(UUID.randomUUID(), userId, List.of("git:read"), NOW));
        index.putPat(PatValidator.hash("tep_good"), new IdentityIndex.PatEntry(UUID.randomUUID(), userId, List.of("git:read"), null));

        assertThat(validator.validate("tep_unknown")).isEmpty();
        assertThat(validator.validate("tep_old")).isEmpty();
        assertThat(validator.validate("not-a-pat")).isEmpty();

        index.banUser(userId, 2);
        assertThat(validator.validate("tep_good")).isEmpty();
    }

    @Test
    void tokenOfUnknownUserIsRejected() {
        index.putPat(PatValidator.hash("tep_orphan"), new IdentityIndex.PatEntry(UUID.randomUUID(), UUID.randomUUID(), List.of("git:read"), null));

        assertThat(validator.validate("tep_orphan")).isEmpty();
    }
}
