package com.ethan.exchange.order.application;

import java.util.Locale;

public record CancelOrderCommand(
        long userId,
        String market,
        long orderId,
        String idempotencyKey
) {

    public CancelOrderCommand {
        if (userId <= 0 || orderId <= 0 || market == null || market.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("cancel-order command fields are invalid");
        }
        market = market.trim().toUpperCase(Locale.ROOT);
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must contain at most 128 characters");
        }
    }

    public String requestHash() {
        return RequestHasher.sha256(String.join("|",
                Long.toString(userId), market, Long.toString(orderId)));
    }
}
