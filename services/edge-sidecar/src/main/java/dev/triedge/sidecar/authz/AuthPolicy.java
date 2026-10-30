package dev.triedge.sidecar.authz;

import dev.triedge.sidecar.auth.AuthenticatedUser;
import dev.triedge.sidecar.auth.Credentials;
import dev.triedge.sidecar.auth.JwtValidator;
import dev.triedge.sidecar.auth.PatValidator;
import dev.triedge.sidecar.directory.IdentityIndex;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public class AuthPolicy {

    public sealed interface Decision {

        record Allow(@Nullable AuthenticatedUser user) implements Decision {
        }

        record Deny(int status, String detail, boolean challengeBasic) implements Decision {
        }
    }

    private final JwtValidator jwts;
    private final PatValidator pats;
    private final IdentityIndex index;

    public AuthPolicy(JwtValidator jwts, PatValidator pats, IdentityIndex index) {
        this.jwts = jwts;
        this.pats = pats;
        this.index = index;
    }

    public Decision decide(RouteRule rule, @Nullable String authorization) {
        if (rule.auth() == RouteRule.Auth.DENY) {
            return new Decision.Deny(403, "This route is not available", false);
        }
        if (rule.auth() == RouteRule.Auth.NONE) {
            return new Decision.Allow(null);
        }
        Optional<AuthenticatedUser> user = Credentials.parse(authorization).flatMap(credentials -> switch (credentials) {
            case Credentials.Session session when rule.auth() != RouteRule.Auth.PAT -> jwts.validate(session.jwt())
                    .filter(this::notBanned);
            case Credentials.AccessToken token when rule.auth() != RouteRule.Auth.JWT -> pats.validate(token.token());
            default -> Optional.empty();
        });
        if (user.isEmpty()) {
            return new Decision.Deny(401, "Valid credentials are required", rule.acceptsAccessTokens());
        }
        if (!user.get().hasScopes(rule.scopes())) {
            return new Decision.Deny(403, "The token does not have the required scopes " + rule.scopes(), false);
        }
        return new Decision.Allow(user.get());
    }

    private boolean notBanned(AuthenticatedUser user) {
        return index.user(user.id()).map(entry -> !entry.banned()).orElse(true);
    }
}
