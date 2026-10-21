package com.ethan.exchange.chain;

import java.time.Instant;

public record ChainBlock(
        long number,
        String hash,
        String parentHash,
        Instant timestamp
) {
    public ChainBlock {
        if (number < 0 || timestamp == null) {
            throw new IllegalArgumentException("invalid block");
        }
        hash = EvmHex.hash(hash);
        parentHash = EvmHex.hash(parentHash);
    }
}
