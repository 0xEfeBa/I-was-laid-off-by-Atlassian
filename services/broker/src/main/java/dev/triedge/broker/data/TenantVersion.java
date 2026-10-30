package dev.triedge.broker.data;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record TenantVersion(int version, String specHash, String reason, @Nullable String state,
        @Nullable String detail, Instant createdAt) {
}
