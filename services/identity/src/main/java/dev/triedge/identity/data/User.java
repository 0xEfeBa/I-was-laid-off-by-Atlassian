package dev.triedge.identity.data;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record User(
        UUID id,
        String username,
        String email,
        String passwordHash,
        String role,
        String homeRegion,
        @Nullable Instant bannedAt,
        long version) {

    public boolean banned() {
        return bannedAt != null;
    }

    public boolean admin() {
        return "ADMIN".equals(role);
    }
}
