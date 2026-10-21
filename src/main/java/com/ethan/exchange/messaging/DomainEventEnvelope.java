package com.ethan.exchange.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record DomainEventEnvelope(
        String eventId,
        String aggregateType,
        long aggregateId,
        long aggregateVersion,
        String market,
        long marketSequence,
        String type,
        Instant occurredAt,
        int schemaVersion,
        JsonNode payload
) {

    public DomainEventEnvelope {
        if (eventId == null || eventId.isBlank() || aggregateType == null
                || aggregateType.isBlank() || aggregateId <= 0 || aggregateVersion < 0
                || market == null || market.isBlank() || marketSequence <= 0
                || type == null || type.isBlank() || occurredAt == null
                || schemaVersion <= 0 || payload == null) {
            throw new IllegalArgumentException("invalid event envelope");
        }
    }
}
