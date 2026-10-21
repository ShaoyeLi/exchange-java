package com.ethan.exchange.api.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ExchangeQueryService {

    private final JdbcTemplate jdbc;

    public ExchangeQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BalanceView> balances(long userId) {
        return jdbc.query("""
                SELECT asset.symbol, asset.decimal_scale, wallet.available, wallet.locked
                FROM wallets wallet JOIN assets asset ON asset.id = wallet.asset_id
                WHERE wallet.user_id = ? ORDER BY asset.symbol
                """, (row, number) -> new BalanceView(
                row.getString("symbol"), row.getInt("decimal_scale"),
                row.getLong("available"), row.getLong("locked")), userId);
    }

    public List<OrderView> orders(long userId, int limit) {
        return jdbc.query("""
                SELECT orders.id, market.symbol AS market, orders.side, orders.order_type,
                       orders.time_in_force, orders.status, orders.price,
                       orders.protection_price, orders.original_quantity,
                       orders.remaining_quantity, orders.created_at
                FROM orders JOIN markets market ON market.id = orders.market_id
                WHERE orders.user_id = ? ORDER BY orders.id DESC LIMIT ?
                """, (row, number) -> new OrderView(
                row.getLong("id"), row.getString("market"), row.getString("side"),
                row.getString("order_type"), row.getString("time_in_force"),
                row.getString("status"), nullableLong(row, "price"),
                nullableLong(row, "protection_price"), row.getLong("original_quantity"),
                row.getLong("remaining_quantity"), row.getTimestamp("created_at").toInstant()),
                userId, bounded(limit));
    }

    public List<TradeView> trades(long userId, int limit) {
        return jdbc.query("""
                SELECT trade.id, market.symbol AS market, trade.execution_sequence,
                       trade.maker_order_id, trade.taker_order_id, trade.price,
                       trade.quantity, trade.quote_quantity, trade.created_at
                FROM trades trade
                JOIN markets market ON market.id = trade.market_id
                JOIN orders maker ON maker.id = trade.maker_order_id
                JOIN orders taker ON taker.id = trade.taker_order_id
                WHERE maker.user_id = ? OR taker.user_id = ?
                ORDER BY trade.id DESC LIMIT ?
                """, (row, number) -> new TradeView(
                row.getLong("id"), row.getString("market"),
                row.getLong("execution_sequence"), row.getLong("maker_order_id"),
                row.getLong("taker_order_id"), row.getLong("price"),
                row.getLong("quantity"), row.getLong("quote_quantity"),
                row.getTimestamp("created_at").toInstant()), userId, userId, bounded(limit));
    }

    public List<DepositView> deposits(long userId, int limit) {
        return jdbc.query("""
                SELECT deposit.id, deposit.chain_id, asset.symbol, asset.decimal_scale,
                       deposit.transaction_hash, deposit.log_index, deposit.block_number,
                       deposit.raw_amount, deposit.internal_amount, deposit.deposit_status,
                       deposit.observed_at, deposit.credited_at
                FROM chain_deposits deposit JOIN assets asset ON asset.id = deposit.asset_id
                WHERE deposit.user_id = ? ORDER BY deposit.id DESC LIMIT ?
                """, (row, number) -> new DepositView(
                row.getLong("id"), row.getLong("chain_id"), row.getString("symbol"),
                row.getInt("decimal_scale"), row.getString("transaction_hash"), row.getLong("log_index"),
                row.getLong("block_number"), row.getString("raw_amount"),
                nullableLong(row, "internal_amount"), row.getString("deposit_status"),
                row.getTimestamp("observed_at").toInstant(),
                nullableInstant(row, "credited_at")), userId, bounded(limit));
    }

    public List<WithdrawalView> withdrawals(long userId, int limit) {
        return jdbc.query("""
                SELECT withdrawal.id, asset.symbol, asset.decimal_scale,
                       withdrawal.destination_address,
                       withdrawal.amount, withdrawal.withdrawal_status, withdrawal.created_at
                FROM withdrawal_requests withdrawal
                JOIN assets asset ON asset.id = withdrawal.asset_id
                WHERE withdrawal.user_id = ? ORDER BY withdrawal.id DESC LIMIT ?
                """, (row, number) -> new WithdrawalView(
                row.getLong("id"), row.getString("symbol"), row.getInt("decimal_scale"),
                row.getString("destination_address"), row.getLong("amount"),
                row.getString("withdrawal_status"),
                row.getTimestamp("created_at").toInstant()), userId, bounded(limit));
    }

    public MarketView market(String symbol) {
        return jdbc.queryForObject("""
                SELECT market.symbol, base.symbol AS base_asset, quote.symbol AS quote_asset,
                       market.price_scale, market.quantity_scale, market.price_tick,
                       market.quantity_step, market.minimum_quantity, market.minimum_notional,
                       market.maker_fee_bps, market.taker_fee_bps
                FROM markets market
                JOIN assets base ON base.id = market.base_asset_id
                JOIN assets quote ON quote.id = market.quote_asset_id
                WHERE market.symbol = ?
                """, (row, number) -> new MarketView(
                row.getString("symbol"), row.getString("base_asset"),
                row.getString("quote_asset"), row.getInt("price_scale"),
                row.getInt("quantity_scale"), row.getLong("price_tick"),
                row.getLong("quantity_step"), row.getLong("minimum_quantity"),
                row.getLong("minimum_notional"), row.getInt("maker_fee_bps"),
                row.getInt("taker_fee_bps")), symbol);
    }

    public List<BookLevelView> book(String market, String side, int limit) {
        String direction = "BUY".equals(side) ? "DESC" : "ASC";
        String sql = """
                SELECT orders.price, SUM(orders.remaining_quantity) AS quantity,
                       COUNT(*) AS order_count
                FROM orders JOIN markets ON markets.id = orders.market_id
                WHERE markets.symbol = ? AND orders.side = ?
                  AND orders.status IN ('NEW', 'PARTIALLY_FILLED')
                GROUP BY orders.price
                ORDER BY orders.price %s
                LIMIT ?
                """.formatted(direction);
        return jdbc.query(sql,
                (row, number) -> new BookLevelView(
                        row.getLong("price"), row.getLong("quantity"),
                        row.getInt("order_count")), market, side, bounded(limit));
    }

    public List<TradeView> publicTrades(String market, int limit) {
        return jdbc.query("""
                SELECT trade.id, markets.symbol AS market, trade.execution_sequence,
                       trade.maker_order_id, trade.taker_order_id, trade.price,
                       trade.quantity, trade.quote_quantity, trade.created_at
                FROM trades trade JOIN markets ON markets.id = trade.market_id
                WHERE markets.symbol = ? ORDER BY trade.id DESC LIMIT ?
                """, (row, number) -> new TradeView(
                row.getLong("id"), row.getString("market"),
                row.getLong("execution_sequence"), row.getLong("maker_order_id"),
                row.getLong("taker_order_id"), row.getLong("price"),
                row.getLong("quantity"), row.getLong("quote_quantity"),
                row.getTimestamp("created_at").toInstant()), market, bounded(limit));
    }

    public OperationsView operations() {
        return new OperationsView(
                scalar("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'"),
                scalar("SELECT COUNT(*) FROM outbox_events WHERE status = 'DEAD'"),
                scalar("SELECT COUNT(*) FROM chain_deposits WHERE deposit_status = 'REVIEW'"),
                scalar("SELECT COUNT(*) FROM withdrawal_requests WHERE withdrawal_status = 'REVIEW'"),
                jdbc.query("""
                        SELECT network.chain_id, network.network_name, state.next_block,
                               state.sync_status, state.blocked_reason
                        FROM chain_networks network JOIN chain_sync_state state
                          ON state.chain_id = network.chain_id
                        ORDER BY network.chain_id
                        """, (row, number) -> new ChainStatusView(
                        row.getLong("chain_id"), row.getString("network_name"),
                        row.getLong("next_block"), row.getString("sync_status"),
                        row.getString("blocked_reason"))));
    }

    private long scalar(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }

    private static int bounded(int limit) {
        if (limit <= 0 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return limit;
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record BalanceView(String asset, int scale, long available, long locked) {
    }

    public record OrderView(
            long id, String market, String side, String type, String timeInForce,
            String status, Long price, Long protectionPrice, long originalQuantity,
            long remainingQuantity, Instant createdAt) {
    }

    public record TradeView(
            long id, String market, long executionSequence, long makerOrderId,
            long takerOrderId, long price, long quantity, long quoteQuantity,
            Instant createdAt) {
    }

    public record DepositView(
            long id, long chainId, String asset, int scale, String transactionHash, long logIndex,
            long blockNumber, String rawAmount, Long internalAmount, String status,
            Instant observedAt, Instant creditedAt) {
    }

    public record WithdrawalView(
            long id, String asset, int scale, String destinationAddress, long amount,
            String status, Instant createdAt) {
    }

    public record MarketView(
            String symbol, String baseAsset, String quoteAsset, int priceScale,
            int quantityScale, long priceTick, long quantityStep,
            long minimumQuantity, long minimumNotional, int makerFeeBps,
            int takerFeeBps) {
    }

    public record BookLevelView(long price, long quantity, int orderCount) {
    }

    public record OperationsView(
            long pendingOutbox, long deadOutbox, long depositsInReview,
            long withdrawalsInReview, List<ChainStatusView> chains) {
    }

    public record ChainStatusView(
            long chainId, String name, long nextBlock, String status, String blockedReason) {
    }
}
