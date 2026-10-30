package dev.triedge.sidecar.routing;

import dev.triedge.sidecar.auth.AuthenticatedUser;
import dev.triedge.sidecar.authz.RouteRule;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public class PlatformRouting {

    private final String localRegion;
    private final Function<String, Optional<String>> leaderOf;

    public PlatformRouting(String localRegion) {
        this(localRegion, cluster -> Optional.empty());
    }

    public PlatformRouting(String localRegion, Function<String, Optional<String>> leaderOf) {
        this.localRegion = localRegion;
        this.leaderOf = leaderOf;
    }

    public String targetRegion(RouteRule rule, @Nullable AuthenticatedUser user) {
        if (rule.routeBy() != RouteRule.RouteBy.HOME || user == null) {
            return localRegion;
        }
        String target = leaderOf.apply(user.homeRegion()).orElse(user.homeRegion());
        return rule.regions().contains(target) ? target : localRegion;
    }
}
