package com.ethan.exchange.matching.domain;

public record PriceLevel(Price price, Quantity quantity) {

    public PriceLevel {
        if (price == null || quantity == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "price level requires price and quantity");
        }
        if (quantity.isZero()) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "price level quantity must be positive");
        }
    }
}
