package com.ethan.exchange.order.application;

public enum FailurePoint {
    AFTER_DRAFT_INSERT,
    AFTER_RESERVATION,
    AFTER_TRADE_INSERT,
    AFTER_SECOND_WALLET_UPDATE,
    BEFORE_LEDGER_INSERT,
    BEFORE_OUTBOX_INSERT
}
