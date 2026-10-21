package com.ethan.exchange.api.order;

import com.ethan.exchange.matching.domain.AssetSymbol;
import com.ethan.exchange.matching.domain.DomainErrorCode;
import com.ethan.exchange.matching.domain.DomainException;
import com.ethan.exchange.matching.domain.Market;
import com.ethan.exchange.matching.domain.MarketSymbol;
import com.ethan.exchange.matching.domain.Notional;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.Quantity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubmitOrderRequestTest {

    private static final Market MARKET = new Market(
            MarketSymbol.of("BTC/USDT"), AssetSymbol.of("BTC"), AssetSymbol.of("USDT"),
            2, 4, Price.of(1, 2), Quantity.of(1, 4), Quantity.of(10, 4),
            Notional.of(1_000, 6));

    @Test
    void mapsTransportValuesIntoTypedDomainCommand() {
        var dto = new SubmitOrderRequest(
                1, 2, "buy", "limit", "gtc", 10_000L, null, 100L);

        var command = dto.toDomain(MARKET);

        assertThat(command.side()).isEqualTo(OrderSide.BUY);
        assertThat(command.price()).isEqualTo(Price.of(10_000, 2));
        assertThat(command.quantity()).isEqualTo(Quantity.of(100, 4));
    }

    @Test
    void rejectsUnknownEnumWithStableErrorCode() {
        var dto = new SubmitOrderRequest(
                1, 2, "hold", "limit", "gtc", 10_000L, null, 100L);

        DomainException error = assertThrows(
                DomainException.class, () -> dto.toDomain(MARKET));

        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ORDER);
        assertThat(error).hasMessageContaining("unknown side");
    }
}
