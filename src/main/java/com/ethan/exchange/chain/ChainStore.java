package com.ethan.exchange.chain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChainStore {

    void ensureNetwork(ChainProperties properties);

    boolean tryAcquireLease(long chainId, String owner, Instant now, Duration duration);

    void releaseLease(long chainId, String owner);

    ChainSyncState loadState(long chainId);

    Optional<String> blockHash(long chainId, long blockNumber);

    Map<String, ChainToken> enabledTokens(long chainId);

    Map<String, Long> enabledDepositAddresses(long chainId);

    void persistBatch(long chainId, String owner, long expectedNextBlock,
                      List<ChainBlock> blocks, List<ObservedDeposit> deposits);

    RewindResult rewind(long chainId, String owner, long commonAncestor, String reason);

    void block(long chainId, String owner, String reason);

    int creditEligible(long chainId, long safeHead, int limit);
}
