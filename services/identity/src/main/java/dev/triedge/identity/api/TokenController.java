package dev.triedge.identity.api;

import dev.triedge.identity.data.PersonalAccessToken;
import dev.triedge.identity.service.PatService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tokens")
public class TokenController {

    public record CreateTokenRequest(String name, List<String> scopes, @Nullable Integer expiresInDays) {
    }

    public record TokenResponse(UUID id, String name, List<String> scopes, @Nullable Instant expiresAt,
            Instant createdAt) {

        static TokenResponse of(PersonalAccessToken pat) {
            return new TokenResponse(pat.id(), pat.name(), pat.scopes(), pat.expiresAt(), pat.createdAt());
        }
    }

    public record CreatedTokenResponse(TokenResponse token, String value) {
    }

    private final PatService pats;

    public TokenController(PatService pats) {
        this.pats = pats;
    }

    @GetMapping
    public List<TokenResponse> list(Caller caller) {
        return pats.list(caller.id()).stream().map(TokenResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<CreatedTokenResponse> create(Caller caller, @RequestBody CreateTokenRequest request) {
        Duration lifetime = request.expiresInDays() == null ? null : Duration.ofDays(request.expiresInDays());
        PatService.CreatedPat created = pats.create(caller.id(), request.name(), request.scopes(), lifetime);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CreatedTokenResponse(TokenResponse.of(created.pat()), created.token()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(Caller caller, @PathVariable UUID id) {
        if (!pats.revoke(caller.id(), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Token not found");
        }
        return ResponseEntity.noContent().build();
    }
}
