package com.ethan.exchange.chain;

import java.util.List;

public interface EvmChainClient {

    long chainId();

    long latestBlockNumber();

    ChainBlock block(long number);

    List<Erc20Transfer> transfers(long fromBlock, long toBlock, List<String> tokenAddresses);
}
