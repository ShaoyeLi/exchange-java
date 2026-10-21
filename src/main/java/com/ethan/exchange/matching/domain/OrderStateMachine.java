package com.ethan.exchange.matching.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            OrderStatus.NEW, EnumSet.of(
                    OrderStatus.PARTIALLY_FILLED,
                    OrderStatus.FILLED,
                    OrderStatus.CANCELED,
                    OrderStatus.REJECTED),
            OrderStatus.PARTIALLY_FILLED, EnumSet.of(
                    OrderStatus.FILLED,
                    OrderStatus.CANCELED),
            OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class)
    );

    private OrderStateMachine() {
    }

    public static void requireTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null || !ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "illegal order state transition: " + from + " -> " + to);
        }
    }

    public static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED
                || status == OrderStatus.REJECTED;
    }
}
