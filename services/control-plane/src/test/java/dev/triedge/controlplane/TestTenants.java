package dev.triedge.controlplane;

import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.tenant.TenantSpec;
import java.util.List;
import java.util.UUID;

public final class TestTenants {

    private TestTenants() {
    }

    public static ActiveTenant tenant(String name, String host, String backendHost, int backendPort) {
        return tenant(name, 1, new TenantSpec(name, List.of("eu"), List.of(host),
                List.of(new TenantSpec.Backend("main", backendHost, backendPort)),
                List.of(new TenantSpec.Route("/", TenantSpec.Match.PREFIX, List.of(), "main", TenantSpec.Auth.NONE,
                        List.of(), TenantSpec.RouteBy.LOCAL, null, null)),
                null));
    }

    public static ActiveTenant tenant(String name, int version, TenantSpec spec) {
        return new ActiveTenant(UUID.nameUUIDFromBytes(name.getBytes()), name, version, spec, false);
    }
}
