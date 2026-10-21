package com.ethan.exchange.matching.domain;

public record OrderRequest(
        long orderId,
        long userId,
        MarketSymbol market,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        Price price,
        Price protectionPrice,
        Quantity quantity
) {

    public OrderRequest {
        if (orderId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER, "orderId must be positive");
        }
        if (userId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER, "userId must be positive");
        }
        if (market == null || side == null || type == null
                || timeInForce == null || quantity == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "market, side, type, timeInForce, and quantity are required");
        }
        if (quantity.isZero()) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "order quantity must be positive");
        }
        if (type == OrderType.LIMIT && (price == null || protectionPrice != null)) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "limit order requires a price and forbids protectionPrice");
        }
        if (type == OrderType.MARKET && (price != null || protectionPrice == null)) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "market order forbids price and requires protectionPrice");
        }
        if (type == OrderType.MARKET && timeInForce != TimeInForce.IOC) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "market order supports IOC only");
        }
    }

    public static OrderRequest limit(
            long orderId,
            long userId,
            MarketSymbol market,
            OrderSide side,
            TimeInForce timeInForce,
            Price price,
            Quantity quantity
    ) {
        return new OrderRequest(orderId, userId, market, side, OrderType.LIMIT,
                timeInForce, price, null, quantity);
    }

    public static OrderRequest market(
            long orderId,
            long userId,
            MarketSymbol market,
            OrderSide side,
            Price protectionPrice,
            Quantity quantity
    ) {
        return new OrderRequest(orderId, userId, market, side, OrderType.MARKET,
                TimeInForce.IOC, null, protectionPrice, quantity);
    }

    public Price effectiveLimit() {
        return type == OrderType.LIMIT ? price : protectionPrice;
    }
}
