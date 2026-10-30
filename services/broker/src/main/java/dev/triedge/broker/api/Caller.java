package dev.triedge.broker.api;

import java.util.UUID;

public record Caller(UUID id, String role) {

    public static final String USER_ID_HEADER = "x-user-id";
    public static final String USER_ROLE_HEADER = "x-user-role";

    public boolean admin() {
        return "ADMIN".equals(role);
    }
}
