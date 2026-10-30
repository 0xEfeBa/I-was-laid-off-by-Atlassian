package dev.triedge.broker.data;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ServiceInstance(String instanceId, UUID tenantId, String serviceId, String planId,
        String parametersHash, String operation, String operationState, @Nullable String operationDescription,
        Instant updatedAt) {

    public static final String IN_PROGRESS = "in progress";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    public boolean inProgress() {
        return IN_PROGRESS.equals(operationState);
    }
}
