package com.ethan.exchange.messaging;

public interface MarketEventSink {

    void accept(DomainEventEnvelope event);
}
