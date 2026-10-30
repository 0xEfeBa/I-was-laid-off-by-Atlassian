package dev.triedge.common.outbox;

public record Checkpoint(long txid, long id) {

    public static final Checkpoint START = new Checkpoint(0, 0);

    public static Checkpoint of(OutboxEvent event) {
        return new Checkpoint(event.txid(), event.id());
    }
}
