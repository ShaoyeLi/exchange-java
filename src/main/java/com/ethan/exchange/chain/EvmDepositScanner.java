package com.ethan.exchange.chain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "exchange.chain.enabled", havingValue = "true")
public class EvmDepositScanner {

    private static final int MAX_CREDITS_PER_CYCLE = 500;

    private final ChainProperties properties;
    private final EvmChainClient client;
    private final ChainStore store;
    private final Clock clock;
    private final String owner;

    @Autowired
    public EvmDepositScanner(ChainProperties properties, EvmChainClient client, ChainStore store) {
        this(properties, client, store, Clock.systemUTC(), UUID.randomUUID().toString());
    }

    EvmDepositScanner(
            ChainProperties properties, EvmChainClient client, ChainStore store,
            Clock clock, String owner) {
        this.properties = properties;
        this.client = client;
        this.store = store;
        this.clock = clock;
        this.owner = owner;
    }

    public ChainScanResult scanOnce() {
        store.ensureNetwork(properties);
        ChainSyncState beforeLease = store.loadState(properties.chainId());
        if (beforeLease.blocked()) {
            return new ChainScanResult(-1, 0, 0, 0, false, true, false);
        }
        Instant now = clock.instant();
        if (!store.tryAcquireLease(
                properties.chainId(), owner, now, properties.leaseDuration())) {
            return ChainScanResult.busy();
        }
        try {
            ChainSyncState state = store.loadState(properties.chainId());
            if (state.blocked()) {
                return new ChainScanResult(-1, 0, 0, 0, false, true, true);
            }
            long actualChainId = client.chainId();
            if (actualChainId != properties.chainId()) {
                throw new ChainDataException("RPC chain ID " + actualChainId
                        + " does not match configured chain ID " + properties.chainId());
            }
            long head = client.latestBlockNumber();
            ChainScanResult reorg = detectAndHandleReorg(state, head);
            if (reorg != null) {
                return reorg;
            }

            int scanned = 0;
            int observed = 0;
            if (state.nextBlock() <= head) {
                long toBlock = boundedEnd(state.nextBlock(), head, properties.scanBatchSize());
                List<ChainBlock> blocks = loadAndValidateBlocks(state.nextBlock(), toBlock);
                List<ObservedDeposit> deposits = loadDeposits(state.nextBlock(), toBlock, blocks);
                store.persistBatch(properties.chainId(), owner, state.nextBlock(), blocks, deposits);
                scanned = blocks.size();
                observed = deposits.size();
            }
            long safeHead = head - properties.confirmationDepth();
            int credited = safeHead < 0 ? 0 : store.creditEligible(
                    properties.chainId(), safeHead, MAX_CREDITS_PER_CYCLE);
            return new ChainScanResult(head, scanned, observed, credited,
                    false, false, true);
        } finally {
            store.releaseLease(properties.chainId(), owner);
        }
    }

    private ChainScanResult detectAndHandleReorg(ChainSyncState state, long head) {
        long lastScanned = state.nextBlock() - 1;
        if (lastScanned < properties.startBlock() || lastScanned > head) {
            return null;
        }
        String stored = store.blockHash(properties.chainId(), lastScanned).orElseThrow(() ->
                new ChainDataException("cursor has no persisted block hash"));
        if (stored.equals(client.block(lastScanned).hash())) {
            return null;
        }

        long lower = Math.max(properties.startBlock(),
                lastScanned - properties.reorgLookback() + 1);
        Long ancestor = null;
        for (long height = lastScanned - 1; height >= lower; height--) {
            String oldHash = store.blockHash(properties.chainId(), height).orElse(null);
            if (oldHash != null && oldHash.equals(client.block(height).hash())) {
                ancestor = height;
                break;
            }
        }
        if (ancestor == null && lower == properties.startBlock()) {
            ancestor = properties.startBlock() - 1;
        }
        if (ancestor == null) {
            store.block(properties.chainId(), owner,
                    "reorganization exceeds configured lookback at block " + lastScanned);
            return new ChainScanResult(head, 0, 0, 0, false, true, true);
        }
        RewindResult result = store.rewind(properties.chainId(), owner, ancestor,
                "canonical block hash changed at block " + lastScanned);
        return new ChainScanResult(head, 0, 0, 0,
                result == RewindResult.REWOUND,
                result == RewindResult.BLOCKED_CREDITED_DEPOSIT, true);
    }

    private List<ChainBlock> loadAndValidateBlocks(long from, long to) {
        List<ChainBlock> blocks = new ArrayList<>();
        String expectedParent = from == properties.startBlock()
                ? null
                : store.blockHash(properties.chainId(), from - 1)
                .orElseGet(() -> client.block(from - 1).hash());
        for (long number = from; ; number++) {
            ChainBlock block = client.block(number);
            if (block.number() != number) {
                throw new ChainDataException("RPC returned the wrong block number");
            }
            if (expectedParent != null && !expectedParent.equals(block.parentHash())) {
                throw new ChainDataException("non-contiguous parent hash at block " + number);
            }
            blocks.add(block);
            expectedParent = block.hash();
            if (number == to) {
                break;
            }
        }
        return List.copyOf(blocks);
    }

    private List<ObservedDeposit> loadDeposits(
            long from, long to, List<ChainBlock> blocks) {
        Map<String, ChainToken> tokens = store.enabledTokens(properties.chainId());
        Map<String, Long> addresses = store.enabledDepositAddresses(properties.chainId());
        if (tokens.isEmpty() || addresses.isEmpty()) {
            return List.of();
        }
        Map<Long, String> hashes = new HashMap<>();
        blocks.forEach(block -> hashes.put(block.number(), block.hash()));
        List<Erc20Transfer> transfers = new ArrayList<>(
                client.transfers(from, to, List.copyOf(tokens.keySet())));
        transfers.sort(Comparator.comparingLong(Erc20Transfer::blockNumber)
                .thenComparingLong(Erc20Transfer::logIndex));

        List<ObservedDeposit> deposits = new ArrayList<>();
        for (Erc20Transfer transfer : transfers) {
            String expectedHash = hashes.get(transfer.blockNumber());
            if (expectedHash == null || !expectedHash.equals(transfer.blockHash())) {
                throw new ChainDataException("transfer does not belong to the scanned canonical range");
            }
            ChainToken token = tokens.get(transfer.tokenAddress());
            Long userId = addresses.get(transfer.toAddress());
            if (token == null || userId == null) {
                continue;
            }
            if (transfer.rawAmount().signum() == 0) {
                deposits.add(new ObservedDeposit(
                        transfer, userId, token.assetId(), null, "IGNORED"));
                continue;
            }
            try {
                long amount = AmountScaler.toInternal(
                        transfer.rawAmount(), token.tokenDecimals(), token.internalScale());
                deposits.add(new ObservedDeposit(
                        transfer, userId, token.assetId(), amount, "SEEN"));
            } catch (ArithmeticException | IllegalArgumentException exception) {
                deposits.add(new ObservedDeposit(
                        transfer, userId, token.assetId(), null, "REVIEW"));
            }
        }
        return List.copyOf(deposits);
    }

    private static long boundedEnd(long from, long head, int batchSize) {
        long maximum = from > Long.MAX_VALUE - batchSize + 1
                ? Long.MAX_VALUE : from + batchSize - 1;
        return Math.min(head, maximum);
    }
}
