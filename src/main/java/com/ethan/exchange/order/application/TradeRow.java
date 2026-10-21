package com.ethan.exchange.order.application;

public record TradeRow(
        long marketId,
        long executionSequence,
        long makerOrderId,
        long takerOrderId,
        long price,
        long quantity,
        long quoteQuantity,
        long makerFee,
        long takerFee
) {
}
