package dev.triedge.identity.service;

import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.identity.api.ApiException;
import dev.triedge.identity.data.TokenRepository;
import dev.triedge.identity.data.User;
import dev.triedge.identity.data.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AccountService {

    static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,38}");
    static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}");
    static final int MIN_PASSWORD = 12;
    static final int MAX_PASSWORD = 128;

    public record Session(User user, String accessToken, RefreshTokenService.IssuedToken refreshToken) {
    }

    public static final class LoginThrottled extends ApiException {

        private static final long serialVersionUID = 1L;

        private final Duration retryAfter;

        LoginThrottled(Duration retryAfter) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts; try again later");
            this.retryAfter = retryAfter;
            getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter.toSeconds()));
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    private final UserRepository users;
    private final TokenRepository tokens;
    private final UsernameReservation reservations;
    private final PasswordHasher passwords;
    private final LoginThrottle throttle;
    private final TokenIssuer issuer;
    private final RefreshTokenService refreshTokens;
    private final IdentityEvents events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String region;

    public AccountService(UserRepository users, TokenRepository tokens, UsernameReservation reservations,
            PasswordHasher passwords, LoginThrottle throttle, TokenIssuer issuer, RefreshTokenService refreshTokens,
            IdentityEvents events, TransactionTemplate transactions, Clock clock, ClusterProperties clusters) {
        this.users = users;
        this.tokens = tokens;
        this.reservations = reservations;
        this.passwords = passwords;
        this.throttle = throttle;
        this.issuer = issuer;
        this.refreshTokens = refreshTokens;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.region = clusters.region();
    }

    public User signup(String username, String email, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Username must be 2 to 39 lowercase letters, digits or dashes and start with a letter or digit");
        }
        if (email == null || !EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email address is not valid");
        }
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must be " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters");
        }
        String home = ClusterContext.current().orElse(region);
        User user = new User(UUID.randomUUID(), username, email.toLowerCase(Locale.ROOT),
                passwords.hash(password), "USER", home, null, 1);
        if (!reservations.reserve(username, new UsernameReservation.Reservation(home, user.id(), clock.instant()))) {
            throw new ApiException(HttpStatus.CONFLICT, "Username is already taken");
        }
        try {
            transactions.executeWithoutResult(status -> {
                users.insert(user);
                events.userCreated(user);
            });
        } catch (DuplicateKeyException e) {
            reservations.release(username, user.id());
            throw new ApiException(HttpStatus.CONFLICT, "Email address is already registered");
        } catch (RuntimeException e) {
            reservations.release(username, user.id());
            throw e;
        }
        return user;
    }

    public Session login(String username, String password) {
        if (username == null || password == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }
        String home = reservations.find(username).map(UsernameReservation.Reservation::region).orElse(region);
        return ClusterContext.call(home, () -> loginAt(username, password));
    }

    private Session loginAt(String username, String password) {
        Optional<Duration> locked = throttle.lockedFor(username);
        if (locked.isPresent()) {
            throw new LoginThrottled(locked.get());
        }
        Optional<User> user = users.findByUsername(ClusterContext.current().orElse(region), username);
        if (user.isEmpty()) {
            passwords.burnTime(password);
            throttle.recordFailure(username);
            throw invalidCredentials();
        }
        if (!passwords.matches(password, user.get().passwordHash())) {
            throttle.recordFailure(username);
            throw invalidCredentials();
        }
        if (user.get().banned()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been suspended");
        }
        throttle.recordSuccess(username);
        return new Session(user.get(), issuer.issue(user.get()), refreshTokens.issue(user.get().id()));
    }

    public Optional<Session> refresh(String refreshToken) {
        return ClusterContext.call(refreshTokens.clusterOf(refreshToken), () -> {
            if (refreshTokens.rotate(refreshToken) instanceof RefreshTokenService.Rotation.Rotated rotated) {
                return users.findById(rotated.userId())
                        .filter(user -> !user.banned())
                        .map(user -> new Session(user, issuer.issue(user), rotated.token()));
            }
            return Optional.empty();
        });
    }

    public Optional<User> findAnywhere(UUID userId) {
        return users.locate(userId).flatMap(cluster -> ClusterContext.call(cluster, () -> users.findById(userId)));
    }

    public User ban(UUID userId) {
        String cluster = users.locate(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found or already banned"));
        return ClusterContext.call(cluster, () -> banAt(userId));
    }

    private User banAt(UUID userId) {
        return transactions.execute(status -> {
            User banned = users.ban(userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found or already banned"));
            tokens.revokeAllPats(userId).forEach(events::patRevoked);
            tokens.revokeAllRefresh(userId);
            events.userBanned(banned);
            return banned;
        });
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
}
