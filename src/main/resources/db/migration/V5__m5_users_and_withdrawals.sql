CREATE TABLE app_users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    user_role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_users_email (email),
    CONSTRAINT ck_app_user_role CHECK (user_role IN ('TRADER', 'ADMIN'))
) ENGINE = InnoDB;

CREATE TABLE withdrawal_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    destination_address CHAR(42) NOT NULL,
    amount BIGINT NOT NULL,
    withdrawal_status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    journal_id CHAR(36) NOT NULL,
    release_journal_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_withdrawal_user_page (user_id, id),
    KEY idx_withdrawal_status_page (withdrawal_status, id),
    CONSTRAINT fk_withdrawal_user FOREIGN KEY (user_id) REFERENCES app_users (id),
    CONSTRAINT fk_withdrawal_asset FOREIGN KEY (asset_id) REFERENCES assets (id),
    CONSTRAINT fk_withdrawal_journal FOREIGN KEY (journal_id) REFERENCES ledger_journals (journal_id),
    CONSTRAINT fk_withdrawal_release_journal FOREIGN KEY (release_journal_id)
        REFERENCES ledger_journals (journal_id),
    CONSTRAINT ck_withdrawal_values CHECK (
        amount > 0 AND withdrawal_status IN (
            'REVIEW', 'APPROVED', 'BROADCASTED', 'CONFIRMED', 'REJECTED', 'CANCELED'))
) ENGINE = InnoDB;

CREATE TABLE withdrawal_idempotency (
    user_id BIGINT NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_withdrawal_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (user_id, operation_type, idempotency_key),
    CONSTRAINT fk_withdrawal_idempotency_user FOREIGN KEY (user_id) REFERENCES app_users (id),
    CONSTRAINT fk_withdrawal_idempotency_result FOREIGN KEY (result_withdrawal_id)
        REFERENCES withdrawal_requests (id),
    CONSTRAINT ck_withdrawal_idempotency_operation CHECK (
        operation_type IN ('REQUEST_WITHDRAWAL', 'CANCEL_WITHDRAWAL'))
) ENGINE = InnoDB;
