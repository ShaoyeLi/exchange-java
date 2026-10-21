package com.ethan.exchange.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class MarketEventProjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketEventProjector.class);

    private final MarketEventStore store;
    private final TransactionTemplate transactions;
    private final List<MarketEventSink> sinks;

    public MarketEventProjector(
            MarketEventStore store,
            TransactionTemplate transactions,
            List<MarketEventSink> sinks) {
        this.store = store;
        this.transactions = transactions;
        this.sinks = List.copyOf(sinks);
    }

    public boolean process(
            String consumerName,
            DomainEventEnvelope event,
            String topic,
            int partition,
            long offset) {
        Boolean applied = transactions.execute(status -> {
            if (!store.claimInbox(consumerName, event.eventId(), topic, partition, offset)) {
                return false;
            }
            store.insertProjection(event);
            return true;
        });
        if (!Boolean.TRUE.equals(applied)) {
            return false;
        }
        for (MarketEventSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (RuntimeException exception) {
                LOGGER.warn("derived market event sink failed for eventId={}",
                        event.eventId(), exception);
            }
        }
        return true;
    }
}
