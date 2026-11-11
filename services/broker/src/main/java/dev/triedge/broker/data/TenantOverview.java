package dev.triedge.broker.data;

import org.jspecify.annotations.Nullable;

public record TenantOverview(String name, String homeRegion, @Nullable Integer activeVersion, int latestVersion,
        @Nullable String operation, @Nullable String operationState, @Nullable String operationDescription) {
}
