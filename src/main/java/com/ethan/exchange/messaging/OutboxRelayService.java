package com.ethan.exchange.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(name = "exchange.messaging.enabled", havingValue = "true")
public class OutboxRelayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final MessagingProperties properties;
    private final String ownerId;
    private final Clock clock;
    private final AfterKafkaAckHook afterAck;

    @Autowired
    public OutboxRelayService(
            OutboxStore store,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            MessagingProperties properties) {
        this(store, kafka, transactions, mapper, properties,
                UUID.randomUUID().toString(), Clock.systemUTC(), AfterKafkaAckHook.NONE);
    }

    public OutboxRelayService(
            OutboxStore store,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            MessagingProperties properties,
            String ownerId,
            Clock clock,
            AfterKafkaAckHook afterAck) {
        this.store = store;
        this.kafka = kafka;
        this.transactions = transactions;
        this.mapper = mapper;
        this.properties = properties;
        this.ownerId = ownerId;
        this.clock = clock;
        this.afterAck = afterAck;
    }

    public int relayOnce() {
        Instant now = clock.instant();
        Boolean acquired = transactions.execute(status -> store.tryAcquireLease(
                properties.relayName(), ownerId, now, properties.leaseDuration()));
        if (!Boolean.TRUE.equals(acquired)) {
            return 0;
        }
        int published = 0;
        try {
            List<OutboxEvent> events = transactions.execute(status -> store.loadReadyBatch(
                    properties.relayName(), ownerId, clock.instant(), properties.batchSize()));
            if (events == null) {
                return 0;
            }
            for (OutboxEvent event : events) {
                try {
                    send(event);
                    afterAck.afterAcknowledgement(event);
                    transactions.executeWithoutResult(status -> store.markPublished(
                            properties.relayName(), ownerId, event.eventId(), clock.instant()));
                    published++;
                } catch (RuntimeException exception) {
                    Instant next = clock.instant().plus(backoff(event.attemptCount()));
                    String message = sanitize(exception);
                    transactions.executeWithoutResult(status -> store.markFailed(
                            properties.relayName(), ownerId, event.eventId(),
                            properties.maxAttempts(), next, message));
                    break;
                }
            }
            return published;
        } finally {
            transactions.executeWithoutResult(status -> store.releaseLease(
                    properties.relayName(), ownerId));
        }
    }

    private void send(OutboxEvent event) {
        try {
            String value = mapper.writeValueAsString(event.toEnvelope(mapper));
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    properties.topic(), event.market(), value);
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EventDeliveryException("Kafka send interrupted", exception);
        } catch (ExecutionException | TimeoutException | JsonProcessingException exception) {
            throw new EventDeliveryException("Kafka send failed", exception);
        }
    }

    private Duration backoff(int attemptsBeforeFailure) {
        int shift = Math.min(attemptsBeforeFailure, 10);
        return properties.initialBackoff().multipliedBy(1L << shift);
    }

    private static String sanitize(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
