package com.ethan.exchange.matching.engine;

import com.ethan.exchange.matching.domain.OrderBookSnapshot;
import com.ethan.exchange.matching.domain.DomainErrorCode;
import com.ethan.exchange.matching.domain.DomainException;
import com.ethan.exchange.matching.domain.Market;
import com.ethan.exchange.matching.domain.Order;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderRequest;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.PriceLevel;
import com.ethan.exchange.matching.domain.Quantity;
import com.ethan.exchange.matching.domain.SelfTradePolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class OrderBook {

    private final NavigableMap<Price, Deque<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Price, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> activeOrders = new HashMap<>();

    void add(Order order) {
        NavigableMap<Price, Deque<Order>> side = levels(order.request().side());
        side.computeIfAbsent(order.request().price(), ignored -> new ArrayDeque<>())
                .addLast(order);
        activeOrders.put(order.request().orderId(), order);
    }

    Order find(long orderId) {
        return activeOrders.get(orderId);
    }

    void remove(Order order) {
        NavigableMap<Price, Deque<Order>> side = levels(order.request().side());
        Deque<Order> level = side.get(order.request().price());
        if (level == null || !level.remove(order)) {
            throw new IllegalStateException("order index and price level disagree");
        }
        activeOrders.remove(order.request().orderId());
        if (level.isEmpty()) {
            side.remove(order.request().price());
        }
    }

    Order bestOpposite(OrderSide takerSide) {
        NavigableMap<Price, Deque<Order>> opposite = oppositeLevels(takerSide);
        return opposite.isEmpty() ? null : opposite.firstEntry().getValue().getFirst();
    }

    void removeFilledMaker(Order maker) {
        NavigableMap<Price, Deque<Order>> side = levels(maker.request().side());
        Deque<Order> level = side.get(maker.request().price());
        if (level == null || level.peekFirst() != maker) {
            throw new IllegalStateException("filled maker is not first at its price level");
        }
        level.removeFirst();
        activeOrders.remove(maker.request().orderId());
        if (level.isEmpty()) {
            side.remove(maker.request().price());
        }
    }

    Quantity available(OrderSide takerSide, Price limit, long takerUserId,
                       SelfTradePolicy selfTradePolicy,
                       int quantityScale) {
        long available = 0;
        for (Map.Entry<Price, Deque<Order>> entry : oppositeLevels(takerSide).entrySet()) {
            if (!crosses(takerSide, limit, entry.getKey())) {
                break;
            }
            for (Order order : entry.getValue()) {
                if (selfTradePolicy == SelfTradePolicy.CANCEL_TAKER
                        && order.request().userId() == takerUserId) {
                    return new Quantity(available, quantityScale);
                }
                try {
                    available = Math.addExact(available, order.remaining().unscaled());
                } catch (ArithmeticException exception) {
                    throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                            "available book quantity exceeds fixed-point range", exception);
                }
            }
        }
        return new Quantity(available, quantityScale);
    }

    long potentialMatchCount(OrderRequest taker, SelfTradePolicy selfTradePolicy) {
        long remaining = taker.quantity().unscaled();
        long count = 0;
        for (Map.Entry<Price, Deque<Order>> entry : oppositeLevels(taker.side()).entrySet()) {
            if (!crosses(taker.side(), taker.effectiveLimit(), entry.getKey())) {
                break;
            }
            for (Order maker : entry.getValue()) {
                if (selfTradePolicy == SelfTradePolicy.CANCEL_TAKER
                        && maker.userId() == taker.userId()) {
                    return count;
                }
                if (remaining == 0) {
                    return count;
                }
                count = incrementExact(count, "potential execution count overflow");
                remaining -= Math.min(remaining, maker.remaining().unscaled());
            }
        }
        return count;
    }

    OrderBookSnapshot snapshot(
            Market market,
            long lastArrivalSequence,
            int depth
    ) {
        if (depth <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_SNAPSHOT_DEPTH,
                    "snapshot depth must be positive");
        }
        return new OrderBookSnapshot(
                market.symbol(),
                lastArrivalSequence,
                aggregate(bids, depth, market.quantityScale()),
                aggregate(asks, depth, market.quantityScale())
        );
    }

    static boolean crosses(OrderSide takerSide, Price takerLimit, Price makerPrice) {
        if (takerLimit == null) {
            return true;
        }
        return takerSide == OrderSide.BUY
                ? makerPrice.compareTo(takerLimit) <= 0
                : makerPrice.compareTo(takerLimit) >= 0;
    }

    private NavigableMap<Price, Deque<Order>> levels(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    private NavigableMap<Price, Deque<Order>> oppositeLevels(OrderSide takerSide) {
        return takerSide == OrderSide.BUY ? asks : bids;
    }

    private List<PriceLevel> aggregate(
            NavigableMap<Price, Deque<Order>> levels,
            int depth,
            int quantityScale
    ) {
        List<PriceLevel> result = new ArrayList<>(Math.min(depth, levels.size()));
        for (Map.Entry<Price, Deque<Order>> entry : levels.entrySet()) {
            if (result.size() == depth) {
                break;
            }
            long total = 0;
            for (Order order : entry.getValue()) {
                try {
                    total = Math.addExact(total, order.remaining().unscaled());
                } catch (ArithmeticException exception) {
                    throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                            "snapshot quantity exceeds fixed-point range", exception);
                }
            }
            if (total > 0) {
                result.add(new PriceLevel(entry.getKey(), new Quantity(total, quantityScale)));
            }
        }
        return result;
    }

    private static long incrementExact(long value, String message) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW, message, exception);
        }
    }
}
