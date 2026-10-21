package com.ethan.exchange.infrastructure.jdbc;

import com.ethan.exchange.chain.ChainBlock;
import com.ethan.exchange.chain.ChainDataException;
import com.ethan.exchange.chain.ChainProperties;
import com.ethan.exchange.chain.ChainStore;
import com.ethan.exchange.chain.ChainSyncState;
import com.ethan.exchange.chain.ChainToken;
import com.ethan.exchange.chain.Erc20Transfer;
import com.ethan.exchange.chain.ObservedDeposit;
import com.ethan.exchange.chain.RewindResult;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcChainStore implements ChainStore {

    private final JdbcTemplate jdbc;

    public JdbcChainStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void ensureNetwork(ChainProperties properties) {
        jdbc.update("""
                INSERT INTO chain_networks (
                    chain_id, network_name, rpc_url, confirmation_depth,
                    scan_batch_size, reorg_lookback, enabled)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                ON DUPLICATE KEY UPDATE
                    network_name = VALUES(network_name), rpc_url = VALUES(rpc_url),
                    confirmation_depth = VALUES(confirmation_depth),
                    scan_batch_size = VALUES(scan_batch_size),
                    reorg_lookback = VALUES(reorg_lookback), enabled = TRUE
                """, properties.chainId(), properties.name(), properties.rpcUrl(),
                properties.confirmationDepth(), properties.scanBatchSize(),
                properties.reorgLookback());
        jdbc.update("""
                INSERT INTO chain_sync_state (chain_id, next_block)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE chain_id = chain_sync_state.chain_id
                """, properties.chainId(), properties.startBlock());
    }

    @Override
    public boolean tryAcquireLease(
            long chainId, String owner, Instant now, Duration duration) {
        return jdbc.update("""
                UPDATE chain_sync_state
                SET owner_id = ?, lease_until = ?, version = version + 1
                WHERE chain_id = ? AND sync_status = 'RUNNING'
                  AND (owner_id = ? OR lease_until IS NULL OR lease_until <= ?)
                """, owner, Timestamp.from(now.plus(duration)), chainId,
                owner, Timestamp.from(now)) == 1;
    }

    @Override
    public void releaseLease(long chainId, String owner) {
        jdbc.update("""
                UPDATE chain_sync_state
                SET owner_id = NULL, lease_until = NULL, version = version + 1
                WHERE chain_id = ? AND owner_id = ?
                """, chainId, owner);
    }

    @Override
    public ChainSyncState loadState(long chainId) {
        try {
            return jdbc.queryForObject("""
                    SELECT next_block, sync_status, blocked_reason
                    FROM chain_sync_state WHERE chain_id = ?
                    """, (row, number) -> new ChainSyncState(
                    row.getLong("next_block"), row.getString("sync_status"),
                    row.getString("blocked_reason")), chainId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ChainDataException("chain sync state does not exist", exception);
        }
    }

    @Override
    public Optional<String> blockHash(long chainId, long blockNumber) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT block_hash FROM chain_blocks
                    WHERE chain_id = ? AND block_number = ?
                    """, String.class, chainId, blockNumber));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, ChainToken> enabledTokens(long chainId) {
        Map<String, ChainToken> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT token.contract_address, token.asset_id, token.token_decimals,
                       asset.decimal_scale
                FROM chain_token_contracts token
                JOIN assets asset ON asset.id = token.asset_id
                WHERE token.chain_id = ? AND token.enabled = TRUE
                ORDER BY token.contract_address
                """, (RowCallbackHandler) row -> result.put(
                row.getString("contract_address"), new ChainToken(
                row.getLong("asset_id"), row.getInt("token_decimals"),
                row.getInt("decimal_scale"))), chainId);
        return Map.copyOf(result);
    }

    @Override
    public Map<String, Long> enabledDepositAddresses(long chainId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT address, user_id FROM deposit_addresses
                WHERE chain_id = ? AND enabled = TRUE ORDER BY address
                """, (RowCallbackHandler) row -> result.put(
                row.getString("address"), row.getLong("user_id")), chainId);
        return Map.copyOf(result);
    }

    @Override
    @Transactional
    public void persistBatch(
            long chainId,
            String owner,
            long expectedNextBlock,
            List<ChainBlock> blocks,
            List<ObservedDeposit> deposits) {
        if (blocks.isEmpty() || blocks.getFirst().number() != expectedNextBlock) {
            throw new ChainDataException("scan batch does not start at the persisted cursor");
        }
        for (ChainBlock block : blocks) {
            jdbc.update("""
                    INSERT INTO chain_blocks (
                        chain_id, block_number, block_hash, parent_hash, block_timestamp)
                    VALUES (?, ?, ?, ?, ?)
                    """, chainId, block.number(), block.hash(), block.parentHash(),
                    Timestamp.from(block.timestamp()));
        }
        for (ObservedDeposit deposit : deposits) {
            Erc20Transfer transfer = deposit.transfer();
            jdbc.update("""
                    INSERT INTO chain_deposits (
                        chain_id, transaction_hash, log_index, block_number, block_hash,
                        token_address, from_address, to_address, user_id, asset_id,
                        raw_amount, internal_amount, deposit_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE transaction_hash = chain_deposits.transaction_hash
                    """, chainId, transfer.transactionHash(), transfer.logIndex(),
                    transfer.blockNumber(), transfer.blockHash(), transfer.tokenAddress(),
                    transfer.fromAddress(), transfer.toAddress(), deposit.userId(),
                    deposit.assetId(), transfer.rawAmount().toString(), deposit.internalAmount(),
                    deposit.status());
        }
        long nextBlock = Math.addExact(blocks.getLast().number(), 1);
        int changed = jdbc.update("""
                UPDATE chain_sync_state
                SET next_block = ?, version = version + 1
                WHERE chain_id = ? AND next_block = ? AND sync_status = 'RUNNING'
                  AND owner_id = ? AND lease_until > CURRENT_TIMESTAMP(6)
                """, nextBlock, chainId, expectedNextBlock, owner);
        requireOne(changed, "chain cursor update lost its lease or expected position");
    }

    @Override
    @Transactional
    public RewindResult rewind(
            long chainId, String owner, long commonAncestor, String reason) {
        Long credited = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chain_deposits
                WHERE chain_id = ? AND block_number > ? AND deposit_status = 'CREDITED'
                """, Long.class, chainId, commonAncestor);
        if (credited != null && credited > 0) {
            block(chainId, owner, "credited deposit affected: " + reason);
            return RewindResult.BLOCKED_CREDITED_DEPOSIT;
        }
        jdbc.update("""
                UPDATE chain_deposits
                SET deposit_status = 'ORPHANED'
                WHERE chain_id = ? AND block_number > ?
                  AND deposit_status IN ('SEEN', 'IGNORED', 'REVIEW')
                """, chainId, commonAncestor);
        jdbc.update("DELETE FROM chain_blocks WHERE chain_id = ? AND block_number > ?",
                chainId, commonAncestor);
        int changed = jdbc.update("""
                UPDATE chain_sync_state
                SET next_block = ?, blocked_reason = NULL, version = version + 1
                WHERE chain_id = ? AND sync_status = 'RUNNING' AND owner_id = ?
                """, Math.addExact(commonAncestor, 1), chainId, owner);
        requireOne(changed, "chain rewind lost its lease");
        return RewindResult.REWOUND;
    }

    @Override
    public void block(long chainId, String owner, String reason) {
        int changed = jdbc.update("""
                UPDATE chain_sync_state
                SET sync_status = 'BLOCKED', blocked_reason = ?, version = version + 1
                WHERE chain_id = ? AND sync_status = 'RUNNING' AND owner_id = ?
                """, abbreviate(reason), chainId, owner);
        requireOne(changed, "chain block operation lost its lease");
    }

    @Override
    @Transactional
    public int creditEligible(long chainId, long safeHead, int limit) {
        List<CreditRow> deposits = jdbc.query("""
                SELECT id, transaction_hash, log_index, user_id, asset_id, internal_amount
                FROM chain_deposits
                WHERE chain_id = ? AND deposit_status = 'SEEN' AND block_number <= ?
                ORDER BY user_id, asset_id, id
                LIMIT ? FOR UPDATE
                """, (row, number) -> new CreditRow(
                row.getLong("id"), row.getString("transaction_hash"),
                row.getLong("log_index"), row.getLong("user_id"),
                row.getLong("asset_id"), (Long) row.getObject("internal_amount")),
                chainId, safeHead, limit);
        int credited = 0;
        for (CreditRow deposit : deposits) {
            if (deposit.amount() == null || deposit.amount() <= 0) {
                jdbc.update("UPDATE chain_deposits SET deposit_status = 'REVIEW' WHERE id = ?",
                        deposit.id());
                continue;
            }
            jdbc.update("""
                    INSERT INTO wallets (user_id, asset_id, available, locked)
                    VALUES (?, ?, 0, 0)
                    ON DUPLICATE KEY UPDATE id = wallets.id
                    """, deposit.userId(), deposit.assetId());
            Map<String, Object> wallet = jdbc.queryForMap("""
                    SELECT id, available, version FROM wallets
                    WHERE user_id = ? AND asset_id = ? FOR UPDATE
                    """, deposit.userId(), deposit.assetId());
            long current = ((Number) wallet.get("available")).longValue();
            long next;
            try {
                next = Math.addExact(current, deposit.amount());
            } catch (ArithmeticException exception) {
                jdbc.update("UPDATE chain_deposits SET deposit_status = 'REVIEW' WHERE id = ?",
                        deposit.id());
                continue;
            }
            long walletId = ((Number) wallet.get("id")).longValue();
            long version = ((Number) wallet.get("version")).longValue();
            String journalId = UUID.randomUUID().toString();
            String businessRef = "CHAIN:" + chainId + ":" + deposit.transactionHash()
                    + ":" + deposit.logIndex();
            jdbc.update("""
                    INSERT INTO ledger_journals (journal_id, journal_type, business_ref)
                    VALUES (?, 'DEPOSIT', ?)
                    """, journalId, businessRef);
            requireOne(jdbc.update("""
                    UPDATE wallets SET available = ?, version = version + 1
                    WHERE id = ? AND version = ?
                    """, next, walletId, version), "deposit wallet version changed");
            jdbc.update("""
                    INSERT INTO ledger_entries (
                        journal_id, business_ref, wallet_id, asset_id, posting_type,
                        delta_available, delta_locked)
                    VALUES (?, ?, ?, ?, 'DEPOSIT_CREDIT', ?, 0)
                    """, journalId, businessRef, walletId, deposit.assetId(), deposit.amount());
            requireOne(jdbc.update("""
                    UPDATE chain_deposits
                    SET deposit_status = 'CREDITED', journal_id = ?,
                        credited_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ? AND deposit_status = 'SEEN'
                    """, journalId, deposit.id()), "deposit status changed while crediting");
            credited++;
        }
        return credited;
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new ChainDataException(message);
        }
    }

    private static String abbreviate(String value) {
        return value == null ? "unknown chain failure"
                : value.substring(0, Math.min(value.length(), 512));
    }

    private record CreditRow(
            long id,
            String transactionHash,
            long logIndex,
            long userId,
            long assetId,
            Long amount
    ) {
    }
}
