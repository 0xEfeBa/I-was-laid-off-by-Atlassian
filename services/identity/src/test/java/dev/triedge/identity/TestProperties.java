package dev.triedge.identity;

import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.identity.config.IdentityProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class TestProperties {

    private TestProperties() {
    }

    public static ClusterProperties clusters(String... regions) {
        Map<String, ClusterConnection> clusters = new LinkedHashMap<>();
        for (String region : regions) {
            clusters.put(region, new ClusterConnection("jdbc:postgresql://" + region + ".invalid/triedge", "u", "p", 1));
        }
        return new ClusterProperties(regions[0], clusters);
    }

    public static IdentityProperties identity() {
        return identity(null, true);
    }

    public static IdentityProperties identity(@Nullable String signingJwk, boolean allowEphemeralKey) {
        return new IdentityProperties(
                "tri-edge-test",
                "http://localhost:2379",
                Duration.ofMinutes(2),
                Duration.ofDays(30),
                signingJwk,
                List.of(),
                allowEphemeralKey,
                false,
                List.of("http://app.test"),
                1024,
                1);
    }
}
