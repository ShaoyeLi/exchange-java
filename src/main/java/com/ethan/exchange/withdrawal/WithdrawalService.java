package com.ethan.exchange.withdrawal;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class WithdrawalService {

    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public WithdrawalService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public WithdrawalResult request(WithdrawalCommand command) {
        return executeWithRetry(() -> transactions.execute(status -> requestInTransaction(command)));
    }

    public WithdrawalResult cancel(long userId, long withdrawalId, String idempotencyKey) {
        if (userId <= 0 || withdrawalId <= 0 || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("invalid withdrawal cancellation");
        }
        String key = idempotencyKey.trim();
        return executeWithRetry(() -> transactions.execute(status ->
                cancelInTransaction(userId, withdrawalId, key)));
    }

    private WithdrawalResult requestInTransaction(WithdrawalCommand command) {
        String hash = hash(String.join("|", command.asset(), command.destinationAddress(),
                Long.toString(command.amount())));
        Long replay = claim(command.userId(), "REQUEST_WITHDRAWAL", command.idempotencyKey(), hash);
        if (replay != null) {
            return new WithdrawalResult(replay, status(replay), true);
        }

        Long assetId;
        try {
            assetId = jdbc.queryForObject(
                    "SELECT id FROM assets WHERE symbol = ?", Long.class, command.asset());
        } catch (EmptyResultDataAccessException exception) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.ASSET_NOT_FOUND, "asset not found: " + command.asset());
        }
        if (assetId == null) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.ASSET_NOT_FOUND, "asset ID was not returned");
        }
        Wallet wallet = lockWallet(command.userId(), assetId);
        if (wallet.available() < command.amount()) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.INSUFFICIENT_BALANCE, "insufficient available balance");
        }
        long nextLocked;
        try {
            nextLocked = Math.addExact(wallet.locked(), command.amount());
        } catch (ArithmeticException exception) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.NUMERIC_OVERFLOW, "locked balance overflow");
        }

        String journalId = UUID.randomUUID().toString();
        String businessRef = "WITHDRAWAL:REQUEST:" + command.userId() + ":" + hash;
        jdbc.update("""
                INSERT INTO ledger_journals (journal_id, journal_type, business_ref)
                VALUES (?, 'WITHDRAWAL', ?)
                """, journalId, businessRef);
        updateWallet(wallet, wallet.available() - command.amount(), nextLocked);
        jdbc.update("""
                INSERT INTO ledger_entries (
                    journal_id, business_ref, wallet_id, asset_id, posting_type,
                    delta_available, delta_locked)
                VALUES (?, ?, ?, ?, 'WITHDRAWAL_RESERVE', ?, ?)
                """, journalId, businessRef, wallet.id(), assetId,
                -command.amount(), command.amount());

        KeyHolder keys = new GeneratedKeyHolder();
        long finalAssetId = assetId;
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO withdrawal_requests (
                        user_id, asset_id, destination_address, amount, withdrawal_status,
                        idempotency_key, request_hash, journal_id)
                    VALUES (?, ?, ?, ?, 'REVIEW', ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, command.userId());
            statement.setLong(2, finalAssetId);
            statement.setString(3, command.destinationAddress());
            statement.setLong(4, command.amount());
            statement.setString(5, command.idempotencyKey());
            statement.setString(6, hash);
            statement.setString(7, journalId);
            return statement;
        }, keys);
        Number generated = keys.getKey();
        if (generated == null) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.CONCURRENT_UPDATE, "database did not return withdrawal ID");
        }
        long withdrawalId = generated.longValue();
        complete(command.userId(), "REQUEST_WITHDRAWAL", command.idempotencyKey(), withdrawalId);
        return new WithdrawalResult(withdrawalId, "REVIEW", false);
    }

    private WithdrawalResult cancelInTransaction(
            long userId, long withdrawalId, String idempotencyKey) {
        String requestHash = hash(Long.toString(withdrawalId));
        Long replay = claim(userId, "CANCEL_WITHDRAWAL", idempotencyKey, requestHash);
        if (replay != null) {
            return new WithdrawalResult(replay, status(replay), true);
        }
        Map<String, Object> withdrawal;
        try {
            withdrawal = jdbc.queryForMap("""
                    SELECT user_id, asset_id, amount, withdrawal_status
                    FROM withdrawal_requests WHERE id = ? FOR UPDATE
                    """, withdrawalId);
        } catch (EmptyResultDataAccessException exception) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.WITHDRAWAL_NOT_FOUND, "withdrawal not found");
        }
        if (((Number) withdrawal.get("user_id")).longValue() != userId) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.WITHDRAWAL_NOT_OWNED, "withdrawal does not belong to user");
        }
        if (!"REVIEW".equals(withdrawal.get("withdrawal_status"))) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.WITHDRAWAL_NOT_CANCELABLE,
                    "only a withdrawal in review can be canceled");
        }
        long assetId = ((Number) withdrawal.get("asset_id")).longValue();
        long amount = ((Number) withdrawal.get("amount")).longValue();
        Wallet wallet = lockWallet(userId, assetId);
        if (wallet.locked() < amount) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.CONCURRENT_UPDATE, "withdrawal reservation is missing");
        }
        long nextAvailable;
        try {
            nextAvailable = Math.addExact(wallet.available(), amount);
        } catch (ArithmeticException exception) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.NUMERIC_OVERFLOW, "available balance overflow");
        }
        String journalId = UUID.randomUUID().toString();
        String businessRef = "WITHDRAWAL:CANCEL:" + withdrawalId;
        jdbc.update("""
                INSERT INTO ledger_journals (journal_id, journal_type, business_ref)
                VALUES (?, 'WITHDRAWAL_CANCEL', ?)
                """, journalId, businessRef);
        updateWallet(wallet, nextAvailable, wallet.locked() - amount);
        jdbc.update("""
                INSERT INTO ledger_entries (
                    journal_id, business_ref, wallet_id, asset_id, posting_type,
                    delta_available, delta_locked)
                VALUES (?, ?, ?, ?, 'WITHDRAWAL_RELEASE', ?, ?)
                """, journalId, businessRef, wallet.id(), assetId, amount, -amount);
        requireOne(jdbc.update("""
                UPDATE withdrawal_requests
                SET withdrawal_status = 'CANCELED', release_journal_id = ?
                WHERE id = ? AND withdrawal_status = 'REVIEW'
                """, journalId, withdrawalId), "withdrawal state changed while canceling");
        complete(userId, "CANCEL_WITHDRAWAL", idempotencyKey, withdrawalId);
        return new WithdrawalResult(withdrawalId, "CANCELED", false);
    }

    private Long claim(long userId, String operation, String key, String requestHash) {
        jdbc.update("""
                INSERT INTO withdrawal_idempotency (
                    user_id, operation_type, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE request_hash = withdrawal_idempotency.request_hash
                """, userId, operation, key, requestHash);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT request_hash, result_withdrawal_id
                FROM withdrawal_idempotency
                WHERE user_id = ? AND operation_type = ? AND idempotency_key = ?
                FOR UPDATE
                """, userId, operation, key);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new WithdrawalException(
                    WithdrawalErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key is already bound to another request");
        }
        Number result = (Number) row.get("result_withdrawal_id");
        return result == null ? null : result.longValue();
    }

    private void complete(long userId, String operation, String key, long withdrawalId) {
        requireOne(jdbc.update("""
                UPDATE withdrawal_idempotency
                SET result_withdrawal_id = ?, completed_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND operation_type = ? AND idempotency_key = ?
                  AND result_withdrawal_id IS NULL
                """, withdrawalId, userId, operation, key), "idempotency completion failed");
    }

    private Wallet lockWallet(long userId, long assetId) {
        jdbc.update("""
                INSERT INTO wallets (user_id, asset_id, available, locked)
                VALUES (?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE id = wallets.id
                """, userId, assetId);
        return jdbc.queryForObject("""
                SELECT id, available, locked, version FROM wallets
                WHERE user_id = ? AND asset_id = ? FOR UPDATE
                """, (row, number) -> new Wallet(
                row.getLong("id"), row.getLong("available"),
                row.getLong("locked"), row.getLong("version")), userId, assetId);
    }

    private void updateWallet(Wallet wallet, long available, long locked) {
        requireOne(jdbc.update("""
                UPDATE wallets SET available = ?, locked = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, available, locked, wallet.id(), wallet.version()),
                "wallet version changed during withdrawal");
    }

    private String status(long withdrawalId) {
        return jdbc.queryForObject(
                "SELECT withdrawal_status FROM withdrawal_requests WHERE id = ?",
                String.class, withdrawalId);
    }

    private <T> T executeWithRetry(Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                T result = action.get();
                if (result == null) {
                    throw new WithdrawalException(
                            WithdrawalErrorCode.CONCURRENT_UPDATE, "transaction returned no result");
                }
                return result;
            } catch (ConcurrencyFailureException exception) {
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    throw new WithdrawalException(
                            WithdrawalErrorCode.CONCURRENT_UPDATE,
                            "withdrawal transaction exhausted retries");
                }
            }
        }
        throw new IllegalStateException("unreachable withdrawal retry state");
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new WithdrawalException(WithdrawalErrorCode.CONCURRENT_UPDATE, message);
        }
    }

    private record Wallet(long id, long available, long locked, long version) {
    }
}
