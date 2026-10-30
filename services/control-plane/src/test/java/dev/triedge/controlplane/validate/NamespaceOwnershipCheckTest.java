package dev.triedge.controlplane.validate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.tenant.TenantSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamespaceOwnershipCheckTest {

    private final NamespaceOwnershipCheck check = new NamespaceOwnershipCheck(List.of("identity", "postgres", "localhost"));

    @ParameterizedTest
    @ValueSource(strings = {"identity", "postgres", "localhost", "db.localhost", "metadata.google.internal",
            "169.254.169.254", "127.0.0.1", "10.0.0.5"})
    void tenantsCannotPointAtPlatformOrLocalAddresses(String host) {
        assertThat(check.violations(spec(host, null))).hasSize(1);
        assertThat(check.violations(spec("api.t-shop.svc", new TenantSpec.Hook(host, 9002, false)))).hasSize(1);
    }

    @Test
    void tenantServicesAreFine() {
        assertThat(check.violations(spec("api.t-shop.svc", new TenantSpec.Hook("authz.t-shop.svc", 9002, false)))).isEmpty();
        assertThat(check.violations(spec("echo", null))).isEmpty();
    }

    private static TenantSpec spec(String backendHost, TenantSpec.Hook hook) {
        return new TenantSpec("shop", List.of("eu"), List.of("shop.test"),
                List.of(new TenantSpec.Backend("api", backendHost, 80)),
                List.of(new TenantSpec.Route("/", TenantSpec.Match.PREFIX, List.of(), "api", TenantSpec.Auth.NONE,
                        List.of(), TenantSpec.RouteBy.LOCAL, null, null)),
                hook);
    }
}
