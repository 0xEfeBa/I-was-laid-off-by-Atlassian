package dev.triedge.sidecar.directory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.outbox.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IdentityIndexTest {

    private final IdentityIndex index = new IdentityIndex();
    private final IdentityEventApplier applier = new IdentityEventApplier(index, JsonMapper.builder().build());
    private final UUID userId = UUID.randomUUID();

    @Test
    void olderVersionsNeverOverwriteNewerOnes() {
        index.putUser(new IdentityIndex.UserEntry(userId, "efe", "USER", "eu", true, 3));
        index.putUser(new IdentityIndex.UserEntry(userId, "efe", "USER", "eu", false, 2));

        assertThat(index.user(userId)).hasValueSatisfying(user -> assertThat(user.banned()).isTrue());

        index.banUser(userId, 1);
        assertThat(index.user(userId)).hasValueSatisfying(user -> assertThat(user.version()).isEqualTo(3));
    }

    @Test
    void appliesUserAndTokenEvents() {
        applier.apply(event("USER_CREATED", """
                {"id":"%s","username":"efe","role":"USER","homeRegion":"us","banned":false,"version":1}
                """.formatted(userId)));
        applier.apply(event("PAT_CREATED", """
                {"id":"%s","userId":"%s","tokenHash":"abc","scopes":["git:read"],"expiresAt":"2030-01-01T00:00:00Z"}
                """.formatted(UUID.randomUUID(), userId)));
        applier.apply(event("PAT_CREATED", """
                {"id":"%s","userId":"%s","tokenHash":"def","scopes":["git:write"],"expiresAt":null}
                """.formatted(UUID.randomUUID(), userId)));

        assertThat(index.user(userId)).hasValueSatisfying(user -> assertThat(user.homeRegion()).isEqualTo("us"));
        assertThat(index.pat("abc")).hasValueSatisfying(pat -> {
            assertThat(pat.scopes()).containsExactly("git:read");
            assertThat(pat.expiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        });
        assertThat(index.pat("def")).hasValueSatisfying(pat -> assertThat(pat.expiresAt()).isNull());

        applier.apply(event("PAT_REVOKED", "{\"id\":\"x\",\"userId\":\"%s\",\"tokenHash\":\"abc\"}".formatted(userId)));
        applier.apply(event("USER_BANNED", "{\"id\":\"%s\",\"version\":2}".formatted(userId)));
        applier.apply(event("SOMETHING_NEW", "{}"));

        assertThat(index.pat("abc")).isEmpty();
        assertThat(index.patCount()).isEqualTo(1);
        assertThat(index.userCount()).isEqualTo(1);
        assertThat(index.user(userId)).hasValueSatisfying(user -> assertThat(user.banned()).isTrue());
    }

    private static OutboxEvent event(String type, String payload) {
        return new OutboxEvent(1, 1, "user", "x", type, payload, Instant.now());
    }
}
