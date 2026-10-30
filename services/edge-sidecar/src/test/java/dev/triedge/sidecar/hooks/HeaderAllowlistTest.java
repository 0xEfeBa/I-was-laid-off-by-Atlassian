package dev.triedge.sidecar.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.sidecar.authz.RouteRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderAllowlistTest {

    private final HeaderAllowlist allowlist = new HeaderAllowlist();
    private final RouteRule rule = new RouteRule("git", RouteRule.Auth.JWT_OR_PAT, List.of(), "hook:1", false,
            List.of("eu", "us"), List.of("shard-0", "api"), RouteRule.RouteBy.LOCAL);

    @Test
    void keepsTenantHeadersAndAllowedRoutingChoices() {
        Map<String, String> filtered = allowlist.filter(rule, Map.of(
                "X-Tenant-Repo-Id", "42",
                "x-target-region", "us",
                "x-backend-set", "shard-0"));

        assertThat(filtered).containsOnly(
                Map.entry("x-tenant-repo-id", "42"),
                Map.entry("x-target-region", "us"),
                Map.entry("x-backend-set", "shard-0"));
    }

    @Test
    void dropsIdentityHeadersAndForeignRegionsOrBackends() {
        Map<String, String> filtered = allowlist.filter(rule, Map.of(
                "x-user-id", "00000000-0000-0000-0000-000000000000",
                "x-user-role", "ADMIN",
                "x-target-region", "ap",
                "x-backend-set", "other-tenant-backend",
                "authorization", "Bearer stolen"));

        assertThat(filtered).isEmpty();
    }
}
