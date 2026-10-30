package dev.triedge.identity.api;

import dev.triedge.identity.data.User;
import dev.triedge.identity.service.AccountService;
import dev.triedge.identity.service.RefreshTokenService;
import dev.triedge.identity.service.TokenIssuer;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AuthController {

    public record SignupRequest(String username, String email, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserResponse(UUID id, String username) {

        static UserResponse of(User user) {
            return new UserResponse(user.id(), user.username());
        }
    }

    public record SessionResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }

    private final AccountService accounts;
    private final RefreshTokenService refreshTokens;
    private final TokenIssuer issuer;
    private final SessionCookies cookies;

    public AuthController(AccountService accounts, RefreshTokenService refreshTokens, TokenIssuer issuer,
            SessionCookies cookies) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.issuer = issuer;
        this.cookies = cookies;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@RequestBody SignupRequest request) {
        User user = accounts.signup(request.username(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.of(user));
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest request) {
        return withSession(accounts.login(request.username(), request.password()));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<SessionResponse> refresh(
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) @Nullable String origin,
            @CookieValue(value = "#{@sessionCookies.name()}", required = false) @Nullable String refreshToken) {
        cookies.requireAllowedOrigin(origin);
        Optional<AccountService.Session> session =
                refreshToken == null ? Optional.empty() : accounts.refresh(refreshToken);
        if (session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookies.cleared().toString())
                    .build();
        }
        return withSession(session.get());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) @Nullable String origin,
            @CookieValue(value = "#{@sessionCookies.name()}", required = false) @Nullable String refreshToken) {
        cookies.requireAllowedOrigin(origin);
        if (refreshToken != null) {
            refreshTokens.revoke(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.cleared().toString())
                .build();
    }

    private ResponseEntity<SessionResponse> withSession(AccountService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookies.refresh(session.refreshToken().value(), refreshTokens.ttl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SessionResponse(session.accessToken(), "Bearer", issuer.ttl().toSeconds(),
                        UserResponse.of(session.user())));
    }
}
