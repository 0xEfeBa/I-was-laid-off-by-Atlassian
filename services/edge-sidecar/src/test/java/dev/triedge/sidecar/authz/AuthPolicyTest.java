package dev.triedge.sidecar.authz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.sidecar.TestTokens;
import dev.triedge.sidecar.auth.JwksProvider;
import dev.triedge.sidecar.auth.JwtValidator;
import dev.triedge.sidecar.auth.PatValidator;
import dev.triedge.sidecar.directory.IdentityIndex;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AuthPolicyTest {

    private final TestTokens tokens = new TestTokens();
    private final IdentityIndex index = new IdentityIndex();
    private final AuthPolicy policy = new AuthPolicy(
            new JwtValidator(new JwksProvider(RestClient.create(), tokens.jwksUrl(), Duration.ofSeconds(30),
                    Clock.systemUTC()), TestTokens.ISSUER, Clock.systemUTC()),
            new PatValidator(index, Clock.systemUTC()),
            index);
    private final UUID userId = UUID.randomUUID();
    private String jwt;

    @BeforeEach
    void setUp() {
        index.putUser(new IdentityIndex.UserEntry(userId, "efe", "USER", "eu", false, 1));
        index.putPat(PatValidator.hash("tep_read"), new IdentityIndex.PatEntry(UUID.randomUUID(), userId, List.of("git:read"), null));
        jwt = "Bearer " + tokens.token(userId, "eu");
    }

    @AfterEach
    void close() {
        tokens.close();
    }

    @Test
    void openRoutesNeedNoCredentials() {
        assertThat(policy.decide(rule(RouteRule.Auth.NONE), null)).isEqualTo(new AuthPolicy.Decision.Allow(null));
    }

    @Test
    void deniedRoutesStayDenied() {
        assertThat(policy.decide(rule(RouteRule.Auth.DENY), jwt))
                .isInstanceOfSatisfying(AuthPolicy.Decision.Deny.class, deny -> assertThat(deny.status()).isEqualTo(403));
    }

    @Test
    void sessionRoutesAcceptOnlySessions() {
        assertThat(policy.decide(rule(RouteRule.Auth.JWT), jwt)).isInstanceOf(AuthPolicy.Decision.Allow.class);
        assertThat(policy.decide(rule(RouteRule.Auth.JWT), "Bearer tep_read"))
                .isEqualTo(new AuthPolicy.Decision.Deny(401, "Valid credentials are required", false));
        assertThat(policy.decide(rule(RouteRule.Auth.JWT), null))
                .isEqualTo(new AuthPolicy.Decision.Deny(401, "Valid credentials are required", false));
    }

    @Test
    void tokenRoutesAcceptOnlyTokensAndChallengeGitClients() {
        assertThat(policy.decide(rule(RouteRule.Auth.PAT), "Bearer tep_read")).isInstanceOf(AuthPolicy.Decision.Allow.class);
        assertThat(policy.decide(rule(RouteRule.Auth.PAT), jwt))
                .isEqualTo(new AuthPolicy.Decision.Deny(401, "Valid credentials are required", true));
    }

    @Test
    void eitherRouteAcceptsBoth() {
        assertThat(policy.decide(rule(RouteRule.Auth.JWT_OR_PAT), jwt)).isInstanceOf(AuthPolicy.Decision.Allow.class);
        assertThat(policy.decide(rule(RouteRule.Auth.JWT_OR_PAT), "Bearer tep_read")).isInstanceOf(AuthPolicy.Decision.Allow.class);
    }

    @Test
    void missingScopesAreForbidden() {
        RouteRule needsWrite = new RouteRule("git", RouteRule.Auth.JWT_OR_PAT, List.of("git:write"), null, false,
                List.of("eu"), List.of(), RouteRule.RouteBy.LOCAL);

        assertThat(policy.decide(needsWrite, "Bearer tep_read"))
                .isInstanceOfSatisfying(AuthPolicy.Decision.Deny.class, deny -> assertThat(deny.status()).isEqualTo(403));
        assertThat(policy.decide(needsWrite, jwt)).isInstanceOf(AuthPolicy.Decision.Allow.class);
    }

    @Test
    void bannedUsersLoseSessionsBeforeTheirTokensExpire() {
        index.banUser(userId, 2);

        assertThat(policy.decide(rule(RouteRule.Auth.JWT), jwt)).isInstanceOf(AuthPolicy.Decision.Deny.class);
        assertThat(policy.decide(rule(RouteRule.Auth.PAT), "Bearer tep_read")).isInstanceOf(AuthPolicy.Decision.Deny.class);
    }

    private static RouteRule rule(RouteRule.Auth auth) {
        return new RouteRule("platform", auth, List.of(), null, false, List.of("eu"), List.of(), RouteRule.RouteBy.LOCAL);
    }
}
