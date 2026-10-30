package dev.triedge.identity.service;

import dev.triedge.common.db.ClusterProperties;
import dev.triedge.identity.data.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationJanitor {

    static final Duration GRACE = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ReservationJanitor.class);

    private final UsernameReservation reservations;
    private final UserRepository users;
    private final Clock clock;
    private final String region;

    public ReservationJanitor(UsernameReservation reservations, UserRepository users, Clock clock,
            ClusterProperties clusters) {
        this.reservations = reservations;
        this.users = users;
        this.clock = clock;
        this.region = clusters.region();
    }

    @Scheduled(fixedDelayString = "${triedge.identity.janitor-interval:PT5M}")
    public int releaseAbandoned() {
        Instant cutoff = clock.instant().minus(GRACE);
        int released = 0;
        for (var entry : reservations.all().entrySet()) {
            UsernameReservation.Reservation reservation = entry.getValue();
            if (reservation.region().equals(region)
                    && reservation.createdAt().isBefore(cutoff)
                    && users.findById(reservation.userId()).isEmpty()
                    && reservations.release(entry.getKey(), reservation.userId())) {
                released++;
            }
        }
        if (released > 0) {
            log.info("Released {} abandoned username reservations", released);
        }
        return released;
    }
}
