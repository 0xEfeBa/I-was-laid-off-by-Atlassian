package dev.triedge.broker.data;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record Tenant(UUID id, String name, UUID ownerId, String homeRegion, @Nullable Integer activeVersion,
        @Nullable Instant deletedAt) {

    public boolean live() {
        return deletedAt == null;
    }
}
