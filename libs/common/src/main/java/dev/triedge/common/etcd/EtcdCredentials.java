package dev.triedge.common.etcd;

import org.jspecify.annotations.Nullable;

public record EtcdCredentials(String username, String password) {

    public EtcdCredentials {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("etcd username and password must not be blank");
        }
    }

    public static @Nullable EtcdCredentials of(@Nullable String username, @Nullable String password) {
        if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
            return null;
        }
        if (username == null || password == null) {
            throw new IllegalArgumentException("etcd username and password must be set together");
        }
        return new EtcdCredentials(username, password);
    }

    @Override
    public String toString() {
        return "EtcdCredentials[username=" + username + "]";
    }
}
