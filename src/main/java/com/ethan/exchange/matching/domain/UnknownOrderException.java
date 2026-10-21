package com.ethan.exchange.matching.domain;

public final class UnknownOrderException extends DomainException {

    private static final long serialVersionUID = 1L;

    public UnknownOrderException(long orderId) {
        super(DomainErrorCode.ORDER_NOT_ACTIVE, "active order not found: " + orderId);
    }
}
