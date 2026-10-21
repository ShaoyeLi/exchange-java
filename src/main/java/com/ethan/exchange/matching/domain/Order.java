package com.ethan.exchange.matching.domain;

/**
 * An accepted order and its mutable matching state. The request and arrival
 * sequence never change; only the remaining quantity and legal status advance.
 */
public final class Order {

    private final OrderRequest request;
    private final long arrivalSequence;
    private Quantity remaining;
    private OrderStatus status;

    private Order(OrderRequest request, long arrivalSequence) {
        if (request == null || arrivalSequence <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "accepted order requires a request and positive arrival sequence");
        }
        this.request = request;
        this.arrivalSequence = arrivalSequence;
        this.remaining = request.quantity();
        this.status = OrderStatus.NEW;
    }

    public static Order accept(OrderRequest request, long arrivalSequence) {
        return new Order(request, arrivalSequence);
    }

    public static Order restore(
            OrderRequest request,
            Quantity remaining,
            OrderStatus status,
            long arrivalSequence
    ) {
        Order order = new Order(request, arrivalSequence);
        if (remaining == null || status == null
                || request.type() != OrderType.LIMIT
                || request.timeInForce() != TimeInForce.GTC
                || remaining.isZero()
                || remaining.compareTo(request.quantity()) > 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "restored order must be an active LIMIT GTC order with valid remaining quantity");
        }
        boolean untouched = remaining.equals(request.quantity());
        if ((untouched && status != OrderStatus.NEW)
                || (!untouched && status != OrderStatus.PARTIALLY_FILLED)) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "restored status does not agree with original and remaining quantity");
        }
        order.remaining = remaining;
        order.status = status;
        return order;
    }

    public long orderId() {
        return request.orderId();
    }

    public long userId() {
        return request.userId();
    }

    public OrderRequest request() {
        return request;
    }

    public MarketSymbol market() {
        return request.market();
    }

    public OrderSide side() {
        return request.side();
    }

    public OrderType type() {
        return request.type();
    }

    public TimeInForce timeInForce() {
        return request.timeInForce();
    }

    public Price price() {
        return request.price();
    }

    public long arrivalSequence() {
        return arrivalSequence;
    }

    public Quantity originalQuantity() {
        return request.quantity();
    }

    public Quantity remaining() {
        return remaining;
    }

    public Quantity filled() {
        return new Quantity(
                originalQuantity().unscaled() - remaining.unscaled(),
                originalQuantity().scale());
    }

    public OrderStatus status() {
        return status;
    }

    public void fill(Quantity quantity) {
        if (OrderStateMachine.isTerminal(status) || quantity == null || quantity.isZero()
                || quantity.compareTo(remaining) > 0) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "fill must be positive, within remaining quantity, and target an active order");
        }
        Quantity nextRemaining = remaining.minus(quantity);
        OrderStatus nextStatus = nextRemaining.isZero()
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;
        if (nextStatus != status) {
            OrderStateMachine.requireTransition(status, nextStatus);
        }
        remaining = nextRemaining;
        status = nextStatus;
    }

    public void cancel() {
        OrderStateMachine.requireTransition(status, OrderStatus.CANCELED);
        status = OrderStatus.CANCELED;
    }
}
