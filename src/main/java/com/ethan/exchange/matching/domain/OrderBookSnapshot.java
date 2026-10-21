package com.ethan.exchange.matching.domain;

import java.util.List;
import java.util.Optional;

public record OrderBookSnapshot(
        MarketSymbol market,
        long lastArrivalSequence,
        List<PriceLevel> bids,
        List<PriceLevel> asks
) {

    public OrderBookSnapshot {
        if (market == null || bids == null || asks == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "snapshot market, bids, and asks are required");
        }
        if (lastArrivalSequence < 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "lastArrivalSequence must not be negative");
        }
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    public Optional<Price> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.getFirst().price());
    }

    public Optional<Price> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.getFirst().price());
    }
}
