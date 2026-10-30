package dev.triedge.sidecar.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public sealed interface Credentials {

    record Session(String jwt) implements Credentials {
    }

    record AccessToken(String token) implements Credentials {
    }

    static Optional<Credentials> parse(@Nullable String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(token.startsWith(PatValidator.PREFIX) ? new AccessToken(token) : new Session(token));
        }
        if (value.regionMatches(true, 0, "Basic ", 0, 6)) {
            return basicPassword(value.substring(6).trim()).map(AccessToken::new);
        }
        return Optional.empty();
    }

    private static Optional<String> basicPassword(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0 || colon == decoded.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(decoded.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
