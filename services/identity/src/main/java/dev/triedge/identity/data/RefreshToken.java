package dev.triedge.identity.data;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RefreshToken(
        UUID id,
        UUID userId,
        UUID familyId,
        Instant expiresAt,
        @Nullable Instant usedAt,
        @Nullable Instant revokedAt) {
}
