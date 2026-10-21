package com.ethan.exchange.order.application;

public record WalletKey(long userId, long assetId) implements Comparable<WalletKey> {

    public WalletKey {
        if (userId < 0 || assetId <= 0) {
            throw new IllegalArgumentException("wallet key requires non-negative user and positive asset");
        }
    }

    @Override
    public int compareTo(WalletKey other) {
        int userComparison = Long.compare(userId, other.userId);
        return userComparison != 0 ? userComparison : Long.compare(assetId, other.assetId);
    }
}
