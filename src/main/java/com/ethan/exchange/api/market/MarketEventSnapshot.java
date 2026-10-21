package com.ethan.exchange.api.market;

import com.ethan.exchange.messaging.DomainEventEnvelope;

import java.util.List;

public record MarketEventSnapshot(
        String market,
        long lastSequence,
        List<DomainEventEnvelope> events
) {

    public MarketEventSnapshot {
        events = List.copyOf(events);
    }
}
