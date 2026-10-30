package dev.triedge.common.queue;

public record Job(long id, String queue, String payload, int attempts, int maxAttempts) {
}
