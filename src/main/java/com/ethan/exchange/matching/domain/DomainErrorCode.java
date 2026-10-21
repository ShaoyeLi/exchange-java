package com.ethan.exchange.matching.domain;

public enum DomainErrorCode {
    INVALID_ORDER,
    INVALID_MARKET,
    INVALID_NUMERIC_VALUE,
    NUMERIC_OVERFLOW,
    DUPLICATE_ORDER,
    ORDER_NOT_ACTIVE,
    INVALID_STATE_TRANSITION,
    INVALID_SNAPSHOT_DEPTH
}
