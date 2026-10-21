package com.ethan.exchange.matching.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRequestTest {

    private static final MarketSymbol BTC_USDT = MarketSymbol.of("btc/usdt");

    @Test
    void normalizesMarketSymbol() {
        assertThat(BTC_USDT.value()).isEqualTo("BTC/USDT");
    }

    @Test
    void rejectsInvalidFixedPointValues() {
        assertThatThrownBy(() -> Price.of(0, 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Quantity.of(0, 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Quantity(-1, 0)).isInstanceOf(DomainException.class);
    }

    @Test
    void limitRequiresPriceAndMarketForbidsPrice() {
        assertThatThrownBy(() -> new OrderRequest(
                1, 1, BTC_USDT, OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, null, null, Quantity.of(1, 0)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("requires a price");

        assertThatThrownBy(() -> new OrderRequest(
                1, 1, BTC_USDT, OrderSide.BUY, OrderType.MARKET,
                TimeInForce.IOC, Price.of(1, 0), Price.of(2, 0), Quantity.of(1, 0)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("forbids price");
    }

    @Test
    void marketSupportsIocOnly() {
        assertThatThrownBy(() -> new OrderRequest(
                1, 1, BTC_USDT, OrderSide.BUY, OrderType.MARKET,
                TimeInForce.GTC, null, Price.of(2, 0), Quantity.of(1, 0)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("IOC only");
    }

    @Test
    void quantityArithmeticIsExactAndCannotBecomeNegative() {
        assertThat(Quantity.of(7, 2).minus(Quantity.of(2, 2)))
                .isEqualTo(Quantity.of(5, 2));
        assertThatThrownBy(() -> Quantity.of(2, 2).minus(Quantity.of(3, 2)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Quantity.of(Long.MAX_VALUE, 2).plus(Quantity.of(1, 2)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(DomainErrorCode.NUMERIC_OVERFLOW);
        assertThatThrownBy(() -> Quantity.of(2, 2).plus(Quantity.of(1, 3)))
                .isInstanceOf(DomainException.class);
    }
}
