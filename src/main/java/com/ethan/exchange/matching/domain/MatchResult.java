package com.ethan.exchange.matching.domain;

import java.util.List;

public record MatchResult(List<Trade> trades, List<OrderUpdate> updates) {

    public MatchResult {
        if (trades == null || updates == null) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "match result trades and updates are required");
        }
        trades = List.copyOf(trades);
        updates = List.copyOf(updates);
    }
}
