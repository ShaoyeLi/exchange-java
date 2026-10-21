package com.ethan.exchange.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

public record OutboxEvent(
        String eventId,
        long marketId,
        String market,
        long marketSequence,
        String aggregateType,
        long aggregateId,
        long aggregateVersion,
        String eventType,
        int schemaVersion,
        String payload,
        int attemptCount,
        Instant occurredAt
) {

    public DomainEventEnvelope toEnvelope(ObjectMapper mapper) throws JsonProcessingException {
        return new DomainEventEnvelope(
                eventId, aggregateType, aggregateId, aggregateVersion, market,
                marketSequence, eventType, occurredAt, schemaVersion, mapper.readTree(payload));
    }
}
