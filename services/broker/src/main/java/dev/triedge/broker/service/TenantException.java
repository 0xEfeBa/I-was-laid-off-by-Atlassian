package dev.triedge.broker.service;

import java.util.List;

public class TenantException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        INVALID, CONFLICT, NOT_FOUND, BUSY, LIMIT, GONE
    }

    private final Kind kind;
    private final transient List<String> errors;

    public TenantException(Kind kind, String message) {
        this(kind, message, List.of());
    }

    public TenantException(Kind kind, String message, List<String> errors) {
        super(message);
        this.kind = kind;
        this.errors = List.copyOf(errors);
    }

    public Kind kind() {
        return kind;
    }

    public List<String> errors() {
        return errors;
    }
}
