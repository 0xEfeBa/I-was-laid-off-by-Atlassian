package dev.triedge.sidecar.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeaderCache implements Function<String, Optional<String>> {

    private static final Logger log = LoggerFactory.getLogger(LeaderCache.class);

    private record Entry(Optional<String> leader, Instant fetchedAt) {
    }

    private final Function<String, Optional<String>> source;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public LeaderCache(Function<String, Optional<String>> source, Duration ttl, Clock clock) {
        this.source = source;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<String> apply(String cluster) {
        Instant now = clock.instant();
        Entry cached = entries.get(cluster);
        if (cached != null && now.isBefore(cached.fetchedAt().plus(ttl))) {
            return cached.leader();
        }
        try {
            Optional<String> leader = source.apply(cluster);
            entries.put(cluster, new Entry(leader, now));
            return leader;
        } catch (RuntimeException e) {
            log.warn("Could not look up the leader of {}; using the last known one: {}", cluster, e.getMessage());
            return cached == null ? Optional.empty() : cached.leader();
        }
    }
}
