package com.ethan.exchange.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class RedisMarketEventView implements MarketEventSink {

    private static final long MAX_EVENTS = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MarketEventStore store;

    public RedisMarketEventView(
            StringRedisTemplate redis, ObjectMapper mapper, MarketEventStore store) {
        this.redis = redis;
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    public void accept(DomainEventEnvelope event) {
        String json = write(event);
        String eventsKey = eventsKey(event.market());
        redis.opsForList().rightPush(eventsKey, json);
        redis.opsForList().trim(eventsKey, -MAX_EVENTS, -1);
        redis.opsForValue().set(sequenceKey(event.market()),
                Long.toString(event.marketSequence()));
    }

    public int rebuild(String market) {
        String normalized = normalize(market);
        List<DomainEventEnvelope> events = store.loadRecent(normalized, (int) MAX_EVENTS);
        redis.delete(List.of(eventsKey(normalized), sequenceKey(normalized)));
        for (DomainEventEnvelope event : events) {
            accept(event);
        }
        return events.size();
    }

    public List<String> recent(String market) {
        List<String> values = redis.opsForList().range(eventsKey(normalize(market)), 0, -1);
        return values == null ? List.of() : List.copyOf(values);
    }

    public long lastSequence(String market) {
        String value = redis.opsForValue().get(sequenceKey(normalize(market)));
        return value == null ? 0 : Long.parseLong(value);
    }

    private String write(DomainEventEnvelope event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new InvalidEventException("event JSON could not be written", exception);
        }
    }

    private static String eventsKey(String market) {
        return "market:" + normalize(market) + ":events";
    }

    private static String sequenceKey(String market) {
        return "market:" + normalize(market) + ":last-sequence";
    }

    private static String normalize(String market) {
        if (market == null || market.isBlank()) {
            throw new IllegalArgumentException("market is required");
        }
        return market.trim().toUpperCase(Locale.ROOT);
    }
}
