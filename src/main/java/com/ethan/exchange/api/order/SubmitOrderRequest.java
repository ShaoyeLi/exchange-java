package com.ethan.exchange.api.order;

import com.ethan.exchange.matching.domain.DomainErrorCode;
import com.ethan.exchange.matching.domain.DomainException;
import com.ethan.exchange.matching.domain.Market;
import com.ethan.exchange.matching.domain.OrderRequest;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.Quantity;
import com.ethan.exchange.matching.domain.TimeInForce;

import java.util.Locale;

/**
 * Transport-neutral input contract. An HTTP controller can reuse it in a later milestone.
 */
public record SubmitOrderRequest(
        long orderId,
        long userId,
        String side,
        String type,
        String timeInForce,
        Long priceUnscaled,
        Long protectionPriceUnscaled,
        long quantityUnscaled
) {

    public OrderRequest toDomain(Market market) {
        if (market == null) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET, "market is required");
        }
        OrderSide parsedSide = parse(OrderSide.class, side, "side");
        OrderType parsedType = parse(OrderType.class, type, "type");
        TimeInForce parsedTif = parse(TimeInForce.class, timeInForce, "timeInForce");
        Price price = priceUnscaled == null ? null : Price.of(priceUnscaled, market.priceScale());
        Price protection = protectionPriceUnscaled == null
                ? null
                : Price.of(protectionPriceUnscaled, market.priceScale());
        OrderRequest command = new OrderRequest(
                orderId,
                userId,
                market.symbol(),
                parsedSide,
                parsedType,
                parsedTif,
                price,
                protection,
                Quantity.of(quantityUnscaled, market.quantityScale())
        );
        market.validate(command);
        return command;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER, field + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "unknown " + field + ": " + value, exception);
        }
    }
}
