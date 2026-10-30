package dev.triedge.controlplane.source;

import dev.triedge.tenant.TenantSpec;
import java.util.UUID;

public record ActiveTenant(UUID id, String name, int version, TenantSpec spec, boolean system) {
}
