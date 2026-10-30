package dev.triedge.sidecar.directory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class IdentityIndex {

    public record UserEntry(UUID id, String username, String role, String homeRegion, boolean banned, long version) {
    }

    public record PatEntry(UUID id, UUID userId, List<String> scopes, @Nullable Instant expiresAt) {
    }

    private final Map<UUID, UserEntry> users = new ConcurrentHashMap<>();
    private final Map<String, PatEntry> pats = new ConcurrentHashMap<>();

    public void putUser(UserEntry user) {
        users.merge(user.id(), user, (current, incoming) -> incoming.version() >= current.version() ? incoming : current);
    }

    public void banUser(UUID id, long version) {
        users.computeIfPresent(id, (key, current) -> version >= current.version()
                ? new UserEntry(current.id(), current.username(), current.role(), current.homeRegion(), true, version)
                : current);
    }

    public void putPat(String tokenHash, PatEntry pat) {
        pats.put(tokenHash, pat);
    }

    public void removePat(String tokenHash) {
        pats.remove(tokenHash);
    }

    public Optional<UserEntry> user(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    public Optional<PatEntry> pat(String tokenHash) {
        return Optional.ofNullable(pats.get(tokenHash));
    }

    public int userCount() {
        return users.size();
    }

    public int patCount() {
        return pats.size();
    }
}
