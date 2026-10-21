package com.ethan.exchange.matching.engine;

import com.ethan.exchange.matching.domain.DuplicateOrderException;
import com.ethan.exchange.matching.domain.DomainErrorCode;
import com.ethan.exchange.matching.domain.DomainException;
import com.ethan.exchange.matching.domain.Market;
import com.ethan.exchange.matching.domain.MarketSymbol;
import com.ethan.exchange.matching.domain.MatchResult;
import com.ethan.exchange.matching.domain.OrderBookSnapshot;
import com.ethan.exchange.matching.domain.Order;
import com.ethan.exchange.matching.domain.OrderRequest;
import com.ethan.exchange.matching.domain.OrderStateMachine;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.OrderUpdate;
import com.ethan.exchange.matching.domain.Quantity;
import com.ethan.exchange.matching.domain.SelfTradePolicy;
import com.ethan.exchange.matching.domain.TimeInForce;
import com.ethan.exchange.matching.domain.Trade;
import com.ethan.exchange.matching.domain.UnknownOrderException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic matching for one market. Callers must preserve command order.
 */
public final class MatchingEngine {

    private final Market market;
    private final SelfTradePolicy selfTradePolicy;
    private final OrderBook book = new OrderBook();
    private final Set<Long> seenOrderIds = new HashSet<>();
    private long arrivalSequence;
    private long executionSequence;

    public MatchingEngine(Market market) {
        this(market, SelfTradePolicy.CANCEL_TAKER);
    }

    public MatchingEngine(Market market, SelfTradePolicy selfTradePolicy) {
        this(market, selfTradePolicy, 0, 0);
    }

    MatchingEngine(
            Market market,
            SelfTradePolicy selfTradePolicy,
            long arrivalSequence,
            long executionSequence
    ) {
        if (market == null || selfTradePolicy == null) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market and selfTradePolicy are required");
        }
        if (arrivalSequence < 0 || executionSequence < 0) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                    "matching sequences must not be negative");
        }
        this.market = market;
        this.selfTradePolicy = selfTradePolicy;
        this.arrivalSequence = arrivalSequence;
        this.executionSequence = executionSequence;
    }

    public static MatchingEngine restore(
            Market market,
            SelfTradePolicy selfTradePolicy,
            long lastArrivalSequence,
            long lastExecutionSequence,
            List<Order> activeOrders
    ) {
        if (activeOrders == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "activeOrders must not be null");
        }
        MatchingEngine engine = new MatchingEngine(
                market, selfTradePolicy, lastArrivalSequence, lastExecutionSequence);
        activeOrders.stream()
                .sorted(java.util.Comparator.comparingLong(Order::arrivalSequence))
                .forEach(engine::restoreActiveOrder);
        return engine;
    }

    public synchronized MatchResult submit(OrderRequest request) {
        if (request == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "request must not be null");
        }
        market.validate(request);
        if (seenOrderIds.contains(request.orderId())) {
            throw new DuplicateOrderException(request.orderId());
        }

        long nextArrivalSequence = incrementExact(arrivalSequence, "arrival sequence overflow");
        boolean insufficientFok = request.timeInForce() == TimeInForce.FOK
                && book.available(request.side(), request.effectiveLimit(), request.userId(),
                selfTradePolicy, market.quantityScale()).compareTo(request.quantity()) < 0;
        if (!insufficientFok) {
            ensureExecutionCapacity(request);
        }

        Order taker = Order.accept(request, nextArrivalSequence);
        seenOrderIds.add(request.orderId());
        arrivalSequence = nextArrivalSequence;
        if (insufficientFok) {
            taker.cancel();
            return new MatchResult(
                    List.of(),
                    List.of(update(taker))
            );
        }

        List<Trade> trades = new ArrayList<>();
        List<OrderUpdate> updates = new ArrayList<>();
        boolean selfTradeBlocked = match(taker, trades, updates);

        boolean rests = request.type() == OrderType.LIMIT
                && request.timeInForce() == TimeInForce.GTC
                && !taker.remaining().isZero()
                && !selfTradeBlocked;
        if (!rests && !OrderStateMachine.isTerminal(taker.status())) {
            taker.cancel();
        }
        updates.add(update(taker));

        if (rests) {
            book.add(taker);
        }
        return new MatchResult(trades, updates);
    }

    public synchronized OrderUpdate cancel(long orderId) {
        Order order = book.find(orderId);
        if (order == null) {
            throw new UnknownOrderException(orderId);
        }
        book.remove(order);
        order.cancel();
        return update(order);
    }

    public synchronized OrderUpdate cancel(MarketSymbol requestedMarket, long orderId) {
        if (!market.symbol().equals(requestedMarket)) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "engine market " + market.symbol() + " cannot cancel in " + requestedMarket);
        }
        return cancel(orderId);
    }

    public synchronized OrderBookSnapshot snapshot(int depth) {
        return book.snapshot(market, arrivalSequence, depth);
    }

    public MarketSymbol market() {
        return market.symbol();
    }

    public synchronized long lastArrivalSequence() {
        return arrivalSequence;
    }

    public synchronized long lastExecutionSequence() {
        return executionSequence;
    }

    private void restoreActiveOrder(Order order) {
        if (order == null || !market.symbol().equals(order.market())
                || OrderStateMachine.isTerminal(order.status())
                || order.arrivalSequence() > arrivalSequence
                || !seenOrderIds.add(order.orderId())) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "invalid or duplicate active order during matching-engine restoration");
        }
        book.add(order);
    }

    private boolean match(Order taker, List<Trade> trades, List<OrderUpdate> updates) {
        while (!taker.remaining().isZero()) {
            Order maker = book.bestOpposite(taker.request().side());
            if (maker == null || !OrderBook.crosses(
                    taker.request().side(), taker.request().effectiveLimit(), maker.request().price())) {
                return false;
            }
            if (selfTradePolicy == SelfTradePolicy.CANCEL_TAKER
                    && maker.request().userId() == taker.request().userId()) {
                return true;
            }

            Quantity fill = Quantity.min(taker.remaining(), maker.remaining());
            long nextExecutionSequence = incrementExact(
                    executionSequence, "execution sequence overflow");
            Trade trade = new Trade(
                    nextExecutionSequence,
                    market.symbol(),
                    maker.request().price(),
                    fill,
                    maker.request().orderId(),
                    taker.request().orderId()
            );
            taker.fill(fill);
            maker.fill(fill);
            executionSequence = nextExecutionSequence;
            trades.add(trade);
            updates.add(update(maker));
            if (maker.remaining().isZero()) {
                book.removeFilledMaker(maker);
            }
        }
        return false;
    }

    private void ensureExecutionCapacity(OrderRequest request) {
        long matches = book.potentialMatchCount(request, selfTradePolicy);
        try {
            Math.addExact(executionSequence, matches);
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                    "execution sequence range exhausted for command", exception);
        }
    }

    private OrderUpdate update(Order order) {
        return new OrderUpdate(
                order.request().orderId(),
                order.status(),
                order.filled(),
                order.remaining()
        );
    }

    private static long incrementExact(long value, String message) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW, message, exception);
        }
    }

}
