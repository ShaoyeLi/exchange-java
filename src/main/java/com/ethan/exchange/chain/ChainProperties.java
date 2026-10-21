package com.ethan.exchange.chain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "exchange.chain")
public record ChainProperties(
        boolean enabled,
        long chainId,
        String name,
        String rpcUrl,
        long startBlock,
        int confirmationDepth,
        int scanBatchSize,
        int reorgLookback,
        Duration leaseDuration
) {
    public ChainProperties {
        if (chainId <= 0 || name == null || name.isBlank() || rpcUrl == null || rpcUrl.isBlank()
                || startBlock < 0 || confirmationDepth < 0 || scanBatchSize <= 0
                || scanBatchSize > 10_000 || reorgLookback <= 0 || leaseDuration == null
                || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("invalid chain configuration");
        }
    }
}
