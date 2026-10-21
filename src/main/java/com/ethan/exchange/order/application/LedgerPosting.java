package com.ethan.exchange.order.application;

public record LedgerPosting(
        WalletKey wallet,
        String postingType,
        long availableDelta,
        long lockedDelta
) {

    public LedgerPosting plus(LedgerPosting other) {
        if (!wallet.equals(other.wallet) || !postingType.equals(other.postingType)) {
            throw new IllegalArgumentException("only equal posting identities can be combined");
        }
        return new LedgerPosting(
                wallet,
                postingType,
                ExactMath.add(availableDelta, other.availableDelta),
                ExactMath.add(lockedDelta, other.lockedDelta));
    }
}
