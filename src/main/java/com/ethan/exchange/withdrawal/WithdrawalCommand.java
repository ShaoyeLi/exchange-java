package com.ethan.exchange.withdrawal;

import com.ethan.exchange.chain.EvmHex;

import java.util.Locale;

public record WithdrawalCommand(
        long userId,
        String asset,
        String destinationAddress,
        long amount,
        String idempotencyKey
) {
    public WithdrawalCommand {
        if (userId <= 0 || asset == null || asset.isBlank() || amount <= 0
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("invalid withdrawal command");
        }
        asset = asset.trim().toUpperCase(Locale.ROOT);
        destinationAddress = EvmHex.address(destinationAddress);
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must contain at most 128 characters");
        }
    }
}
