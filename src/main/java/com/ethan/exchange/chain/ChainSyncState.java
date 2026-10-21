package com.ethan.exchange.chain;

public record ChainSyncState(long nextBlock, String status, String blockedReason) {

    public boolean blocked() {
        return "BLOCKED".equals(status);
    }
}
