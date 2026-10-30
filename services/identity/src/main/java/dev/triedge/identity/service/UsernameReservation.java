package dev.triedge.identity.service;

import dev.triedge.common.etcd.EtcdClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UsernameReservation {

    static final String PREFIX = "/triedge/usernames/";

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

    public boolean reserve(String username, Reservation reservation) {
        return etcd.putIfAbsent(PREFIX + username, reservation.encode());
    }

    public Optional<Reservation> find(String username) {
        return etcd.get(PREFIX + username).map(Reservation::decode);
    }

    public boolean release(String username, UUID userId) {
        Optional<String> current = etcd.get(PREFIX + username);
        if (current.isEmpty() || !Reservation.decode(current.get()).userId().equals(userId)) {
            return false;
        }
        return etcd.deleteIfValue(PREFIX + username, current.get());
    }

    public Map<String, Reservation> all() {
        Map<String, Reservation> result = new LinkedHashMap<>();
        etcd.getPrefix(PREFIX).forEach((key, value) -> result.put(key.substring(PREFIX.length()), Reservation.decode(value)));
        return result;
    }
}
