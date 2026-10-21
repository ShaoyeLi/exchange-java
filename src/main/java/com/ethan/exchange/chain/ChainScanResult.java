package com.ethan.exchange.chain;

public record ChainScanResult(
        long head,
        int scannedBlocks,
        int observedDeposits,
        int creditedDeposits,
        boolean rewound,
        boolean blocked,
        boolean leaseAcquired
) {
    static ChainScanResult busy() {
        return new ChainScanResult(-1, 0, 0, 0, false, false, false);
    }
}
