package com.ethan.exchange.order.application;

public record WalletBalance(
        long walletId,
        WalletKey key,
        long available,
        long locked,
        long version
) {

    public WalletBalance apply(long availableDelta, long lockedDelta) {
        long nextAvailable;
        long nextLocked;
        long nextVersion;
        try {
            nextAvailable = Math.addExact(available, availableDelta);
            nextLocked = Math.addExact(locked, lockedDelta);
            nextVersion = Math.incrementExact(version);
        } catch (ArithmeticException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "wallet arithmetic overflow for " + key, exception);
        }
        if (nextAvailable < 0 || nextLocked < 0) {
            throw new ExchangeOperationException(ExchangeErrorCode.INSUFFICIENT_BALANCE,
                    "wallet balance would become negative for " + key);
        }
        return new WalletBalance(walletId, key, nextAvailable, nextLocked, nextVersion);
    }
}
