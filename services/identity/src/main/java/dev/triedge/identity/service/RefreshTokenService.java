package dev.triedge.identity.service;

import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.identity.config.IdentityProperties;
import dev.triedge.identity.data.RefreshToken;
import dev.triedge.identity.data.TokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RefreshTokenService {

    public record IssuedToken(String value, Instant expiresAt) {
    }

    public sealed interface Rotation {
        record Rotated(UUID userId, IssuedToken token) implements Rotation {
        }

        record Rejected(String reason) implements Rotation {
        }
    }

    private final TokenRepository tokens;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration ttl;
    private final String localRegion;
    private final Set<String> clusters;

    public RefreshTokenService(TokenRepository tokens, TransactionTemplate transactions, Clock clock,
            IdentityProperties properties, ClusterProperties clusterProperties) {
        this.tokens = tokens;
        this.transactions = transactions;
        this.clock = clock;
        this.ttl = properties.refreshTokenTtl();
        this.localRegion = clusterProperties.region();
        this.clusters = Set.copyOf(clusterProperties.clusters().keySet());
    }

    public String clusterOf(String value) {
        int dot = value.indexOf('.');
        String cluster = dot < 0 ? "" : value.substring(0, dot);
        return clusters.contains(cluster) ? cluster : localRegion;
    }

    public Duration ttl() {
        return ttl;
    }

    public IssuedToken issue(UUID userId) {
        return transactions.execute(status -> create(userId, UUID.randomUUID()));
    }

    public Rotation rotate(String value) {
        return transactions.execute(status -> {
            Optional<RefreshToken> found = tokens.lockRefresh(SecretTokens.hash(value));
            if (found.isEmpty()) {
                return new Rotation.Rejected("unknown");
            }
            RefreshToken token = found.get();
            if (token.revokedAt() != null || token.usedAt() != null) {
                tokens.revokeRefreshFamily(token.familyId());
                return new Rotation.Rejected("reused");
            }
            if (!token.expiresAt().isAfter(clock.instant())) {
                return new Rotation.Rejected("expired");
            }
            tokens.markRefreshUsed(token.id());
            return new Rotation.Rotated(token.userId(), create(token.userId(), token.familyId()));
        });
    }

    public void revoke(String value) {
        ClusterContext.run(clusterOf(value), () -> transactions.executeWithoutResult(status ->
                tokens.lockRefresh(SecretTokens.hash(value)).ifPresent(token -> tokens.revokeRefreshFamily(token.familyId()))));
    }

    private IssuedToken create(UUID userId, UUID familyId) {
        String value = ClusterContext.current().orElse(localRegion) + "." + SecretTokens.newRefreshToken();
        Instant expiresAt = clock.instant().plus(ttl);
        tokens.insertRefresh(UUID.randomUUID(), userId, familyId, SecretTokens.hash(value), expiresAt);
        return new IssuedToken(value, expiresAt);
    }
}
