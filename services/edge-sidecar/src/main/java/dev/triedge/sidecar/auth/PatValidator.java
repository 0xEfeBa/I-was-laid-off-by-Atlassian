package dev.triedge.sidecar.auth;

import dev.triedge.sidecar.directory.IdentityIndex;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;

public class PatValidator {

    public static final String PREFIX = "tep_";

    private final IdentityIndex index;
    private final Clock clock;

    public PatValidator(IdentityIndex index, Clock clock) {
        this.index = index;
        this.clock = clock;
    }

    public Optional<AuthenticatedUser> validate(String token) {
        if (!token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        return index.pat(hash(token))
                .filter(pat -> pat.expiresAt() == null || pat.expiresAt().isAfter(clock.instant()))
                .flatMap(pat -> index.user(pat.userId())
                        .filter(user -> !user.banned())
                        .map(user -> new AuthenticatedUser(user.id(), user.username(), user.role(), user.homeRegion(),
                                AuthenticatedUser.Kind.PERSONAL_ACCESS_TOKEN, pat.scopes())));
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
