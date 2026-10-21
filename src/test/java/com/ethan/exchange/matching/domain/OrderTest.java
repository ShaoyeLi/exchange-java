package com.ethan.exchange.matching.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void aggregateOwnsIdentityQuantitiesSequenceAndLegalStatus() {
        Order order = Order.accept(OrderRequest.limit(
                1, 2, MarketSymbol.of("BTC/USDT"), OrderSide.BUY,
                TimeInForce.GTC, Price.of(10_000, 2), Quantity.of(5, 3)), 7);

        assertThat(order.orderId()).isEqualTo(1);
        assertThat(order.userId()).isEqualTo(2);
        assertThat(order.arrivalSequence()).isEqualTo(7);
        assertThat(order.originalQuantity()).isEqualTo(Quantity.of(5, 3));
        assertThat(order.status()).isEqualTo(OrderStatus.NEW);

        order.fill(Quantity.of(2, 3));
        assertThat(order.filled()).isEqualTo(Quantity.of(2, 3));
        assertThat(order.remaining()).isEqualTo(Quantity.of(3, 3));
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        order.cancel();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELED);
        assertThatThrownBy(() -> order.fill(Quantity.of(1, 3)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION);
    }
}
