package dev.triedge.sidecar.auth;

import java.util.List;
import java.util.UUID;

public record AuthenticatedUser(UUID id, String username, String role, String homeRegion, Kind kind,
        List<String> scopes) {

    public enum Kind {
        SESSION,
        PERSONAL_ACCESS_TOKEN
    }

    public static final List<String> ALL_SCOPES = List.of("edge:read", "edge:write", "git:read", "git:write");

    public boolean hasScopes(List<String> required) {
        return scopes.containsAll(required);
    }
}
