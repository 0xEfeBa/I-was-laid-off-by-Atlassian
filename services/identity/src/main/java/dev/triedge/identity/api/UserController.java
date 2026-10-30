package dev.triedge.identity.api;

import dev.triedge.identity.data.User;
import dev.triedge.identity.data.UserRepository;
import dev.triedge.identity.service.AccountService;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    public record ProfileResponse(UUID id, String username, String role, String homeRegion) {
    }

    public record PublicUserResponse(UUID id, String username) {
    }

    public record BanResponse(UUID id, String username, @Nullable Instant bannedAt) {
    }

    private final UserRepository users;
    private final AccountService accounts;

    public UserController(UserRepository users, AccountService accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    @GetMapping("/v1/users/me")
    public ProfileResponse me(Caller caller) {
        User user = users.findById(caller.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return new ProfileResponse(user.id(), user.username(), user.role(), user.homeRegion());
    }

    @GetMapping("/v1/users/{id}")
    public PublicUserResponse user(Caller caller, @PathVariable UUID id) {
        User user = accounts.findAnywhere(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return new PublicUserResponse(user.id(), user.username());
    }

    @PostMapping("/v1/admin/users/{id}/ban")
    public BanResponse ban(Caller caller, @PathVariable UUID id) {
        if (!caller.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
        User banned = accounts.ban(id);
        return new BanResponse(banned.id(), banned.username(), banned.bannedAt());
    }
}
