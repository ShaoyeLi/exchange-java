package com.ethan.exchange.matching.domain;

public record Trade(
        long executionSequence,
        MarketSymbol market,
        Price price,
        Quantity quantity,
        long makerOrderId,
        long takerOrderId
) {

    public Trade {
        if (executionSequence <= 0 || makerOrderId <= 0 || takerOrderId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "trade identifiers must be positive");
        }
        if (market == null || price == null || quantity == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "trade market, price, and quantity are required");
        }
        if (quantity.isZero()) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "trade quantity must be positive");
        }
    }
}
