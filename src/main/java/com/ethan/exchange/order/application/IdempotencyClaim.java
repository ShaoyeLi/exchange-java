package com.ethan.exchange.order.application;

public record IdempotencyClaim(boolean replay, Long resultOrderId) {

    public static IdempotencyClaim fresh() {
        return new IdempotencyClaim(false, null);
    }

    public static IdempotencyClaim replay(long orderId) {
        return new IdempotencyClaim(true, orderId);
    }
}
