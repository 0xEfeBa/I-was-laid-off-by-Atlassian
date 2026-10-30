package dev.triedge.identity.data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PersonalAccessToken(
        UUID id,
        UUID userId,
        String name,
        byte[] tokenHash,
        List<String> scopes,
        @Nullable Instant expiresAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant revokedAt,
        Instant createdAt) {
}
