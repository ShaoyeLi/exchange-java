package com.ethan.exchange.messaging;

import java.util.List;

public interface MarketEventStore {

    boolean claimInbox(
            String consumerName, String eventId, String topic, int partition, long offset);

    void insertProjection(DomainEventEnvelope event);

    List<DomainEventEnvelope> load(String market, long afterSequence, int limit);

    List<DomainEventEnvelope> loadRecent(String market, int limit);

    long lastSequence(String market);
}
