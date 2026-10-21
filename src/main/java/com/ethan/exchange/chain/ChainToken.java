package com.ethan.exchange.chain;

public record ChainToken(long assetId, int tokenDecimals, int internalScale) {
    public ChainToken {
        if (assetId <= 0 || tokenDecimals < 0 || tokenDecimals > 77
                || internalScale < 0 || internalScale > 18) {
            throw new IllegalArgumentException("invalid chain token mapping");
        }
    }
}
