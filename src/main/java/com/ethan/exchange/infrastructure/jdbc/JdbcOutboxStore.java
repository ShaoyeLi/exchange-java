package com.ethan.exchange.infrastructure.jdbc;

import com.ethan.exchange.messaging.OutboxEvent;
import com.ethan.exchange.messaging.OutboxStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquireLease(
            String relayName, String ownerId, Instant now, Duration duration) {
        int changed = jdbc.update("""
                UPDATE outbox_relay_leases
                SET owner_id = ?, lease_until = ?, version = version + 1
                WHERE relay_name = ?
                  AND (owner_id = ? OR lease_until IS NULL OR lease_until <= ?)
                """, ownerId, Timestamp.from(now.plus(duration)), relayName,
                ownerId, Timestamp.from(now));
        return changed == 1;
    }

    @Override
    public List<OutboxEvent> loadReadyBatch(
            String relayName, String ownerId, Instant now, int limit) {
        return jdbc.query("""
                SELECT event.event_id, event.market_id, market.symbol AS market_symbol,
                       event.market_sequence, event.aggregate_type, event.aggregate_id,
                       event.aggregate_version, event.event_type, event.schema_version,
                       event.payload, event.attempt_count, event.created_at
                FROM outbox_events event
                JOIN markets market ON market.id = event.market_id
                WHERE event.status = 'PENDING'
                  AND event.next_attempt_at <= ?
                  AND EXISTS (
                      SELECT 1 FROM outbox_relay_leases lease
                      WHERE lease.relay_name = ? AND lease.owner_id = ? AND lease.lease_until > ?
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox_events prior
                      WHERE prior.market_id = event.market_id
                        AND prior.market_sequence < event.market_sequence
                        AND (
                            prior.status = 'DEAD'
                            OR (prior.status = 'PENDING' AND prior.next_attempt_at > ?)
                        )
                  )
                ORDER BY event.market_sequence, event.created_at, event.event_id
                LIMIT ?
                """, (result, row) -> new OutboxEvent(
                result.getString("event_id"), result.getLong("market_id"),
                result.getString("market_symbol"), result.getLong("market_sequence"),
                result.getString("aggregate_type"), result.getLong("aggregate_id"),
                result.getLong("aggregate_version"), result.getString("event_type"),
                result.getInt("schema_version"), result.getString("payload"),
                result.getInt("attempt_count"), result.getTimestamp("created_at").toInstant()),
                Timestamp.from(now), relayName, ownerId, Timestamp.from(now),
                Timestamp.from(now), limit);
    }

    @Override
    public void markPublished(
            String relayName, String ownerId, String eventId, Instant publishedAt) {
        int changed = jdbc.update("""
                UPDATE outbox_events event
                SET event.status = 'PUBLISHED', event.published_at = ?, event.last_error = NULL
                WHERE event.event_id = ? AND event.status = 'PENDING'
                  AND EXISTS (
                      SELECT 1 FROM outbox_relay_leases lease
                      WHERE lease.relay_name = ? AND lease.owner_id = ? AND lease.lease_until > ?
                  )
                """, Timestamp.from(publishedAt), eventId, relayName, ownerId,
                Timestamp.from(publishedAt));
        requireOne(changed, "outbox publish mark lost its lease");
    }

    @Override
    public void markFailed(
            String relayName,
            String ownerId,
            String eventId,
            int maxAttempts,
            Instant nextAttempt,
            String error) {
        int changed = jdbc.update("""
                UPDATE outbox_events event
                SET event.attempt_count = event.attempt_count + 1,
                    event.status = CASE
                        WHEN event.attempt_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    event.next_attempt_at = ?, event.last_error = ?
                WHERE event.event_id = ? AND event.status = 'PENDING'
                  AND EXISTS (
                      SELECT 1 FROM outbox_relay_leases lease
                      WHERE lease.relay_name = ? AND lease.owner_id = ?
                  )
                """, maxAttempts, Timestamp.from(nextAttempt), error, eventId,
                relayName, ownerId);
        requireOne(changed, "outbox failure mark lost its lease");
    }

    @Override
    public void releaseLease(String relayName, String ownerId) {
        jdbc.update("""
                UPDATE outbox_relay_leases
                SET owner_id = NULL, lease_until = NULL, version = version + 1
                WHERE relay_name = ? AND owner_id = ?
                """, relayName, ownerId);
    }

    @Override
    public boolean replayDead(String eventId) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'PENDING', attempt_count = 0,
                    next_attempt_at = CURRENT_TIMESTAMP(6), last_error = NULL,
                    published_at = NULL
                WHERE event_id = ? AND status = 'DEAD'
                """, eventId) == 1;
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new EventStoreException(message);
        }
    }
}
