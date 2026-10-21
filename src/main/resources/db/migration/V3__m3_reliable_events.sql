CREATE TABLE market_event_sequences (
    market_id BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (market_id),
    CONSTRAINT fk_market_event_sequence_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT ck_market_event_sequence_nonnegative CHECK (last_sequence >= 0)
) ENGINE = InnoDB;

ALTER TABLE outbox_events
    ADD COLUMN market_id BIGINT NULL AFTER event_id,
    ADD COLUMN market_sequence BIGINT NULL AFTER market_id,
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0 AFTER aggregate_id,
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1 AFTER event_type,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER payload,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER attempt_count,
    ADD COLUMN last_error VARCHAR(512) NULL AFTER next_attempt_at;

UPDATE outbox_events event
JOIN orders source_order ON source_order.id = event.aggregate_id
SET event.market_id = source_order.market_id
WHERE event.market_id IS NULL;

CREATE TEMPORARY TABLE legacy_outbox_sequences AS
SELECT event_id,
       ROW_NUMBER() OVER (PARTITION BY market_id ORDER BY created_at, event_id) AS market_sequence
FROM outbox_events;

UPDATE outbox_events event
JOIN legacy_outbox_sequences legacy ON legacy.event_id = event.event_id
SET event.market_sequence = legacy.market_sequence
WHERE event.market_sequence IS NULL;

INSERT INTO market_event_sequences (market_id, last_sequence)
SELECT market_id, MAX(market_sequence)
FROM outbox_events
WHERE market_id IS NOT NULL
GROUP BY market_id;

DROP TEMPORARY TABLE legacy_outbox_sequences;

ALTER TABLE outbox_events
    MODIFY market_id BIGINT NOT NULL,
    MODIFY market_sequence BIGINT NOT NULL,
    ADD CONSTRAINT fk_outbox_market FOREIGN KEY (market_id) REFERENCES markets (id),
    ADD CONSTRAINT ck_outbox_sequence CHECK (market_sequence > 0),
    ADD CONSTRAINT ck_outbox_version CHECK (aggregate_version >= 0 AND schema_version > 0),
    ADD CONSTRAINT ck_outbox_attempt CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    ADD UNIQUE KEY uk_outbox_market_sequence (market_id, market_sequence),
    ADD KEY idx_outbox_dispatch (status, next_attempt_at, created_at, event_id);

UPDATE outbox_events
SET status = 'PUBLISHED'
WHERE published_at IS NOT NULL;

CREATE TABLE outbox_relay_leases (
    relay_name VARCHAR(64) NOT NULL,
    owner_id VARCHAR(128) NULL,
    lease_until TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (relay_name),
    CONSTRAINT ck_relay_lease_version CHECK (version >= 0)
) ENGINE = InnoDB;

INSERT INTO outbox_relay_leases (relay_name) VALUES ('market-events');

CREATE TABLE inbox_events (
    consumer_name VARCHAR(128) NOT NULL,
    event_id CHAR(36) NOT NULL,
    topic_name VARCHAR(128) NOT NULL,
    partition_id INT NOT NULL,
    offset_value BIGINT NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_name, event_id),
    KEY idx_inbox_topic_position (topic_name, partition_id, offset_value),
    CONSTRAINT ck_inbox_position CHECK (partition_id >= 0 AND offset_value >= 0)
) ENGINE = InnoDB;

CREATE TABLE market_event_projection (
    event_id CHAR(36) NOT NULL,
    market_id BIGINT NOT NULL,
    market_sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    envelope JSON NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_projection_market_sequence (market_id, market_sequence),
    KEY idx_projection_market_page (market_id, market_sequence),
    CONSTRAINT fk_projection_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT ck_projection_sequence CHECK (market_sequence > 0)
) ENGINE = InnoDB;
