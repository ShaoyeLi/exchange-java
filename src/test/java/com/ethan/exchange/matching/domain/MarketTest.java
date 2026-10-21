package com.ethan.exchange.matching.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketTest {

    private static final Market MARKET = new Market(
            MarketSymbol.of("BTC/USDT"), AssetSymbol.of("BTC"), AssetSymbol.of("USDT"),
            2, 3, Price.of(5, 2), Quantity.of(10, 3), Quantity.of(20, 3),
            Notional.of(10_000, 5));

    @Test
    void enforcesScaleTickStepMinimumQuantityAndMinimumNotional() {
        assertThatThrownBy(() -> MARKET.validate(limit(Price.of(101, 2), Quantity.of(100, 3))))
                .isInstanceOf(DomainException.class).hasMessageContaining("tick");
        assertThatThrownBy(() -> MARKET.validate(limit(Price.of(100, 1), Quantity.of(100, 3))))
                .isInstanceOf(DomainException.class).hasMessageContaining("scale");
        assertThatThrownBy(() -> MARKET.validate(limit(Price.of(100, 2), Quantity.of(15, 3))))
                .isInstanceOf(DomainException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> MARKET.validate(limit(Price.of(100, 2), Quantity.of(20, 3))))
                .isInstanceOf(DomainException.class).hasMessageContaining("notional");

        MARKET.validate(limit(Price.of(500, 2), Quantity.of(20, 3)));
    }

    @Test
    void multiplicationDetectsOverflowAndScaleMismatch() {
        assertThatThrownBy(() -> Price.of(Long.MAX_VALUE, 2).multiply(Quantity.of(2, 3)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.NUMERIC_OVERFLOW);
        assertThatThrownBy(() -> Price.of(100, 2).compareTo(Price.of(100, 3)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsZeroQuantityStepAndMinimumsWithDomainError() {
        assertThatThrownBy(() -> new Market(
                MarketSymbol.of("BTC/USDT"), AssetSymbol.of("BTC"), AssetSymbol.of("USDT"),
                2, 3, Price.of(1, 2), Quantity.zero(3), Quantity.of(1, 3),
                Notional.of(1, 5)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.INVALID_MARKET);

        assertThatThrownBy(() -> new Market(
                MarketSymbol.of("BTC/USDT"), AssetSymbol.of("BTC"), AssetSymbol.of("USDT"),
                2, 3, Price.of(1, 2), Quantity.of(1, 3), Quantity.zero(3),
                Notional.of(1, 5)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.INVALID_MARKET);

        assertThatThrownBy(() -> new Market(
                MarketSymbol.of("BTC/USDT"), AssetSymbol.of("BTC"), AssetSymbol.of("USDT"),
                2, 3, Price.of(1, 2), Quantity.of(1, 3), Quantity.of(1, 3),
                Notional.of(0, 5)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.INVALID_MARKET);
    }

    @Test
    void terminalOrderStatesCannotReopen() {
        OrderStateMachine.requireTransition(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED);
        OrderStateMachine.requireTransition(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED);
        assertThat(OrderStateMachine.isTerminal(OrderStatus.FILLED)).isTrue();
        assertThat(OrderStateMachine.isTerminal(OrderStatus.CANCELED)).isTrue();
        assertThat(OrderStateMachine.isTerminal(OrderStatus.REJECTED)).isTrue();
        assertThatThrownBy(() -> OrderStateMachine.requireTransition(
                OrderStatus.FILLED, OrderStatus.NEW))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION);
    }

    private static OrderRequest limit(Price price, Quantity quantity) {
        return OrderRequest.limit(1, 1, MARKET.symbol(), OrderSide.BUY,
                TimeInForce.GTC, price, quantity);
    }
}
