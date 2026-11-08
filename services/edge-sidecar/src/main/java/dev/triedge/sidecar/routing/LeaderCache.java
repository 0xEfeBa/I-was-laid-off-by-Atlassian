package dev.triedge.sidecar.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeaderCache implements Function<String, Optional<String>> {

    private static final Logger log = LoggerFactory.getLogger(LeaderCache.class);

    private record Entry(Optional<String> leader, Instant checkedAt) {
    }

    private final Function<String, Optional<String>> source;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<String>>> inFlight = new ConcurrentHashMap<>();

    public LeaderCache(Function<String, Optional<String>> source, Duration ttl, Clock clock) {
        this.source = source;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<String> apply(String cluster) {
        Entry cached = entries.get(cluster);
        if (cached != null && fresh(cached)) {
            return cached.leader();
        }
        CompletableFuture<Optional<String>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<String>> running = inFlight.putIfAbsent(cluster, mine);
        if (running != null) {
            return cached != null ? cached.leader() : running.join();
        }
        try {
            Entry latest = entries.get(cluster);
            Optional<String> leader = latest != null && fresh(latest) ? latest.leader() : lookUp(cluster, latest);
            mine.complete(leader);
            return leader;
        } finally {
            mine.complete(Optional.empty());
            inFlight.remove(cluster, mine);
        }
    }

    private boolean fresh(Entry entry) {
        return clock.instant().isBefore(entry.checkedAt().plus(ttl));
    }

    private Optional<String> lookUp(String cluster, @Nullable Entry previous) {
        try {
            Optional<String> leader = source.apply(cluster);
            entries.put(cluster, new Entry(leader, clock.instant()));
            return leader;
        } catch (RuntimeException e) {
            Optional<String> last = previous == null ? Optional.empty() : previous.leader();
            entries.put(cluster, new Entry(last, clock.instant()));
            log.warn("Could not look up the leader of {}; using the last known one for {}: {}", cluster, ttl,
                    e.getMessage());
            return last;
        }
    }
}
