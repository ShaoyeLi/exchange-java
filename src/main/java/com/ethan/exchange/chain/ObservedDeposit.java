package com.ethan.exchange.chain;

public record ObservedDeposit(
        Erc20Transfer transfer,
        long userId,
        long assetId,
        Long internalAmount,
        String status
) {
    public ObservedDeposit {
        if (transfer == null || userId <= 0 || assetId <= 0
                || !("SEEN".equals(status) || "IGNORED".equals(status)
                || "REVIEW".equals(status))) {
            throw new IllegalArgumentException("invalid observed deposit");
        }
    }
}
