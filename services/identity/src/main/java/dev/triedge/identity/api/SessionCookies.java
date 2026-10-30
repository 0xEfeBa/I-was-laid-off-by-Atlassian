package dev.triedge.identity.api;

import dev.triedge.identity.config.IdentityProperties;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookies {

    private final boolean secure;
    private final String name;
    private final Set<String> allowedOrigins;

    public SessionCookies(IdentityProperties properties) {
        this.secure = properties.secureCookies();
        this.name = secure ? "__Host-triedge_refresh" : "triedge_refresh";
        this.allowedOrigins = Set.copyOf(properties.allowedOrigins());
    }

    public String name() {
        return name;
    }

    public ResponseCookie refresh(String value, Duration maxAge) {
        return base(value).maxAge(maxAge).build();
    }

    public ResponseCookie cleared() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public void requireAllowedOrigin(@Nullable String origin) {
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Request origin is not allowed");
        }
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/");
    }
}
