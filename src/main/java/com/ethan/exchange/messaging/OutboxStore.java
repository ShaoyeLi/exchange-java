package com.ethan.exchange.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface OutboxStore {

    boolean tryAcquireLease(String relayName, String ownerId, Instant now, Duration duration);

    List<OutboxEvent> loadReadyBatch(
            String relayName, String ownerId, Instant now, int limit);

    void markPublished(String relayName, String ownerId, String eventId, Instant publishedAt);

    void markFailed(
            String relayName,
            String ownerId,
            String eventId,
            int maxAttempts,
            Instant nextAttempt,
            String error);

    void releaseLease(String relayName, String ownerId);

    boolean replayDead(String eventId);
}
