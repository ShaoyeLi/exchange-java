package com.ethan.exchange.order.application;

import com.ethan.exchange.matching.domain.OrderStatus;

public record OrderCommandResult(
        long orderId,
        OrderStatus status,
        long remainingQuantity,
        long reservedBaseRemaining,
        long reservedQuoteRemaining,
        boolean replayed
) {

    public static OrderCommandResult from(StoredOrder order, boolean replayed) {
        return new OrderCommandResult(
                order.orderId(), order.status(), order.remainingQuantity(),
                order.reservedBaseRemaining(), order.reservedQuoteRemaining(), replayed);
    }
}
