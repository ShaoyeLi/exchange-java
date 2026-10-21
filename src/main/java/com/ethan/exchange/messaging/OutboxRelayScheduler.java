package com.ethan.exchange.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"exchange.messaging.enabled", "exchange.messaging.scheduling-enabled"},
        havingValue = "true")
public class OutboxRelayScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayService relay;

    public OutboxRelayScheduler(OutboxRelayService relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${exchange.messaging.relay-delay:500}")
    public void run() {
        try {
            relay.relayOnce();
        } catch (RuntimeException exception) {
            LOGGER.error("outbox relay cycle failed", exception);
        }
    }
}
