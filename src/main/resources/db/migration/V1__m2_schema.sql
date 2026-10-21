CREATE TABLE assets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(16) NOT NULL,
    decimal_scale INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assets_symbol (symbol),
    CONSTRAINT ck_assets_scale CHECK (decimal_scale BETWEEN 0 AND 18)
) ENGINE = InnoDB;

CREATE TABLE markets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    base_asset_id BIGINT NOT NULL,
    quote_asset_id BIGINT NOT NULL,
    fee_wallet_user_id BIGINT NOT NULL,
    price_scale INT NOT NULL,
    quantity_scale INT NOT NULL,
    price_tick BIGINT NOT NULL,
    quantity_step BIGINT NOT NULL,
    minimum_quantity BIGINT NOT NULL,
    minimum_notional BIGINT NOT NULL,
    maker_fee_bps INT NOT NULL,
    taker_fee_bps INT NOT NULL,
    last_arrival_sequence BIGINT NOT NULL DEFAULT 0,
    last_execution_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_markets_symbol (symbol),
    CONSTRAINT fk_markets_base_asset FOREIGN KEY (base_asset_id) REFERENCES assets (id),
    CONSTRAINT fk_markets_quote_asset FOREIGN KEY (quote_asset_id) REFERENCES assets (id),
    CONSTRAINT ck_markets_assets CHECK (base_asset_id <> quote_asset_id),
    CONSTRAINT ck_markets_scales CHECK (price_scale BETWEEN 0 AND 18 AND quantity_scale BETWEEN 0 AND 18),
    CONSTRAINT ck_markets_steps CHECK (price_tick > 0 AND quantity_step > 0),
    CONSTRAINT ck_markets_minimums CHECK (minimum_quantity > 0 AND minimum_notional > 0),
    CONSTRAINT ck_markets_fees CHECK (maker_fee_bps BETWEEN 0 AND 10000 AND taker_fee_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_markets_sequences CHECK (last_arrival_sequence >= 0 AND last_execution_sequence >= 0)
) ENGINE = InnoDB;

CREATE TABLE wallets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    available BIGINT NOT NULL DEFAULT 0,
    locked BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallets_user_asset (user_id, asset_id),
    CONSTRAINT fk_wallets_asset FOREIGN KEY (asset_id) REFERENCES assets (id),
    CONSTRAINT ck_wallets_user CHECK (user_id >= 0),
    CONSTRAINT ck_wallets_balances CHECK (available >= 0 AND locked >= 0),
    CONSTRAINT ck_wallets_version CHECK (version >= 0)
) ENGINE = InnoDB;

CREATE TABLE idempotency_records (
    user_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_order_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (user_id, operation_type, idempotency_key),
    KEY idx_idempotency_result_order (result_order_id)
) ENGINE = InnoDB;

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    market_id BIGINT NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    time_in_force VARCHAR(8) NOT NULL,
    status VARCHAR(24) NOT NULL,
    price BIGINT NULL,
    protection_price BIGINT NULL,
    original_quantity BIGINT NOT NULL,
    remaining_quantity BIGINT NOT NULL,
    arrival_sequence BIGINT NOT NULL,
    reserved_base_remaining BIGINT NOT NULL DEFAULT 0,
    reserved_quote_remaining BIGINT NOT NULL DEFAULT 0,
    cumulative_maker_quote BIGINT NOT NULL DEFAULT 0,
    cumulative_taker_quote BIGINT NOT NULL DEFAULT 0,
    cumulative_fee_charged BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_market_arrival (market_id, arrival_sequence),
    KEY idx_orders_active_book (market_id, status, side, price, arrival_sequence),
    KEY idx_orders_user_status_created (user_id, status, created_at, id),
    CONSTRAINT fk_orders_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT ck_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_orders_type CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT ck_orders_tif CHECK (time_in_force IN ('GTC', 'IOC', 'FOK')),
    CONSTRAINT ck_orders_status CHECK (status IN ('NEW', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED')),
    CONSTRAINT ck_orders_price_shape CHECK (
        (order_type = 'LIMIT' AND price IS NOT NULL AND price > 0 AND protection_price IS NULL)
        OR (order_type = 'MARKET' AND price IS NULL AND protection_price IS NOT NULL AND protection_price > 0)
    ),
    CONSTRAINT ck_orders_quantities CHECK (
        original_quantity > 0 AND remaining_quantity >= 0 AND remaining_quantity <= original_quantity
    ),
    CONSTRAINT ck_orders_active_remaining CHECK (
        (status IN ('NEW', 'PARTIALLY_FILLED') AND remaining_quantity > 0)
        OR (status = 'FILLED' AND remaining_quantity = 0)
        OR status IN ('CANCELED', 'REJECTED')
    ),
    CONSTRAINT ck_orders_reservations CHECK (reserved_base_remaining >= 0 AND reserved_quote_remaining >= 0),
    CONSTRAINT ck_orders_cumulative CHECK (
        cumulative_maker_quote >= 0 AND cumulative_taker_quote >= 0 AND cumulative_fee_charged >= 0
    ),
    CONSTRAINT ck_orders_sequence_version CHECK (arrival_sequence > 0 AND version >= 0)
) ENGINE = InnoDB;

ALTER TABLE idempotency_records
    ADD CONSTRAINT fk_idempotency_result_order FOREIGN KEY (result_order_id) REFERENCES orders (id);

CREATE TABLE trades (
    id BIGINT NOT NULL AUTO_INCREMENT,
    market_id BIGINT NOT NULL,
    execution_sequence BIGINT NOT NULL,
    maker_order_id BIGINT NOT NULL,
    taker_order_id BIGINT NOT NULL,
    price BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    quote_quantity BIGINT NOT NULL,
    maker_fee BIGINT NOT NULL,
    taker_fee BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_trades_market_execution (market_id, execution_sequence),
    KEY idx_trades_maker_order (maker_order_id, id),
    KEY idx_trades_taker_order (taker_order_id, id),
    CONSTRAINT fk_trades_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT fk_trades_maker_order FOREIGN KEY (maker_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_taker_order FOREIGN KEY (taker_order_id) REFERENCES orders (id),
    CONSTRAINT ck_trades_values CHECK (
        execution_sequence > 0 AND price > 0 AND quantity > 0 AND quote_quantity > 0
        AND maker_fee >= 0 AND taker_fee >= 0
    )
) ENGINE = InnoDB;

CREATE TABLE ledger_journals (
    journal_id CHAR(36) NOT NULL,
    journal_type VARCHAR(24) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (journal_id),
    UNIQUE KEY uk_ledger_journal_business (journal_type, business_ref)
) ENGINE = InnoDB;

CREATE TABLE ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    journal_id CHAR(36) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    wallet_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    posting_type VARCHAR(48) NOT NULL,
    delta_available BIGINT NOT NULL,
    delta_locked BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_posting (business_ref, wallet_id, posting_type),
    KEY idx_ledger_wallet_page (wallet_id, id),
    KEY idx_ledger_journal (journal_id, id),
    CONSTRAINT fk_ledger_journal FOREIGN KEY (journal_id) REFERENCES ledger_journals (journal_id),
    CONSTRAINT fk_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_ledger_asset FOREIGN KEY (asset_id) REFERENCES assets (id),
    CONSTRAINT ck_ledger_nonzero CHECK (delta_available <> 0 OR delta_locked <> 0)
) ENGINE = InnoDB;

CREATE TABLE outbox_events (
    event_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (event_id),
    KEY idx_outbox_unpublished (published_at, created_at, event_id)
) ENGINE = InnoDB;

