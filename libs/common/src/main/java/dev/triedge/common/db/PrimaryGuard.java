package dev.triedge.common.db;

import org.springframework.jdbc.core.simple.JdbcClient;

public class PrimaryGuard {

    private final JdbcClient jdbc;

    public PrimaryGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isPrimary() {
        return !jdbc.sql("SELECT pg_is_in_recovery()").query(Boolean.class).single();
    }

    public void requirePrimary() {
        if (!isPrimary()) {
            throw new NotPrimaryException();
        }
    }
}
