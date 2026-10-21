package com.ethan.exchange.matching.domain;

public record OrderUpdate(
        long orderId,
        OrderStatus status,
        Quantity filled,
        Quantity remaining
) {

    public OrderUpdate {
        if (orderId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER, "orderId must be positive");
        }
        if (status == null || filled == null || remaining == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "status, filled, and remaining are required");
        }
        if (filled.scale() != remaining.scale()) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "filled and remaining scales must match");
        }
    }
}
