package com.ethan.exchange.order.application;

import com.ethan.exchange.matching.domain.AssetSymbol;
import com.ethan.exchange.matching.domain.Market;
import com.ethan.exchange.matching.domain.MarketSymbol;
import com.ethan.exchange.matching.domain.Notional;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.Quantity;

public record MarketState(
        long marketId,
        String symbol,
        long baseAssetId,
        String baseAssetSymbol,
        long quoteAssetId,
        String quoteAssetSymbol,
        long feeWalletUserId,
        int priceScale,
        int quantityScale,
        long priceTick,
        long quantityStep,
        long minimumQuantity,
        long minimumNotional,
        int makerFeeBps,
        int takerFeeBps,
        long lastArrivalSequence,
        long lastExecutionSequence
) {

    public Market toDomain() {
        return new Market(
                MarketSymbol.of(symbol),
                AssetSymbol.of(baseAssetSymbol),
                AssetSymbol.of(quoteAssetSymbol),
                priceScale,
                quantityScale,
                Price.of(priceTick, priceScale),
                Quantity.of(quantityStep, quantityScale),
                Quantity.of(minimumQuantity, quantityScale),
                Notional.of(minimumNotional, priceScale + quantityScale)
        );
    }

    public int maximumFeeBps() {
        return Math.max(makerFeeBps, takerFeeBps);
    }
}
