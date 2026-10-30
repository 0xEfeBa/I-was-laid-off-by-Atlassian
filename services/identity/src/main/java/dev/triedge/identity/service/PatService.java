package dev.triedge.identity.service;

import dev.triedge.identity.api.ApiException;
import dev.triedge.identity.data.PersonalAccessToken;
import dev.triedge.identity.data.TokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PatService {

    public static final Set<String> SCOPES = Set.of("edge:read", "edge:write", "git:read", "git:write");

    static final int MAX_ACTIVE_TOKENS = 20;
    static final Duration MAX_LIFETIME = Duration.ofDays(365);

    public record CreatedPat(PersonalAccessToken pat, String token) {
    }

    private final TokenRepository tokens;
    private final IdentityEvents events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PatService(TokenRepository tokens, IdentityEvents events, TransactionTemplate transactions, Clock clock) {
        this.tokens = tokens;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    public CreatedPat create(UUID userId, String name, List<String> scopes, @Nullable Duration lifetime) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Token name must be 1 to 100 characters");
        }
        if (scopes == null || scopes.isEmpty() || !SCOPES.containsAll(scopes)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Scopes must be a non-empty subset of " + SCOPES);
        }
        if (lifetime != null && (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Token lifetime must be between 1 and 365 days");
        }
        String token = SecretTokens.newPat();
        Instant now = clock.instant();
        PersonalAccessToken pat = new PersonalAccessToken(
                UUID.randomUUID(), userId, name, SecretTokens.hash(token), List.copyOf(scopes),
                lifetime == null ? null : now.plus(lifetime), null, null, now);
        transactions.executeWithoutResult(status -> {
            if (tokens.activePats(userId).size() >= MAX_ACTIVE_TOKENS) {
                throw new ApiException(HttpStatus.CONFLICT, "At most " + MAX_ACTIVE_TOKENS + " active tokens are allowed");
            }
            tokens.insertPat(pat);
            events.patCreated(pat);
        });
        return new CreatedPat(pat, token);
    }

    public List<PersonalAccessToken> list(UUID userId) {
        return tokens.activePats(userId);
    }

    public boolean revoke(UUID userId, UUID patId) {
        Boolean revoked = transactions.execute(status -> tokens.revokePat(userId, patId)
                .map(pat -> {
                    events.patRevoked(pat);
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(revoked);
    }
}
