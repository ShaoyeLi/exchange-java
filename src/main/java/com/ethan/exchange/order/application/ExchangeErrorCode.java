package com.ethan.exchange.order.application;

public enum ExchangeErrorCode {
    MARKET_NOT_FOUND,
    ORDER_NOT_FOUND,
    ORDER_NOT_ACTIVE,
    ORDER_NOT_OWNED,
    IDEMPOTENCY_CONFLICT,
    INSUFFICIENT_BALANCE,
    NUMERIC_OVERFLOW,
    CONCURRENT_UPDATE,
    INJECTED_FAILURE
}
