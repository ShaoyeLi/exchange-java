package com.ethan.exchange.api.order;

import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.TimeInForce;
import com.ethan.exchange.order.application.CancelOrderCommand;
import com.ethan.exchange.order.application.ExchangeOrderService;
import com.ethan.exchange.order.application.OrderCommandResult;
import com.ethan.exchange.order.application.PlaceOrderCommand;
import com.ethan.exchange.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ExchangeOrderService orders;

    public OrderController(ExchangeOrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public OrderCommandResult place(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderHttpRequest request) {
        return orders.place(new PlaceOrderCommand(
                CurrentUser.id(jwt), request.market(), parse(OrderSide.class, request.side()),
                parse(OrderType.class, request.type()),
                parse(TimeInForce.class, request.timeInForce()), request.price(),
                request.protectionPrice(), request.quantity(), idempotencyKey));
    }

    @PostMapping("/{orderId}/cancel")
    public OrderCommandResult cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CancelOrderHttpRequest request) {
        return orders.cancel(new CancelOrderCommand(
                CurrentUser.id(jwt), request.market(), orderId, idempotencyKey));
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName(), exception);
        }
    }
}
