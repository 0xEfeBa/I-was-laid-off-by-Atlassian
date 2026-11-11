package dev.triedge.identity.service;

import dev.triedge.common.etcd.EtcdClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UsernameReservation {

    static final String PREFIX = "/triedge/usernames/";
    static final String EMAILS = "/triedge/emails/";

    private final EtcdClient etcd;

    public UsernameReservation(EtcdClient etcd) {
        this.etcd = etcd;
    }

    public record Reservation(String region, UUID userId, Instant createdAt) {

        String encode() {
            return region + "|" + userId + "|" + createdAt.getEpochSecond();
        }

        static Reservation decode(String value) {
            String[] parts = value.split("\\|");
            return new Reservation(parts[0], UUID.fromString(parts[1]), Instant.ofEpochSecond(Long.parseLong(parts[2])));
        }
    }

    public boolean reserve(String username, String email, Reservation reservation) {
        return etcd.putAllIfUnchanged(Map.of(
                PREFIX + username, reservation.encode(),
                EMAILS + emailKey(email), reservation.encode()), Map.of());
    }

    public Optional<Reservation> find(String username) {
        return etcd.get(PREFIX + username).map(Reservation::decode);
    }

    public Optional<Reservation> findEmail(String email) {
        return etcd.get(EMAILS + emailKey(email)).map(Reservation::decode);
    }

    public boolean release(String username, UUID userId) {
        return releaseKey(PREFIX + username, userId);
    }

    public boolean releaseEmail(String email, UUID userId) {
        return releaseEmailKey(emailKey(email), userId);
    }

    public boolean releaseEmailKey(String key, UUID userId) {
        return releaseKey(EMAILS + key, userId);
    }

    public Map<String, Reservation> all() {
        return list(PREFIX);
    }

    public Map<String, Reservation> emails() {
        return list(EMAILS);
    }

    static String emailKey(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private boolean releaseKey(String key, UUID userId) {
        Optional<String> current = etcd.get(key);
        if (current.isEmpty() || !Reservation.decode(current.get()).userId().equals(userId)) {
            return false;
        }
        return etcd.deleteIfValue(key, current.get());
    }

    private Map<String, Reservation> list(String prefix) {
        Map<String, Reservation> result = new LinkedHashMap<>();
        etcd.getPrefix(prefix).forEach((key, value) -> result.put(key.substring(prefix.length()), Reservation.decode(value)));
        return result;
    }
}
