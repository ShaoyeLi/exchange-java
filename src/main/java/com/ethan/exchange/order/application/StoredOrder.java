package com.ethan.exchange.order.application;

import com.ethan.exchange.matching.domain.Order;
import com.ethan.exchange.matching.domain.OrderRequest;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderStatus;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.OrderUpdate;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.Quantity;
import com.ethan.exchange.matching.domain.TimeInForce;

public record StoredOrder(
        long orderId,
        long userId,
        long marketId,
        String marketSymbol,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        OrderStatus status,
        Long price,
        Long protectionPrice,
        long originalQuantity,
        long remainingQuantity,
        long arrivalSequence,
        long reservedBaseRemaining,
        long reservedQuoteRemaining,
        long cumulativeMakerQuote,
        long cumulativeTakerQuote,
        long cumulativeFeeCharged,
        String idempotencyKey,
        String requestHash,
        long version
) {

    public OrderRequest toRequest(MarketState market) {
        Price limit = price == null ? null : Price.of(price, market.priceScale());
        Price protection = protectionPrice == null
                ? null
                : Price.of(protectionPrice, market.priceScale());
        return new OrderRequest(
                orderId,
                userId,
                market.toDomain().symbol(),
                side,
                type,
                timeInForce,
                limit,
                protection,
                Quantity.of(originalQuantity, market.quantityScale())
        );
    }

    public Order restore(MarketState market) {
        return Order.restore(
                toRequest(market),
                Quantity.of(remainingQuantity, market.quantityScale()),
                status,
                arrivalSequence
        );
    }

    public StoredOrder apply(OrderUpdate update) {
        if (update.orderId() != orderId) {
            throw new IllegalArgumentException("order update targets another order");
        }
        return new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                update.status(), price, protectionPrice, originalQuantity,
                update.remaining().unscaled(), arrivalSequence,
                reservedBaseRemaining, reservedQuoteRemaining,
                cumulativeMakerQuote, cumulativeTakerQuote, cumulativeFeeCharged,
                idempotencyKey, requestHash, version
        );
    }

    public StoredOrder withReservation(long reservedBase, long reservedQuote) {
        return new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                status, price, protectionPrice, originalQuantity, remainingQuantity,
                arrivalSequence, reservedBase, reservedQuote,
                cumulativeMakerQuote, cumulativeTakerQuote, cumulativeFeeCharged,
                idempotencyKey, requestHash, version
        );
    }

    public FeeCharge charge(OrderRole role, long tradeQuote, MarketState market) {
        long nextMaker = role == OrderRole.MAKER
                ? ExactMath.add(cumulativeMakerQuote, tradeQuote)
                : cumulativeMakerQuote;
        long nextTaker = role == OrderRole.TAKER
                ? ExactMath.add(cumulativeTakerQuote, tradeQuote)
                : cumulativeTakerQuote;
        long nextTotalFee = ExactMath.cumulativeFee(
                nextMaker, market.makerFeeBps(), nextTaker, market.takerFeeBps());
        long feeDelta = ExactMath.subtract(nextTotalFee, cumulativeFeeCharged);
        StoredOrder next = new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                status, price, protectionPrice, originalQuantity, remainingQuantity,
                arrivalSequence, reservedBaseRemaining, reservedQuoteRemaining,
                nextMaker, nextTaker, nextTotalFee,
                idempotencyKey, requestHash, version
        );
        return new FeeCharge(next, feeDelta);
    }

    public StoredOrder withVersion(long nextVersion) {
        return new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                status, price, protectionPrice, originalQuantity, remainingQuantity,
                arrivalSequence, reservedBaseRemaining, reservedQuoteRemaining,
                cumulativeMakerQuote, cumulativeTakerQuote, cumulativeFeeCharged,
                idempotencyKey, requestHash, nextVersion
        );
    }

    public StoredOrder fill(long quantity) {
        long nextRemaining = ExactMath.subtract(remainingQuantity, quantity);
        if (quantity <= 0 || nextRemaining < 0) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "trade quantity exceeds persisted order remaining");
        }
        OrderStatus nextStatus = nextRemaining == 0
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;
        return new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                nextStatus, price, protectionPrice, originalQuantity, nextRemaining,
                arrivalSequence, reservedBaseRemaining, reservedQuoteRemaining,
                cumulativeMakerQuote, cumulativeTakerQuote, cumulativeFeeCharged,
                idempotencyKey, requestHash, version
        );
    }

    public StoredOrder cancelAndClearReservation() {
        return new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                OrderStatus.CANCELED, price, protectionPrice, originalQuantity, remainingQuantity,
                arrivalSequence, 0, 0,
                cumulativeMakerQuote, cumulativeTakerQuote, cumulativeFeeCharged,
                idempotencyKey, requestHash, version
        );
    }

    public StoredOrder clearReservation() {
        return new StoredOrder(
                orderId, userId, marketId, marketSymbol, side, type, timeInForce,
                status, price, protectionPrice, originalQuantity, remainingQuantity,
                arrivalSequence, 0, 0,
                cumulativeMakerQuote, cumulativeTakerQuote, cumulativeFeeCharged,
                idempotencyKey, requestHash, version
        );
    }

    public long effectivePrice() {
        return type == OrderType.LIMIT ? price : protectionPrice;
    }

    public record FeeCharge(StoredOrder order, long feeDelta) {
    }
}
