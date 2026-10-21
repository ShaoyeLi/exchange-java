CREATE TABLE chain_networks (
    chain_id BIGINT NOT NULL,
    network_name VARCHAR(64) NOT NULL,
    rpc_url VARCHAR(512) NOT NULL,
    confirmation_depth INT NOT NULL,
    scan_batch_size INT NOT NULL,
    reorg_lookback INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (chain_id),
    CONSTRAINT ck_chain_network_values CHECK (
        chain_id > 0 AND confirmation_depth >= 0 AND scan_batch_size BETWEEN 1 AND 10000
        AND reorg_lookback > 0)
) ENGINE = InnoDB;

CREATE TABLE chain_sync_state (
    chain_id BIGINT NOT NULL,
    next_block BIGINT NOT NULL,
    sync_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    blocked_reason VARCHAR(512) NULL,
    owner_id VARCHAR(128) NULL,
    lease_until TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (chain_id),
    CONSTRAINT fk_chain_sync_network FOREIGN KEY (chain_id) REFERENCES chain_networks (chain_id),
    CONSTRAINT ck_chain_sync_values CHECK (
        next_block >= 0 AND version >= 0 AND sync_status IN ('RUNNING', 'BLOCKED'))
) ENGINE = InnoDB;

CREATE TABLE chain_blocks (
    chain_id BIGINT NOT NULL,
    block_number BIGINT NOT NULL,
    block_hash CHAR(66) NOT NULL,
    parent_hash CHAR(66) NOT NULL,
    block_timestamp TIMESTAMP(6) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (chain_id, block_number),
    UNIQUE KEY uk_chain_block_hash (chain_id, block_hash),
    CONSTRAINT fk_chain_block_network FOREIGN KEY (chain_id) REFERENCES chain_networks (chain_id),
    CONSTRAINT ck_chain_block_number CHECK (block_number >= 0)
) ENGINE = InnoDB;

CREATE TABLE chain_token_contracts (
    chain_id BIGINT NOT NULL,
    contract_address CHAR(42) NOT NULL,
    asset_id BIGINT NOT NULL,
    token_decimals INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (chain_id, contract_address),
    KEY idx_chain_token_asset (asset_id),
    CONSTRAINT fk_chain_token_network FOREIGN KEY (chain_id) REFERENCES chain_networks (chain_id),
    CONSTRAINT fk_chain_token_asset FOREIGN KEY (asset_id) REFERENCES assets (id),
    CONSTRAINT ck_chain_token_decimals CHECK (token_decimals BETWEEN 0 AND 77)
) ENGINE = InnoDB;

CREATE TABLE deposit_addresses (
    chain_id BIGINT NOT NULL,
    address CHAR(42) NOT NULL,
    user_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (chain_id, address),
    KEY idx_deposit_address_user (user_id, chain_id),
    CONSTRAINT fk_deposit_address_network FOREIGN KEY (chain_id) REFERENCES chain_networks (chain_id),
    CONSTRAINT ck_deposit_address_user CHECK (user_id > 0)
) ENGINE = InnoDB;

CREATE TABLE chain_deposits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chain_id BIGINT NOT NULL,
    transaction_hash CHAR(66) NOT NULL,
    log_index BIGINT NOT NULL,
    block_number BIGINT NOT NULL,
    block_hash CHAR(66) NOT NULL,
    token_address CHAR(42) NOT NULL,
    from_address CHAR(42) NOT NULL,
    to_address CHAR(42) NOT NULL,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    raw_amount VARCHAR(78) NOT NULL,
    internal_amount BIGINT NULL,
    deposit_status VARCHAR(20) NOT NULL,
    journal_id CHAR(36) NULL,
    observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    credited_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chain_deposit_log (chain_id, transaction_hash, log_index),
    KEY idx_chain_deposit_confirmation (chain_id, deposit_status, block_number, id),
    KEY idx_chain_deposit_user (user_id, id),
    CONSTRAINT fk_chain_deposit_network FOREIGN KEY (chain_id) REFERENCES chain_networks (chain_id),
    CONSTRAINT fk_chain_deposit_asset FOREIGN KEY (asset_id) REFERENCES assets (id),
    CONSTRAINT fk_chain_deposit_address FOREIGN KEY (chain_id, to_address)
        REFERENCES deposit_addresses (chain_id, address),
    CONSTRAINT fk_chain_deposit_journal FOREIGN KEY (journal_id) REFERENCES ledger_journals (journal_id),
    CONSTRAINT ck_chain_deposit_values CHECK (
        log_index >= 0 AND block_number >= 0 AND raw_amount REGEXP '^[0-9]{1,78}$'
        AND (internal_amount IS NULL OR internal_amount > 0)
        AND deposit_status IN ('SEEN', 'CREDITED', 'ORPHANED', 'IGNORED', 'REVIEW'))
) ENGINE = InnoDB;
