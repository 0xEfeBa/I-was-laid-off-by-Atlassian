package dev.triedge.common.db;

public class NotPrimaryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotPrimaryException() {
        super("This database is a standby; writes must go to the primary");
    }
}
