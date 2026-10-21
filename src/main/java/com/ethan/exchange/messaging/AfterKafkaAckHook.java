package com.ethan.exchange.messaging;

@FunctionalInterface
public interface AfterKafkaAckHook {

    AfterKafkaAckHook NONE = event -> { };

    void afterAcknowledgement(OutboxEvent event);
}
