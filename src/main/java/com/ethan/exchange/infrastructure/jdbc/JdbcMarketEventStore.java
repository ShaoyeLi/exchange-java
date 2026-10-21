package com.ethan.exchange.infrastructure.jdbc;

import com.ethan.exchange.messaging.DomainEventEnvelope;
import com.ethan.exchange.messaging.MarketEventStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcMarketEventStore implements MarketEventStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcMarketEventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean claimInbox(
            String consumerName, String eventId, String topic, int partition, long offset) {
        try {
            jdbc.update("""
                    INSERT INTO inbox_events (
                        consumer_name, event_id, topic_name, partition_id, offset_value)
                    VALUES (?, ?, ?, ?, ?)
                    """, consumerName, eventId, topic, partition, offset);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public void insertProjection(DomainEventEnvelope event) {
        Long marketId;
        try {
            marketId = jdbc.queryForObject(
                    "SELECT id FROM markets WHERE symbol = ?", Long.class, event.market());
        } catch (EmptyResultDataAccessException exception) {
            throw new EventStoreException("event references an unknown market");
        }
        if (marketId == null) {
            throw new EventStoreException("event market ID was not returned");
        }
        try {
            jdbc.update("""
                    INSERT INTO market_event_projection (
                        event_id, market_id, market_sequence, event_type, envelope, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, event.eventId(), marketId, event.marketSequence(), event.type(),
                    mapper.writeValueAsString(event), java.sql.Timestamp.from(event.occurredAt()));
        } catch (JsonProcessingException exception) {
            throw new EventStoreException("event envelope could not be serialized");
        }
    }

    @Override
    public List<DomainEventEnvelope> load(String market, long afterSequence, int limit) {
        return jdbc.query("""
                SELECT projection.envelope
                FROM market_event_projection projection
                JOIN markets market ON market.id = projection.market_id
                WHERE market.symbol = ? AND projection.market_sequence > ?
                ORDER BY projection.market_sequence
                LIMIT ?
                """, (result, row) -> read(result.getString("envelope")),
                market, afterSequence, limit);
    }

    @Override
    public List<DomainEventEnvelope> loadRecent(String market, int limit) {
        return jdbc.query("""
                SELECT recent.envelope
                FROM (
                    SELECT projection.envelope, projection.market_sequence
                    FROM market_event_projection projection
                    JOIN markets market ON market.id = projection.market_id
                    WHERE market.symbol = ?
                    ORDER BY projection.market_sequence DESC
                    LIMIT ?
                ) recent
                ORDER BY recent.market_sequence
                """, (result, row) -> read(result.getString("envelope")), market, limit);
    }

    @Override
    public long lastSequence(String market) {
        Long result = jdbc.queryForObject("""
                SELECT COALESCE(MAX(projection.market_sequence), 0)
                FROM markets market
                LEFT JOIN market_event_projection projection ON projection.market_id = market.id
                WHERE market.symbol = ?
                """, Long.class, market);
        return result == null ? 0 : result;
    }

    private DomainEventEnvelope read(String json) {
        try {
            return mapper.readValue(json, DomainEventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new EventStoreException("persisted event envelope is invalid");
        }
    }
}
