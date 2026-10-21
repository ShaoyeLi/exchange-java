package com.ethan.exchange.order.application;

import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.TimeInForce;

import java.util.Locale;

public record PlaceOrderCommand(
        long userId,
        String market,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        Long price,
        Long protectionPrice,
        long quantity,
        String idempotencyKey
) {

    public PlaceOrderCommand {
        if (userId <= 0 || market == null || market.isBlank()
                || side == null || type == null || timeInForce == null
                || quantity <= 0 || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("place-order command fields are invalid");
        }
        market = market.trim().toUpperCase(Locale.ROOT);
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must contain at most 128 characters");
        }
    }

    public String requestHash() {
        return RequestHasher.sha256(String.join("|",
                Long.toString(userId), market, side.name(), type.name(), timeInForce.name(),
                String.valueOf(price), String.valueOf(protectionPrice), Long.toString(quantity)));
    }
}
