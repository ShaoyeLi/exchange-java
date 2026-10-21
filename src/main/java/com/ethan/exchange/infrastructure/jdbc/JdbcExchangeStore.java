package com.ethan.exchange.infrastructure.jdbc;

import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderStatus;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.TimeInForce;
import com.ethan.exchange.order.application.ExchangeStore;
import com.ethan.exchange.order.application.IdempotencyClaim;
import com.ethan.exchange.order.application.ExchangeErrorCode;
import com.ethan.exchange.order.application.ExchangeOperationException;
import com.ethan.exchange.order.application.MarketState;
import com.ethan.exchange.order.application.OperationType;
import com.ethan.exchange.order.application.PlaceOrderCommand;
import com.ethan.exchange.order.application.StoredOrder;
import com.ethan.exchange.order.application.TradeRow;
import com.ethan.exchange.order.application.WalletBalance;
import com.ethan.exchange.order.application.WalletKey;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class JdbcExchangeStore implements ExchangeStore {

    private static final RowMapper<StoredOrder> ORDER_MAPPER = (result, row) -> new StoredOrder(
            result.getLong("id"), result.getLong("user_id"), result.getLong("market_id"),
            result.getString("market_symbol"), OrderSide.valueOf(result.getString("side")),
            OrderType.valueOf(result.getString("order_type")),
            TimeInForce.valueOf(result.getString("time_in_force")),
            OrderStatus.valueOf(result.getString("status")),
            nullableLong(result, "price"), nullableLong(result, "protection_price"),
            result.getLong("original_quantity"), result.getLong("remaining_quantity"),
            result.getLong("arrival_sequence"), result.getLong("reserved_base_remaining"),
            result.getLong("reserved_quote_remaining"), result.getLong("cumulative_maker_quote"),
            result.getLong("cumulative_taker_quote"), result.getLong("cumulative_fee_charged"),
            result.getString("idempotency_key"), result.getString("request_hash"),
            result.getLong("version"));

    private static final String ORDER_SELECT = """
            SELECT o.*, m.symbol AS market_symbol
            FROM orders o JOIN markets m ON m.id = o.market_id
            """;

    private final JdbcTemplate jdbc;

    public JdbcExchangeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IdempotencyClaim claimIdempotency(
            long userId, OperationType operation, String key, String requestHash) {
        jdbc.update("""
                INSERT INTO idempotency_records
                    (user_id, operation_type, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE request_hash = idempotency_records.request_hash
                """, userId, operation.name(), key, requestHash);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT request_hash, result_order_id
                FROM idempotency_records
                WHERE user_id = ? AND operation_type = ? AND idempotency_key = ?
                FOR UPDATE
                """, userId, operation.name(), key);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new ExchangeOperationException(ExchangeErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key is already bound to another request");
        }
        Number result = (Number) row.get("result_order_id");
        return result == null ? IdempotencyClaim.fresh() : IdempotencyClaim.replay(result.longValue());
    }

    @Override
    public void completeIdempotency(
            long userId, OperationType operation, String key, long orderId) {
        int changed = jdbc.update("""
                UPDATE idempotency_records
                SET result_order_id = ?, completed_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND operation_type = ? AND idempotency_key = ?
                  AND result_order_id IS NULL
                """, orderId, userId, operation.name(), key);
        if (changed != 1) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "idempotency completion did not update exactly one row");
        }
    }

    @Override
    public MarketState lockMarket(String symbol) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM markets WHERE symbol = ? FOR UPDATE", symbol);
            long baseAssetId = ((Number) row.get("base_asset_id")).longValue();
            long quoteAssetId = ((Number) row.get("quote_asset_id")).longValue();
            String baseSymbol = jdbc.queryForObject(
                    "SELECT symbol FROM assets WHERE id = ?", String.class, baseAssetId);
            String quoteSymbol = jdbc.queryForObject(
                    "SELECT symbol FROM assets WHERE id = ?", String.class, quoteAssetId);
            return new MarketState(
                    ((Number) row.get("id")).longValue(), (String) row.get("symbol"),
                    baseAssetId, baseSymbol, quoteAssetId, quoteSymbol,
                    ((Number) row.get("fee_wallet_user_id")).longValue(),
                    ((Number) row.get("price_scale")).intValue(),
                    ((Number) row.get("quantity_scale")).intValue(),
                    ((Number) row.get("price_tick")).longValue(),
                    ((Number) row.get("quantity_step")).longValue(),
                    ((Number) row.get("minimum_quantity")).longValue(),
                    ((Number) row.get("minimum_notional")).longValue(),
                    ((Number) row.get("maker_fee_bps")).intValue(),
                    ((Number) row.get("taker_fee_bps")).intValue(),
                    ((Number) row.get("last_arrival_sequence")).longValue(),
                    ((Number) row.get("last_execution_sequence")).longValue());
        } catch (EmptyResultDataAccessException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.MARKET_NOT_FOUND,
                    "market not found: " + symbol, exception);
        }
    }

    @Override
    public List<StoredOrder> loadActiveOrders(long marketId) {
        return jdbc.query(ORDER_SELECT + """
                WHERE o.market_id = ? AND o.status IN ('NEW', 'PARTIALLY_FILLED')
                ORDER BY o.arrival_sequence
                """, ORDER_MAPPER, marketId);
    }

    @Override
    public StoredOrder loadOrder(long orderId) {
        return queryOrder(ORDER_SELECT + " WHERE o.id = ?", orderId);
    }

    @Override
    public StoredOrder lockOrder(long orderId) {
        return queryOrder(ORDER_SELECT + " WHERE o.id = ? FOR UPDATE", orderId);
    }

    @Override
    public long insertDraft(
            PlaceOrderCommand command,
            MarketState market,
            long arrivalSequence,
            String requestHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO orders (
                        user_id, market_id, side, order_type, time_in_force, status,
                        price, protection_price, original_quantity, remaining_quantity,
                        arrival_sequence, idempotency_key, request_hash)
                    VALUES (?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, command.userId());
            statement.setLong(2, market.marketId());
            statement.setString(3, command.side().name());
            statement.setString(4, command.type().name());
            statement.setString(5, command.timeInForce().name());
            if (command.price() == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, command.price());
            }
            if (command.protectionPrice() == null) {
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(7, command.protectionPrice());
            }
            statement.setLong(8, command.quantity());
            statement.setLong(9, command.quantity());
            statement.setLong(10, arrivalSequence);
            statement.setString(11, command.idempotencyKey());
            statement.setString(12, requestHash);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "database did not return an order ID");
        }
        return key.longValue();
    }

    @Override
    public Map<WalletKey, WalletBalance> createAndLockWallets(Set<WalletKey> keys) {
        List<WalletKey> sorted = keys.stream().sorted().toList();
        for (WalletKey key : sorted) {
            jdbc.update("""
                    INSERT INTO wallets (user_id, asset_id, available, locked)
                    VALUES (?, ?, 0, 0)
                    ON DUPLICATE KEY UPDATE id = wallets.id
                    """, key.userId(), key.assetId());
        }
        Map<WalletKey, WalletBalance> result = new LinkedHashMap<>();
        for (WalletKey key : sorted) {
            WalletBalance wallet = jdbc.queryForObject("""
                    SELECT id, user_id, asset_id, available, locked, version
                    FROM wallets WHERE user_id = ? AND asset_id = ? FOR UPDATE
                    """, (row, number) -> new WalletBalance(
                    row.getLong("id"), new WalletKey(row.getLong("user_id"), row.getLong("asset_id")),
                    row.getLong("available"), row.getLong("locked"), row.getLong("version")),
                    key.userId(), key.assetId());
            result.put(key, wallet);
        }
        return result;
    }

    @Override
    public WalletBalance updateWallet(
            WalletBalance current, long availableDelta, long lockedDelta) {
        WalletBalance next = current.apply(availableDelta, lockedDelta);
        int changed = jdbc.update("""
                UPDATE wallets
                SET available = ?, locked = ?, version = ?
                WHERE id = ? AND version = ?
                """, next.available(), next.locked(), next.version(),
                current.walletId(), current.version());
        if (changed != 1) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "wallet version changed while locked: " + current.key());
        }
        return next;
    }

    @Override
    public void insertJournal(String journalId, String type, String businessRef) {
        jdbc.update("INSERT INTO ledger_journals (journal_id, journal_type, business_ref) VALUES (?, ?, ?)",
                journalId, type, businessRef);
    }

    @Override
    public void insertLedgerEntry(
            String journalId, String businessRef, WalletBalance wallet,
            String postingType, long availableDelta, long lockedDelta) {
        jdbc.update("""
                INSERT INTO ledger_entries (
                    journal_id, business_ref, wallet_id, asset_id, posting_type,
                    delta_available, delta_locked)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, journalId, businessRef, wallet.walletId(), wallet.key().assetId(),
                postingType, availableDelta, lockedDelta);
    }

    @Override
    public void updateOrder(StoredOrder order) {
        int changed = jdbc.update("""
                UPDATE orders SET
                    status = ?, remaining_quantity = ?,
                    reserved_base_remaining = ?, reserved_quote_remaining = ?,
                    cumulative_maker_quote = ?, cumulative_taker_quote = ?,
                    cumulative_fee_charged = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, order.status().name(), order.remainingQuantity(),
                order.reservedBaseRemaining(), order.reservedQuoteRemaining(),
                order.cumulativeMakerQuote(), order.cumulativeTakerQuote(),
                order.cumulativeFeeCharged(), order.orderId(), order.version());
        if (changed != 1) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "order version changed: " + order.orderId());
        }
    }

    @Override
    public void insertTrade(TradeRow trade) {
        jdbc.update("""
                INSERT INTO trades (
                    market_id, execution_sequence, maker_order_id, taker_order_id,
                    price, quantity, quote_quantity, maker_fee, taker_fee)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, trade.marketId(), trade.executionSequence(), trade.makerOrderId(),
                trade.takerOrderId(), trade.price(), trade.quantity(), trade.quoteQuantity(),
                trade.makerFee(), trade.takerFee());
    }

    @Override
    public void updateMarketSequences(long marketId, long lastArrival, long lastExecution) {
        jdbc.update("""
                UPDATE markets SET last_arrival_sequence = ?, last_execution_sequence = ?
                WHERE id = ?
                """, lastArrival, lastExecution, marketId);
    }

    @Override
    public void insertOutbox(
            long marketId,
            String aggregateType,
            long aggregateId,
            long aggregateVersion,
            String eventType,
            String payload) {
        jdbc.update("""
                INSERT INTO market_event_sequences (market_id, last_sequence)
                VALUES (?, 0)
                ON DUPLICATE KEY UPDATE market_id = market_event_sequences.market_id
                """, marketId);
        int advanced = jdbc.update("""
                UPDATE market_event_sequences
                SET last_sequence = last_sequence + 1
                WHERE market_id = ? AND last_sequence < 9223372036854775807
                """, marketId);
        if (advanced != 1) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "market event sequence exhausted");
        }
        Long marketSequence = jdbc.queryForObject(
                "SELECT last_sequence FROM market_event_sequences WHERE market_id = ?",
                Long.class, marketId);
        if (marketSequence == null) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "market event sequence was not returned");
        }
        jdbc.update("""
                INSERT INTO outbox_events (
                    event_id, market_id, market_sequence, aggregate_type, aggregate_id,
                    aggregate_version, event_type, schema_version, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
                """, java.util.UUID.randomUUID().toString(), marketId, marketSequence,
                aggregateType, aggregateId, aggregateVersion, eventType, payload);
    }

    private StoredOrder queryOrder(String sql, long orderId) {
        try {
            return jdbc.queryForObject(sql, ORDER_MAPPER, orderId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.ORDER_NOT_FOUND,
                    "order not found: " + orderId, exception);
        }
    }

    private static Long nullableLong(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
