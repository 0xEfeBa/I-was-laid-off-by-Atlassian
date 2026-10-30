package dev.triedge.identity.service;

import dev.triedge.identity.config.IdentityProperties;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;

    private final Argon2PasswordEncoder encoder;
    private final String dummyHash;

    public PasswordHasher(IdentityProperties properties) {
        this.encoder = new Argon2PasswordEncoder(
                SALT_BYTES, HASH_BYTES, PARALLELISM, properties.argon2MemoryKib(), properties.argon2Iterations());
        this.dummyHash = encoder.encode("timing-equaliser-for-unknown-users");
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }

    public void burnTime(String password) {
        encoder.matches(password, dummyHash);
    }
}
