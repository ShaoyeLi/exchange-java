package com.ethan.exchange.matching.domain;

public final class DuplicateOrderException extends DomainException {

    private static final long serialVersionUID = 1L;

    public DuplicateOrderException(long orderId) {
        super(DomainErrorCode.DUPLICATE_ORDER,
                "orderId has already been submitted: " + orderId);
    }
}
