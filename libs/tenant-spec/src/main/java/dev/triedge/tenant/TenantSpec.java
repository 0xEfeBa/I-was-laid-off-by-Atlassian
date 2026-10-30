package dev.triedge.tenant;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public record TenantSpec(
        String name,
        List<String> regions,
        List<String> hosts,
        List<Backend> backends,
        List<Route> routes,
        @Nullable Hook hook) {

    public static final List<String> ALL_REGIONS = List.of("eu", "us", "ap");

    public record Backend(String name, String host, int port) {

        public String address() {
            return host + ":" + port;
        }
    }

    public record Route(
            String path,
            Match match,
            List<String> methods,
            String backend,
            Auth auth,
            List<String> scopes,
            RouteBy routeBy,
            @Nullable Integer timeoutSeconds,
            @Nullable RateLimit rateLimit) {
    }

    public record RateLimit(int requests, Unit per) {

        public enum Unit {
            SECOND,
            MINUTE,
            HOUR
        }
    }

    public record Hook(String host, int port, boolean failOpen) {

        public String address() {
            return host + ":" + port;
        }
    }

    public enum Match {
        PREFIX,
        EXACT
    }

    public enum Auth {
        NONE("none"),
        SESSION("jwt"),
        TOKEN("pat"),
        ANY("jwt_or_pat");

        private final String sidecarName;

        Auth(String sidecarName) {
            this.sidecarName = sidecarName;
        }

        public String sidecarName() {
            return sidecarName;
        }
    }

    public enum RouteBy {
        LOCAL,
        HOME;

        public String specName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public @Nullable Backend backend(String name) {
        return backends.stream().filter(b -> b.name().equals(name)).findFirst().orElse(null);
    }
}
