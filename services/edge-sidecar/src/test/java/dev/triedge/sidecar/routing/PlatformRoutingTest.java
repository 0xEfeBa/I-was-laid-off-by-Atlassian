package dev.triedge.sidecar.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.sidecar.auth.AuthenticatedUser;
import dev.triedge.sidecar.authz.RouteRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformRoutingTest {

    private final PlatformRouting routing = new PlatformRouting("eu");

    @Test
    void homeRoutesFollowTheUsersHomeRegion() {
        assertThat(routing.targetRegion(rule(RouteRule.RouteBy.HOME, "eu", "ap"), user("ap"))).isEqualTo("ap");
    }

    @Test
    void everythingElseStaysLocal() {
        assertThat(routing.targetRegion(rule(RouteRule.RouteBy.LOCAL, "eu", "ap"), user("ap"))).isEqualTo("eu");
        assertThat(routing.targetRegion(rule(RouteRule.RouteBy.HOME, "eu", "ap"), null)).isEqualTo("eu");
        assertThat(routing.targetRegion(rule(RouteRule.RouteBy.HOME, "eu"), user("us"))).isEqualTo("eu");
    }

    @Test
    void homeRoutesFollowTheHomeDatabaseWhenItFailsOver() {
        PlatformRouting failedOver = new PlatformRouting("eu",
                cluster -> cluster.equals("ap") ? Optional.of("us") : Optional.empty());

        assertThat(failedOver.targetRegion(rule(RouteRule.RouteBy.HOME, "eu", "us", "ap"), user("ap"))).isEqualTo("us");
        assertThat(failedOver.targetRegion(rule(RouteRule.RouteBy.HOME, "eu", "us", "ap"), user("eu"))).isEqualTo("eu");
        assertThat(failedOver.targetRegion(rule(RouteRule.RouteBy.HOME, "eu", "ap"), user("ap"))).isEqualTo("eu");
    }

    private static RouteRule rule(RouteRule.RouteBy routeBy, String... regions) {
        return new RouteRule("platform", RouteRule.Auth.JWT, List.of(), null, false, List.of(regions), List.of(), routeBy);
    }

    private static AuthenticatedUser user(String homeRegion) {
        return new AuthenticatedUser(UUID.randomUUID(), "efe", "USER", homeRegion, AuthenticatedUser.Kind.SESSION,
                AuthenticatedUser.ALL_SCOPES);
    }
}
