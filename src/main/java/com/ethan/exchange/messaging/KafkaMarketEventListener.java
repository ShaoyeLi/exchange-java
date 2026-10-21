package com.ethan.exchange.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "exchange.messaging.enabled", havingValue = "true")
public class KafkaMarketEventListener {

    private final ObjectMapper mapper;
    private final MarketEventProjector projector;
    private final MessagingProperties properties;

    public KafkaMarketEventListener(
            ObjectMapper mapper,
            MarketEventProjector projector,
            MessagingProperties properties) {
        this.mapper = mapper;
        this.projector = projector;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${exchange.messaging.topic}",
            groupId = "${exchange.messaging.consumer-name}")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            DomainEventEnvelope event = mapper.readValue(
                    record.value(), DomainEventEnvelope.class);
            if (event.schemaVersion() != 1) {
                throw new UnsupportedEventSchemaException(event.schemaVersion());
            }
            projector.process(properties.consumerName(), event, record.topic(),
                    record.partition(), record.offset());
        } catch (JsonProcessingException exception) {
            throw new InvalidEventException("event JSON is invalid", exception);
        }
    }
}
