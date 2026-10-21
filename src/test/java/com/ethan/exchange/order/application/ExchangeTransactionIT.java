package com.ethan.exchange.order.application;

import com.ethan.exchange.infrastructure.jdbc.JdbcExchangeStore;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderStatus;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.TimeInForce;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = "spring.main.web-application-type=none")
class ExchangeTransactionIT {

    private static final long BTC = 1;
    private static final long USDT = 2;
    private static final long PRICE_100 = 10_000;
    private static final long PRICE_110 = 11_000;
    private static final long QUANTITY = 10_000;
    private static final long QUOTE_AT_100 = 100_000_000;
    private static final long BUY_RESERVE_AT_100 = 100_200_000;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("exchange_java")
            .withUsername("exchange")
            .withPassword("exchange")
            .withStartupTimeout(Duration.ofSeconds(90));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ExchangeOrderService service;

    @Autowired
    private JdbcExchangeStore store;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetBusinessData() {
        jdbc.update("DELETE FROM market_event_projection");
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM market_event_sequences");
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM ledger_journals");
        jdbc.update("DELETE FROM trades");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallets WHERE asset_id NOT IN (1, 2)");
        jdbc.update("DELETE FROM wallets WHERE user_id <> 0");
        jdbc.update("DELETE FROM markets WHERE id <> 1");
        jdbc.update("DELETE FROM assets WHERE id NOT IN (1, 2)");
        jdbc.update("UPDATE wallets SET available = 0, locked = 0, version = 0 WHERE user_id = 0");
        jdbc.update("UPDATE markets SET last_arrival_sequence = 0, last_execution_sequence = 0");
    }

    @Test
    void flywayMigratesMySql84AndDatabaseConstraintsAreActive() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name IN "
                + "('assets','markets','wallets','orders','trades','ledger_journals',"
                + "'ledger_entries','idempotency_records','outbox_events',"
                + "'market_event_sequences','outbox_relay_leases','inbox_events',"
                + "'market_event_projection','chain_networks','chain_sync_state',"
                + "'chain_blocks','chain_token_contracts','deposit_addresses',"
                + "'chain_deposits','app_users','withdrawal_requests',"
                + "'withdrawal_idempotency')")).isEqualTo(22);

        fund(10, USDT, 1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE wallets SET available = -1 WHERE user_id = 10 AND asset_id = 2"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE markets SET maker_fee_bps = 10001 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void placingOrderReservesFundsAndCreatesJournalLedgerAndOutbox() {
        fund(11, BTC, QUANTITY);

        OrderCommandResult result = service.place(sell(11, PRICE_100, QUANTITY, "reserve-sell"));

        assertThat(result.status()).isEqualTo(OrderStatus.NEW);
        assertThat(result.reservedBaseRemaining()).isEqualTo(QUANTITY);
        assertWallet(11, BTC, 0, QUANTITY);
        assertThat(count("SELECT COUNT(*) FROM ledger_journals WHERE journal_type = 'RESERVE'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE posting_type = 'RESERVE_BASE'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = " + result.orderId()))
                .isEqualTo(1);
    }

    @Test
    void tradeAtomicallySettlesBalancesCumulativeFeesLedgerAndOutbox() {
        fund(21, BTC, QUANTITY);
        fund(22, USDT, 200_000_000);
        OrderCommandResult maker = service.place(sell(21, PRICE_100, QUANTITY, "maker"));

        OrderCommandResult taker = service.place(buy(22, PRICE_110, QUANTITY, "taker"));

        assertThat(taker.status()).isEqualTo(OrderStatus.FILLED);
        assertWallet(21, BTC, 0, 0);
        assertWallet(21, USDT, 99_900_000, 0);
        assertWallet(22, BTC, QUANTITY, 0);
        assertWallet(22, USDT, 99_800_000, 0);
        assertWallet(0, USDT, 300_000, 0);
        assertThat(count("SELECT COUNT(*) FROM trades")).isEqualTo(1);
        assertThat(value("SELECT maker_fee FROM trades")).isEqualTo(100_000);
        assertThat(value("SELECT taker_fee FROM trades")).isEqualTo(200_000);
        assertThat(value("SELECT cumulative_maker_quote FROM orders WHERE id = " + maker.orderId()))
                .isEqualTo(QUOTE_AT_100);
        assertThat(value("SELECT cumulative_taker_quote FROM orders WHERE id = " + taker.orderId()))
                .isEqualTo(QUOTE_AT_100);
        assertThat(count("""
                SELECT COUNT(*) FROM (
                    SELECT journal_id, asset_id
                    FROM ledger_entries
                    GROUP BY journal_id, asset_id
                    HAVING SUM(delta_available + delta_locked) <> 0
                ) imbalance
                """)).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(4);
        assertThat(value("SELECT last_arrival_sequence FROM markets WHERE id = 1")).isEqualTo(2);
        assertThat(value("SELECT last_execution_sequence FROM markets WHERE id = 1")).isEqualTo(1);
    }

    @Test
    void partialFillThenCancelReleasesOnlyPersistedRemainingReservation() {
        fund(31, BTC, QUANTITY * 2);
        fund(32, USDT, BUY_RESERVE_AT_100);
        OrderCommandResult seller = service.place(sell(31, PRICE_100, QUANTITY * 2, "partial-maker"));
        service.place(buy(32, PRICE_100, QUANTITY, "partial-taker"));

        assertWallet(31, BTC, 0, QUANTITY);
        assertThat(value("SELECT reserved_base_remaining FROM orders WHERE id = " + seller.orderId()))
                .isEqualTo(QUANTITY);

        OrderCommandResult canceled = service.cancel(new CancelOrderCommand(
                31, "BTC/USDT", seller.orderId(), "cancel-partial"));

        assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.reservedBaseRemaining()).isZero();
        assertWallet(31, BTC, QUANTITY, 0);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE business_ref = 'ORDER:"
                + seller.orderId() + ":RELEASE'")).isEqualTo(1);
    }

    @Test
    void partialBuyFillReleasesPriceImprovementAndUsesOnlyRemainingWeightedFeeBuffer() {
        fund(35, BTC, QUANTITY);
        fund(36, USDT, 300_000_000);
        service.place(sell(35, PRICE_100, QUANTITY, "partial-buy-maker"));

        OrderCommandResult buyer = service.place(
                buy(36, PRICE_110, QUANTITY * 2, "partial-buy"));

        assertThat(buyer.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(buyer.remainingQuantity()).isEqualTo(QUANTITY);
        assertThat(buyer.reservedQuoteRemaining()).isEqualTo(110_220_000);
        assertWallet(36, USDT, 89_580_000, 110_220_000);
        assertThat(value("SELECT cumulative_taker_quote FROM orders WHERE id = " + buyer.orderId()))
                .isEqualTo(QUOTE_AT_100);
        assertThat(value("SELECT cumulative_fee_charged FROM orders WHERE id = " + buyer.orderId()))
                .isEqualTo(200_000);
    }

    @Test
    void idempotencyReplaysOneOrderAndRejectsSameKeyWithDifferentRequest() {
        fund(41, BTC, QUANTITY * 2);
        PlaceOrderCommand command = sell(41, PRICE_100, QUANTITY, "same-key");

        OrderCommandResult first = service.place(command);
        OrderCommandResult replay = service.place(command);

        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.replayed()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM orders WHERE user_id = 41")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ledger_journals WHERE journal_type = 'RESERVE'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> service.place(
                sell(41, PRICE_100, QUANTITY * 2, "same-key")))
                .isInstanceOf(ExchangeOperationException.class)
                .extracting("code").isEqualTo(ExchangeErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void failureMatrixRollsBackEveryTransactionalArtifact() {
        List<FailurePoint> points = List.of(
                FailurePoint.AFTER_TRADE_INSERT,
                FailurePoint.AFTER_SECOND_WALLET_UPDATE,
                FailurePoint.BEFORE_LEDGER_INSERT,
                FailurePoint.BEFORE_OUTBOX_INSERT);
        for (FailurePoint target : points) {
            resetBusinessData();
            fund(51, BTC, QUANTITY);
            fund(52, USDT, BUY_RESERVE_AT_100);
            OrderCommandResult maker = service.place(sell(51, PRICE_100, QUANTITY, "maker-" + target));
            Map<String, Object> sellerBefore = wallet(51, BTC);
            Map<String, Object> buyerBefore = wallet(52, USDT);
            long ledgerBefore = count("SELECT COUNT(*) FROM ledger_entries");
            long outboxBefore = count("SELECT COUNT(*) FROM outbox_events");

            ExchangeOrderService failing = new ExchangeOrderService(store, transactions, point -> {
                if (point == target) {
                    throw new ExchangeOperationException(ExchangeErrorCode.INJECTED_FAILURE, "expected " + target);
                }
            });
            assertThatThrownBy(() -> failing.place(
                    buy(52, PRICE_100, QUANTITY, "rollback-" + target)))
                    .isInstanceOf(ExchangeOperationException.class)
                    .extracting("code").isEqualTo(ExchangeErrorCode.INJECTED_FAILURE);

            assertThat(wallet(51, BTC)).isEqualTo(sellerBefore);
            assertThat(wallet(52, USDT)).isEqualTo(buyerBefore);
            assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM trades")).isZero();
            assertThat(count("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(ledgerBefore);
            assertThat(count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(outboxBefore);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = 'rollback-"
                    + target + "'")).isZero();
            assertThat(value("SELECT last_arrival_sequence FROM markets WHERE id = 1")).isEqualTo(1);
            assertThat(value("SELECT last_execution_sequence FROM markets WHERE id = 1")).isZero();
            assertThat(value("SELECT remaining_quantity FROM orders WHERE id = " + maker.orderId()))
                    .isEqualTo(QUANTITY);
        }
    }

    @Test
    void concurrentIdenticalIdempotentRequestsHaveOneOrderAndOneReservation() throws Exception {
        fund(55, BTC, QUANTITY);
        PlaceOrderCommand command = sell(55, PRICE_100, QUANTITY, "concurrent-same-key");
        List<OrderCommandResult> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<OrderCommandResult>>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> service.place(command)));
            }
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(results.stream().map(OrderCommandResult::orderId).distinct()).hasSize(1);
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE user_id = 55")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE posting_type = 'RESERVE_BASE'"))
                .isEqualTo(1);
        assertWallet(55, BTC, 0, QUANTITY);
    }

    @Test
    void twentyConcurrentBuysCannotReserveMoreThanFiniteBalance() throws Exception {
        long initial = BUY_RESERVE_AT_100 * 5;
        fund(61, USDT, initial);
        List<OrderCommandResult> successes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<java.util.concurrent.Future<OrderCommandResult>>();
            for (int index = 0; index < 20; index++) {
                int commandNumber = index;
                futures.add(executor.submit(() -> service.place(
                        buy(61, PRICE_100, QUANTITY, "concurrent-" + commandNumber))));
            }
            for (var future : futures) {
                try {
                    successes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(ExchangeOperationException.class);
                    assertThat(((ExchangeOperationException) exception.getCause()).code())
                            .isEqualTo(ExchangeErrorCode.INSUFFICIENT_BALANCE);
                }
            }
        }

        assertThat(successes).hasSize(5);
        assertThat(successes.stream().map(OrderCommandResult::orderId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(5);
        assertWallet(61, USDT, 0, initial);
        assertThat(value("SELECT COALESCE(SUM(reserved_quote_remaining), 0) "
                + "FROM orders WHERE user_id = 61")).isEqualTo(initial);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE user_id = 61")).isEqualTo(5);
    }

    @Test
    void newServiceInstanceRebuildsPersistedBookAndMatchesOldOrder() {
        fund(71, BTC, QUANTITY);
        fund(72, USDT, BUY_RESERVE_AT_100);
        OrderCommandResult oldMaker = service.place(sell(71, PRICE_100, QUANTITY, "old-maker"));
        ExchangeOrderService restarted = new ExchangeOrderService(store, transactions);

        OrderCommandResult taker = restarted.place(buy(72, PRICE_100, QUANTITY, "after-restart"));

        assertThat(taker.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(value("SELECT maker_order_id FROM trades")).isEqualTo(oldMaker.orderId());
        assertThat(value("SELECT status = 'FILLED' FROM orders WHERE id = " + oldMaker.orderId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM trades")).isEqualTo(1);
    }

    @Test
    void crossMarketConcurrencyCannotOverspendSharedUsdtWalletOrDeadlock() throws Exception {
        seedEthMarket();
        long initial = BUY_RESERVE_AT_100 * 5;
        fund(81, USDT, initial);
        List<OrderCommandResult> successes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<java.util.concurrent.Future<OrderCommandResult>>();
            for (int index = 0; index < 20; index++) {
                int commandNumber = index;
                String market = index % 2 == 0 ? "BTC/USDT" : "ETH/USDT";
                futures.add(executor.submit(() -> service.place(new PlaceOrderCommand(
                        81, market, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                        PRICE_100, null, QUANTITY, "cross-market-" + commandNumber))));
            }
            for (var future : futures) {
                try {
                    successes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(ExchangeOperationException.class);
                    assertThat(((ExchangeOperationException) exception.getCause()).code())
                            .isEqualTo(ExchangeErrorCode.INSUFFICIENT_BALANCE);
                }
            }
        }

        assertThat(successes).hasSize(5);
        assertWallet(81, USDT, 0, initial);
        assertThat(value("SELECT COALESCE(SUM(reserved_quote_remaining), 0) "
                + "FROM orders WHERE user_id = 81")).isEqualTo(initial);
    }

    @Test
    void concurrentFillAndCancelEndsInOneLegalTerminalState() throws Exception {
        fund(82, BTC, QUANTITY);
        fund(83, USDT, BUY_RESERVE_AT_100);
        OrderCommandResult maker = service.place(sell(82, PRICE_100, QUANTITY, "race-maker"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var fill = executor.submit(() -> {
                start.await();
                return service.place(buy(83, PRICE_100, QUANTITY, "race-fill"));
            });
            var cancel = executor.submit(() -> {
                start.await();
                return service.cancel(new CancelOrderCommand(
                        82, "btc/usdt", maker.orderId(), "race-cancel"));
            });
            start.countDown();
            fill.get(30, TimeUnit.SECONDS);
            try {
                cancel.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(ExchangeOperationException.class);
                assertThat(((ExchangeOperationException) exception.getCause()).code())
                        .isEqualTo(ExchangeErrorCode.ORDER_NOT_ACTIVE);
            }
        }

        String status = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, maker.orderId());
        assertThat(status).isIn("FILLED", "CANCELED");
        assertThat(count("SELECT COUNT(*) FROM trades")).isIn(0L, 1L);
        assertThat(value("SELECT MIN(available) FROM wallets")).isGreaterThanOrEqualTo(0);
        assertThat(value("SELECT MIN(locked) FROM wallets")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void rebuiltPartiallyFilledFifoAndBothSequencesContinueWithoutDuplicateTrade() {
        fund(84, BTC, QUANTITY * 2);
        fund(85, BTC, QUANTITY);
        fund(86, USDT, BUY_RESERVE_AT_100 * 3);
        OrderCommandResult first = service.place(
                sell(84, PRICE_100, QUANTITY * 2, "fifo-first"));
        OrderCommandResult second = service.place(
                sell(85, PRICE_100, QUANTITY, "fifo-second"));
        service.place(buy(86, PRICE_100, QUANTITY, "fifo-fill-1"));

        new ExchangeOrderService(store, transactions).place(
                buy(86, PRICE_100, QUANTITY, "fifo-fill-2"));
        new ExchangeOrderService(store, transactions).place(
                buy(86, PRICE_100, QUANTITY, "fifo-fill-3"));

        List<Map<String, Object>> trades = jdbc.queryForList(
                "SELECT execution_sequence, maker_order_id FROM trades ORDER BY execution_sequence");
        assertThat(trades).extracting(row -> ((Number) row.get("maker_order_id")).longValue())
                .containsExactly(first.orderId(), first.orderId(), second.orderId());
        assertThat(trades).extracting(row -> ((Number) row.get("execution_sequence")).longValue())
                .containsExactly(1L, 2L, 3L);
        assertThat(value("SELECT last_arrival_sequence FROM markets WHERE id = 1")).isEqualTo(5);
        assertThat(value("SELECT last_execution_sequence FROM markets WHERE id = 1")).isEqualTo(3);
        assertThat(count("SELECT COUNT(DISTINCT execution_sequence) FROM trades")).isEqualTo(3);
    }

    @Test
    void criticalQueriesHaveUsableIndexes() {
        fund(87, BTC, QUANTITY);
        OrderCommandResult order = service.place(sell(87, PRICE_100, QUANTITY, "explain"));
        long walletId = value("SELECT id FROM wallets WHERE user_id = 87 AND asset_id = 1");

        assertExplainUsesIndex("EXPLAIN SELECT * FROM orders FORCE INDEX (idx_orders_active_book) "
                + "WHERE market_id = 1 AND status = 'NEW' AND side = 'SELL' "
                + "ORDER BY price, arrival_sequence LIMIT 20");
        assertExplainUsesIndex("EXPLAIN SELECT * FROM idempotency_records "
                + "WHERE user_id = 87 AND operation_type = 'PLACE_ORDER' "
                + "AND idempotency_key = 'explain'");
        assertExplainUsesIndex("EXPLAIN SELECT * FROM ledger_entries FORCE INDEX (idx_ledger_wallet_page) "
                + "WHERE wallet_id = " + walletId + " ORDER BY id DESC LIMIT 20");
        assertExplainUsesIndex("EXPLAIN SELECT * FROM outbox_events FORCE INDEX (idx_outbox_unpublished) "
                + "WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT 20");
        assertThat(order.status()).isEqualTo(OrderStatus.NEW);
    }

    private void fund(long userId, long assetId, long available) {
        jdbc.update("""
                INSERT INTO wallets (user_id, asset_id, available, locked, version)
                VALUES (?, ?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE available = ?, locked = 0, version = 0
                """, userId, assetId, available, available);
    }

    private void assertWallet(long userId, long assetId, long available, long locked) {
        var wallet = wallet(userId, assetId);
        assertThat(((Number) wallet.get("available")).longValue()).isEqualTo(available);
        assertThat(((Number) wallet.get("locked")).longValue()).isEqualTo(locked);
    }

    private Map<String, Object> wallet(long userId, long assetId) {
        return jdbc.queryForMap(
                "SELECT available, locked FROM wallets WHERE user_id = ? AND asset_id = ?",
                userId, assetId);
    }

    private void seedEthMarket() {
        jdbc.update("INSERT INTO assets (id, symbol, decimal_scale) VALUES (3, 'ETH', 6)");
        jdbc.update("""
                INSERT INTO markets (
                    id, symbol, base_asset_id, quote_asset_id, fee_wallet_user_id,
                    price_scale, quantity_scale, price_tick, quantity_step,
                    minimum_quantity, minimum_notional, maker_fee_bps, taker_fee_bps)
                VALUES (2, 'ETH/USDT', 3, 2, 0, 2, 6, 1, 1, 100, 1000000, 10, 20)
                """);
        fund(0, 3, 0);
    }

    private void assertExplainUsesIndex(String sql) {
        List<Map<String, Object>> plan = jdbc.queryForList(sql);
        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(row -> assertThat(row.get("key")).isNotNull());
    }

    private long count(String sql) {
        return value(sql);
    }

    private long value(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }

    private static PlaceOrderCommand sell(
            long userId, long price, long quantity, String key) {
        return new PlaceOrderCommand(
                userId, "BTC/USDT", OrderSide.SELL, OrderType.LIMIT,
                TimeInForce.GTC, price, null, quantity, key);
    }

    private static PlaceOrderCommand buy(
            long userId, long price, long quantity, String key) {
        return new PlaceOrderCommand(
                userId, "BTC/USDT", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, price, null, quantity, key);
    }
}
