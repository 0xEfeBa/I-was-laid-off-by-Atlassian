package dev.triedge.sidecar.auth;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

public class JwksProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksProvider.class);

    private final RestClient http;
    private final String url;
    private final Duration minRefresh;
    private final Clock clock;
    private volatile JWKSet keys = new JWKSet();
    private volatile Instant lastAttempt = Instant.EPOCH;

    public JwksProvider(RestClient http, String url, Duration minRefresh, Clock clock) {
        this.http = http;
        this.url = url;
        this.minRefresh = minRefresh;
        this.clock = clock;
    }

    public Optional<ECKey> key(String keyId) {
        Optional<ECKey> known = find(keyId);
        if (known.isPresent()) {
            return known;
        }
        refreshIfDue();
        return find(keyId);
    }

    @Scheduled(fixedDelayString = "${triedge.sidecar.jwks-check-interval:PT1S}")
    public void keepFresh() {
        if (keys.getKeys().isEmpty()) {
            refresh();
        } else {
            refreshIfDue();
        }
    }

    public synchronized void refreshIfDue() {
        if (clock.instant().isBefore(lastAttempt.plus(minRefresh))) {
            return;
        }
        refresh();
    }

    private synchronized void refresh() {
        lastAttempt = clock.instant();
        try {
            String body = http.get().uri(url).retrieve().body(String.class);
            if (body != null) {
                keys = JWKSet.parse(body);
            }
        } catch (Exception e) {
            log.warn("Could not refresh signing keys from {}; keeping the last known set: {}", url, e.getMessage());
        }
    }

    private Optional<ECKey> find(String keyId) {
        JWK key = keys.getKeyByKeyId(keyId);
        return key instanceof ECKey ec ? Optional.of(ec) : Optional.empty();
    }
}
