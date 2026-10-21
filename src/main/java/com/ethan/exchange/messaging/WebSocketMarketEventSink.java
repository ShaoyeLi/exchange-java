package com.ethan.exchange.messaging;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class WebSocketMarketEventSink implements MarketEventSink {

    private final SimpMessagingTemplate messaging;

    public WebSocketMarketEventSink(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void accept(DomainEventEnvelope event) {
        messaging.convertAndSend(destination(event.market()), event);
    }

    public static String destination(String market) {
        return "/topic/markets/" + market.replace('/', '_');
    }
}
