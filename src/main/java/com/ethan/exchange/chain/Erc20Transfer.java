package com.ethan.exchange.chain;

import java.math.BigInteger;

public record Erc20Transfer(
        String tokenAddress,
        String transactionHash,
        long logIndex,
        long blockNumber,
        String blockHash,
        String fromAddress,
        String toAddress,
        BigInteger rawAmount
) {
    public Erc20Transfer {
        tokenAddress = EvmHex.address(tokenAddress);
        transactionHash = EvmHex.hash(transactionHash);
        blockHash = EvmHex.hash(blockHash);
        fromAddress = EvmHex.address(fromAddress);
        toAddress = EvmHex.address(toAddress);
        if (logIndex < 0 || blockNumber < 0 || rawAmount == null || rawAmount.signum() < 0) {
            throw new IllegalArgumentException("invalid ERC-20 transfer");
        }
    }
}
