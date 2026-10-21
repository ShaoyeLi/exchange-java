package com.ethan.exchange.matching.engine;

import com.ethan.exchange.matching.domain.DuplicateOrderException;
import com.ethan.exchange.matching.domain.AssetSymbol;
import com.ethan.exchange.matching.domain.DomainException;
import com.ethan.exchange.matching.domain.Market;
import com.ethan.exchange.matching.domain.MarketSymbol;
import com.ethan.exchange.matching.domain.MatchResult;
import com.ethan.exchange.matching.domain.OrderBookSnapshot;
import com.ethan.exchange.matching.domain.OrderRequest;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderStatus;
import com.ethan.exchange.matching.domain.Notional;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.Quantity;
import com.ethan.exchange.matching.domain.SelfTradePolicy;
import com.ethan.exchange.matching.domain.TimeInForce;
import com.ethan.exchange.matching.domain.UnknownOrderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingEngineTest {

    private static final MarketSymbol BTC_USDT = MarketSymbol.of("BTC/USDT");
    private static final Market MARKET = new Market(
            BTC_USDT, AssetSymbol.of("BTC"), AssetSymbol.of("USDT"),
            0, 0, Price.of(1, 0), Quantity.of(1, 0),
            Quantity.of(1, 0), Notional.of(1, 0));

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(MARKET);
    }

    @Test
    void matchesBestPriceBeforeWorsePriceAtMakerPrice() {
        engine.submit(sellLimit(1, 1, 10_500, 5, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_000, 5, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(3, 3, 11_000, 5, TimeInForce.GTC));

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().getFirst().makerOrderId()).isEqualTo(2);
        assertThat(result.trades().getFirst().price()).isEqualTo(p(10_000));
        assertThat(engine.snapshot(10).asks())
                .extracting(level -> level.price().unscaled())
                .containsExactly(10_500L);
    }

    @Test
    void preservesFifoWithinOnePriceLevel() {
        engine.submit(sellLimit(1, 1, 10_000, 3, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_000, 3, TimeInForce.GTC));
        engine.submit(sellLimit(4, 4, 10_000, 3, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(3, 3, 10_000, 4, TimeInForce.GTC));

        assertThat(result.trades())
                .extracting(trade -> trade.makerOrderId())
                .containsExactly(1L, 2L);
        assertThat(result.trades())
                .extracting(trade -> trade.quantity().unscaled())
                .containsExactly(3L, 1L);
        assertThat(engine.snapshot(10).asks().getFirst().quantity()).isEqualTo(q(5));
    }

    @Test
    void partiallyFillsMakerAndCancelReportsItsRemainingQuantity() {
        engine.submit(sellLimit(1, 1, 10_000, 10, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(2, 2, 10_000, 6, TimeInForce.GTC));

        assertThat(result.trades().getFirst().quantity()).isEqualTo(q(6));
        assertThat(result.updates().getFirst().status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(engine.cancel(1))
                .isEqualTo(new com.ethan.exchange.matching.domain.OrderUpdate(
                        1, OrderStatus.CANCELED, q(6), q(4)));
        assertThat(engine.snapshot(10).asks()).isEmpty();
    }

    @Test
    void unmatchedGtcLimitRestsButDoesNotCrossAnInferiorPrice() {
        engine.submit(sellLimit(1, 1, 10_100, 2, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(2, 2, 10_000, 5, TimeInForce.GTC));

        assertThat(result.trades()).isEmpty();
        assertThat(result.updates().getFirst().status()).isEqualTo(OrderStatus.NEW);
        assertThat(engine.snapshot(10).bids().getFirst().quantity()).isEqualTo(q(5));
    }

    @Test
    void marketOrderConsumesAvailableLiquidityAndCancelsRemainder() {
        engine.submit(sellLimit(1, 1, 10_000, 2, TimeInForce.GTC));

        MatchResult result = engine.submit(OrderRequest.market(
                2, 2, BTC_USDT, OrderSide.BUY, p(10_500), q(5)));

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().getFirst().quantity()).isEqualTo(q(2));
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(result.updates().getLast().filled()).isEqualTo(q(2));
        assertThat(result.updates().getLast().remaining()).isEqualTo(q(3));
        assertThatThrownBy(() -> engine.cancel(2)).isInstanceOf(UnknownOrderException.class);
    }

    @Test
    void marketProtectionPreventsUnboundedBookSweep() {
        engine.submit(sellLimit(1, 1, 10_000, 1, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_200, 1, TimeInForce.GTC));

        MatchResult result = engine.submit(OrderRequest.market(
                3, 3, BTC_USDT, OrderSide.BUY, p(10_100), q(2)));

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().getFirst().makerOrderId()).isEqualTo(1);
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(engine.snapshot(10).bestAsk()).contains(p(10_200));
    }

    @Test
    void defaultSelfTradePolicyCancelsTakerBeforeSelfMatch() {
        engine.submit(sellLimit(1, 7, 10_000, 2, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(2, 7, 10_000, 2, TimeInForce.GTC));

        assertThat(result.trades()).isEmpty();
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(engine.snapshot(10).asks().getFirst().quantity()).isEqualTo(q(2));
    }

    @Test
    void iocLimitNeverRests() {
        MatchResult result = engine.submit(buyLimit(1, 1, 10_000, 5, TimeInForce.IOC));

        assertThat(result.trades()).isEmpty();
        assertThat(result.updates().getFirst().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(engine.snapshot(10).bids()).isEmpty();
        assertThatThrownBy(() -> engine.cancel(1)).isInstanceOf(UnknownOrderException.class);
    }

    @Test
    void gtcPartialFillRestsItsRemainder() {
        engine.submit(sellLimit(1, 1, 10_000, 2, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(2, 2, 10_000, 5, TimeInForce.GTC));

        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(result.updates().getLast().remaining()).isEqualTo(q(3));
        assertThat(engine.snapshot(10).bids().getFirst().quantity()).isEqualTo(q(3));
    }

    @Test
    void iocPartialAndFullFillHaveDifferentTerminalQuantities() {
        engine.submit(sellLimit(1, 1, 10_000, 2, TimeInForce.GTC));
        MatchResult partial = engine.submit(buyLimit(2, 2, 10_000, 3, TimeInForce.IOC));
        assertThat(partial.updates().getLast().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(partial.updates().getLast().remaining()).isEqualTo(q(1));

        engine.submit(sellLimit(3, 3, 10_000, 2, TimeInForce.GTC));
        MatchResult full = engine.submit(buyLimit(4, 4, 10_000, 2, TimeInForce.IOC));
        assertThat(full.updates().getLast().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(full.updates().getLast().remaining()).isEqualTo(Quantity.zero(0));
    }

    @Test
    void protectedMarketSellMatchesBidAndCancelsRemainder() {
        engine.submit(buyLimit(1, 1, 10_000, 2, TimeInForce.GTC));
        engine.submit(buyLimit(2, 2, 9_800, 2, TimeInForce.GTC));

        MatchResult result = engine.submit(OrderRequest.market(
                3, 3, BTC_USDT, OrderSide.SELL, p(9_900), q(3)));

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().getFirst().price()).isEqualTo(p(10_000));
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(engine.snapshot(10).bestBid()).contains(p(9_800));
    }

    @Test
    void insufficientFokDoesNotMutateRestingOrders() {
        engine.submit(sellLimit(1, 1, 10_000, 2, TimeInForce.GTC));
        OrderBookSnapshot before = engine.snapshot(10);

        MatchResult result = engine.submit(buyLimit(2, 2, 10_000, 3, TimeInForce.FOK));

        assertThat(result.trades()).isEmpty();
        assertThat(result.updates().getFirst().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(engine.snapshot(10).asks()).isEqualTo(before.asks());
        assertThatThrownBy(() -> engine.cancel(2)).isInstanceOf(UnknownOrderException.class);
        assertThat(engine.cancel(1).remaining()).isEqualTo(q(2));
    }

    @Test
    void fokBlockedBySelfOrderHasNoPartialSideEffect() {
        engine.submit(sellLimit(1, 1, 10_000, 1, TimeInForce.GTC));
        engine.submit(sellLimit(2, 7, 10_000, 1, TimeInForce.GTC));
        engine.submit(sellLimit(3, 3, 10_000, 1, TimeInForce.GTC));
        OrderBookSnapshot before = engine.snapshot(10);

        MatchResult result = engine.submit(buyLimit(4, 7, 10_000, 2, TimeInForce.FOK));

        assertThat(result.trades()).isEmpty();
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(engine.snapshot(10).asks()).isEqualTo(before.asks());
        assertThat(engine.cancel(1).remaining()).isEqualTo(q(1));
    }

    @Test
    void allowSelfTradePolicyExecutesAgainstSameUsersMaker() {
        MatchingEngine allow = new MatchingEngine(MARKET, SelfTradePolicy.ALLOW);
        allow.submit(sellLimit(1, 7, 10_000, 2, TimeInForce.GTC));

        MatchResult result = allow.submit(buyLimit(2, 7, 10_000, 2, TimeInForce.GTC));

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().getFirst().makerOrderId()).isEqualTo(1);
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void sufficientFokFillsAcrossPriceLevelsWithoutResting() {
        engine.submit(sellLimit(1, 1, 9_900, 2, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_000, 3, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(3, 3, 10_000, 5, TimeInForce.FOK));

        assertThat(result.trades())
                .extracting(trade -> trade.price().unscaled())
                .containsExactly(9_900L, 10_000L);
        assertThat(result.updates().getLast().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(engine.snapshot(10).asks()).isEmpty();
    }

    @Test
    void takerCanFillAcrossThreePriceLevelsAtEachMakerPrice() {
        engine.submit(sellLimit(1, 1, 9_900, 2, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_000, 2, TimeInForce.GTC));
        engine.submit(sellLimit(3, 3, 10_100, 3, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(4, 4, 10_100, 6, TimeInForce.GTC));

        assertThat(result.trades())
                .extracting(trade -> trade.price().unscaled())
                .containsExactly(9_900L, 10_000L, 10_100L);
        assertThat(result.trades())
                .extracting(trade -> trade.quantity().unscaled())
                .containsExactly(2L, 2L, 2L);
        assertThat(engine.snapshot(10).asks().getFirst().quantity()).isEqualTo(q(1));
    }

    @Test
    void sellTakerMatchesHighestBidFirst() {
        engine.submit(buyLimit(1, 1, 10_000, 2, TimeInForce.GTC));
        engine.submit(buyLimit(2, 2, 10_100, 2, TimeInForce.GTC));

        MatchResult result = engine.submit(sellLimit(3, 3, 9_900, 2, TimeInForce.GTC));

        assertThat(result.trades().getFirst().makerOrderId()).isEqualTo(2);
        assertThat(result.trades().getFirst().price()).isEqualTo(p(10_100));
    }

    @Test
    void snapshotAggregatesLevelsOrdersBothSidesAndAppliesDepth() {
        engine.submit(buyLimit(1, 1, 9_900, 2, TimeInForce.GTC));
        engine.submit(buyLimit(2, 2, 9_900, 3, TimeInForce.GTC));
        engine.submit(buyLimit(3, 3, 9_800, 4, TimeInForce.GTC));
        engine.submit(sellLimit(4, 4, 10_100, 6, TimeInForce.GTC));
        engine.submit(sellLimit(5, 5, 10_200, 7, TimeInForce.GTC));

        OrderBookSnapshot snapshot = engine.snapshot(1);

        assertThat(snapshot.bids()).hasSize(1);
        assertThat(snapshot.bids().getFirst().price()).isEqualTo(p(9_900));
        assertThat(snapshot.bids().getFirst().quantity()).isEqualTo(q(5));
        assertThat(snapshot.asks()).hasSize(1);
        assertThat(snapshot.asks().getFirst().price()).isEqualTo(p(10_100));
        assertThat(snapshot.bestBid()).contains(p(9_900));
        assertThat(snapshot.bestAsk()).contains(p(10_100));
    }

    @Test
    void rejectsWrongMarketDuplicateIdsAndUnknownCancellation() {
        engine.submit(buyLimit(1, 1, 9_900, 2, TimeInForce.GTC));

        assertThatThrownBy(() -> engine.submit(buyLimit(1, 2, 9_800, 1, TimeInForce.GTC)))
                .isInstanceOf(DuplicateOrderException.class);
        assertThatThrownBy(() -> engine.submit(OrderRequest.limit(
                2, 2, MarketSymbol.of("ETH/USDT"), OrderSide.BUY,
                TimeInForce.GTC, p(100), q(1))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("engine market");
        assertThatThrownBy(() -> engine.cancel(999)).isInstanceOf(UnknownOrderException.class);
    }

    @Test
    void rejectsReusedIdsAfterFillAndCancellation() {
        engine.submit(sellLimit(1, 1, 10_000, 1, TimeInForce.GTC));
        engine.submit(buyLimit(2, 2, 10_000, 1, TimeInForce.GTC));
        assertThatThrownBy(() -> engine.submit(sellLimit(1, 3, 10_000, 1, TimeInForce.GTC)))
                .isInstanceOf(DuplicateOrderException.class);

        engine.submit(buyLimit(3, 3, 9_000, 1, TimeInForce.GTC));
        engine.cancel(3);
        assertThatThrownBy(() -> engine.submit(buyLimit(3, 4, 9_000, 1, TimeInForce.GTC)))
                .isInstanceOf(DuplicateOrderException.class);
    }

    @Test
    void failedCancellationCasesLeaveBookUnchanged() {
        engine.submit(buyLimit(1, 1, 9_000, 1, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_000, 1, TimeInForce.GTC));
        engine.submit(buyLimit(3, 3, 10_000, 1, TimeInForce.GTC));
        engine.submit(buyLimit(4, 4, 8_000, 1, TimeInForce.GTC));
        engine.cancel(4);
        OrderBookSnapshot before = engine.snapshot(10);

        assertThatThrownBy(() -> engine.cancel(MarketSymbol.of("ETH/USDT"), 1))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> engine.cancel(2)).isInstanceOf(UnknownOrderException.class);
        assertThatThrownBy(() -> engine.cancel(4)).isInstanceOf(UnknownOrderException.class);
        assertThat(engine.snapshot(10)).isEqualTo(before);
    }

    @Test
    void cancelingPartiallyFilledMakerPreservesFollowingFifo() {
        engine.submit(sellLimit(1, 1, 10_000, 3, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_000, 2, TimeInForce.GTC));
        engine.submit(sellLimit(3, 3, 10_000, 2, TimeInForce.GTC));
        engine.submit(buyLimit(4, 4, 10_000, 1, TimeInForce.IOC));
        engine.cancel(1);

        MatchResult result = engine.submit(buyLimit(5, 5, 10_000, 3, TimeInForce.IOC));

        assertThat(result.trades()).extracting(trade -> trade.makerOrderId())
                .containsExactly(2L, 3L);
        assertThat(result.trades()).extracting(trade -> trade.quantity().unscaled())
                .containsExactly(2L, 1L);
    }

    @Test
    void sequenceExhaustionFailsBeforeMutatingBookOrAcceptingOrder() {
        MatchingEngine arrivalExhausted = new MatchingEngine(
                MARKET, SelfTradePolicy.ALLOW, Long.MAX_VALUE, 0);
        OrderBookSnapshot empty = arrivalExhausted.snapshot(10);
        assertThatThrownBy(() -> arrivalExhausted.submit(
                buyLimit(1, 1, 10_000, 1, TimeInForce.GTC)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(com.ethan.exchange.matching.domain.DomainErrorCode.NUMERIC_OVERFLOW);
        assertThat(arrivalExhausted.snapshot(10)).isEqualTo(empty);

        MatchingEngine executionExhausted = new MatchingEngine(
                MARKET, SelfTradePolicy.ALLOW, 0, Long.MAX_VALUE);
        executionExhausted.submit(sellLimit(1, 1, 10_000, 2, TimeInForce.GTC));
        OrderBookSnapshot before = executionExhausted.snapshot(10);
        assertThatThrownBy(() -> executionExhausted.submit(
                buyLimit(2, 2, 10_000, 1, TimeInForce.IOC)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(com.ethan.exchange.matching.domain.DomainErrorCode.NUMERIC_OVERFLOW);
        assertThat(executionExhausted.snapshot(10)).isEqualTo(before);
        assertThat(executionExhausted.cancel(1).remaining()).isEqualTo(q(2));
    }

    @Test
    void sameCommandStreamProducesExactlyTheSameObservableState() {
        List<OrderRequest> commands = List.of(
                sellLimit(1, 1, 10_100, 3, TimeInForce.GTC),
                sellLimit(2, 2, 10_000, 2, TimeInForce.GTC),
                buyLimit(3, 3, 10_100, 4, TimeInForce.GTC),
                buyLimit(4, 4, 9_900, 5, TimeInForce.GTC)
        );
        MatchingEngine baseline = new MatchingEngine(MARKET);
        List<MatchResult> expected = commands.stream().map(baseline::submit).toList();
        OrderBookSnapshot expectedBook = baseline.snapshot(10);

        for (int replay = 0; replay < 100; replay++) {
            MatchingEngine candidate = new MatchingEngine(MARKET);
            assertThat(commands.stream().map(candidate::submit).toList()).isEqualTo(expected);
            assertThat(candidate.snapshot(10)).isEqualTo(expectedBook);
        }
    }

    @Test
    void executionSequencesIncreaseAcrossTrades() {
        engine.submit(sellLimit(1, 1, 10_000, 1, TimeInForce.GTC));
        engine.submit(sellLimit(2, 2, 10_100, 1, TimeInForce.GTC));

        MatchResult result = engine.submit(buyLimit(3, 3, 10_100, 2, TimeInForce.GTC));

        assertThat(result.trades())
                .extracting(trade -> trade.executionSequence())
                .containsExactly(1L, 2L);
    }

    @Test
    void snapshotRejectsNonPositiveDepthAndReturnedCollectionsAreImmutable() {
        engine.submit(buyLimit(1, 1, 10_000, 1, TimeInForce.GTC));
        OrderBookSnapshot snapshot = engine.snapshot(10);

        assertThatThrownBy(() -> engine.snapshot(0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> snapshot.bids().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void separateMarketEnginesKeepBooksAndSequencesIsolated() {
        Market ethMarket = new Market(
                MarketSymbol.of("ETH/USDT"), AssetSymbol.of("ETH"), AssetSymbol.of("USDT"),
                0, 0, p(1), q(1), q(1), Notional.of(1, 0));
        MatchingEngine eth = new MatchingEngine(ethMarket);

        engine.submit(buyLimit(1, 1, 10_000, 2, TimeInForce.GTC));
        eth.submit(OrderRequest.limit(1, 2, ethMarket.symbol(), OrderSide.SELL,
                TimeInForce.GTC, p(2_000), q(3)));

        assertThat(engine.snapshot(10).bids()).hasSize(1);
        assertThat(engine.snapshot(10).asks()).isEmpty();
        assertThat(eth.snapshot(10).asks()).hasSize(1);
        assertThat(engine.snapshot(10).lastArrivalSequence()).isEqualTo(1);
        assertThat(eth.snapshot(10).lastArrivalSequence()).isEqualTo(1);
    }

    @Test
    void synchronizedEntryPreventsConcurrentBookCorruption() throws Exception {
        int orders = 20;
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<MatchResult>>();
            for (int index = 1; index <= orders; index++) {
                long orderId = index;
                futures.add(executor.submit(() -> engine.submit(
                        buyLimit(orderId, orderId, 10_000, 1, TimeInForce.GTC))));
            }
            for (var future : futures) {
                future.get(2, TimeUnit.SECONDS);
            }
        }

        assertThat(engine.snapshot(10).bids().getFirst().quantity()).isEqualTo(q(orders));
        MatchResult result = engine.submit(sellLimit(10_000, 10_000, 10_000, orders, TimeInForce.IOC));
        assertThat(result.trades()).hasSize(orders);
        assertThat(result.trades()).extracting(trade -> trade.makerOrderId())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        LongStream.rangeClosed(1, orders).boxed().toList());
    }

    private static OrderRequest buyLimit(
            long orderId, long userId, long price, long quantity, TimeInForce timeInForce) {
        return OrderRequest.limit(orderId, userId, BTC_USDT, OrderSide.BUY,
                timeInForce, p(price), q(quantity));
    }

    private static OrderRequest sellLimit(
            long orderId, long userId, long price, long quantity, TimeInForce timeInForce) {
        return OrderRequest.limit(orderId, userId, BTC_USDT, OrderSide.SELL,
                timeInForce, p(price), q(quantity));
    }

    private static Price p(long value) {
        return Price.of(value, 0);
    }

    private static Quantity q(long value) {
        return Quantity.of(value, 0);
    }
}
