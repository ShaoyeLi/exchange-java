package com.ethan.exchange.order.application;

import com.ethan.exchange.matching.domain.MatchResult;
import com.ethan.exchange.matching.domain.Order;
import com.ethan.exchange.matching.domain.OrderRequest;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderStateMachine;
import com.ethan.exchange.matching.domain.OrderStatus;
import com.ethan.exchange.matching.domain.OrderUpdate;
import com.ethan.exchange.matching.domain.Price;
import com.ethan.exchange.matching.domain.Quantity;
import com.ethan.exchange.matching.domain.SelfTradePolicy;
import com.ethan.exchange.matching.domain.Trade;
import com.ethan.exchange.matching.engine.MatchingEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ExchangeOrderService {

    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final ExchangeStore store;
    private final TransactionTemplate transactions;
    private final FailureInjector failureInjector;

    @Autowired
    public ExchangeOrderService(ExchangeStore store, TransactionTemplate transactions) {
        this(store, transactions, FailureInjector.NONE);
    }

    public ExchangeOrderService(
            ExchangeStore store,
            TransactionTemplate transactions,
            FailureInjector failureInjector
    ) {
        this.store = store;
        this.transactions = transactions;
        this.failureInjector = failureInjector;
    }

    public OrderCommandResult place(PlaceOrderCommand command) {
        return executeWithDeadlockRetry(() -> transactions.execute(status -> placeInTransaction(command)));
    }

    public OrderCommandResult cancel(CancelOrderCommand command) {
        return executeWithDeadlockRetry(() -> transactions.execute(status -> cancelInTransaction(command)));
    }

    private OrderCommandResult placeInTransaction(PlaceOrderCommand command) {
        String hash = command.requestHash();
        IdempotencyClaim claim = store.claimIdempotency(
                command.userId(), OperationType.PLACE_ORDER, command.idempotencyKey(), hash);
        if (claim.replay()) {
            return OrderCommandResult.from(store.loadOrder(claim.resultOrderId()), true);
        }

        MarketState market = store.lockMarket(command.market());
        List<StoredOrder> active = store.loadActiveOrders(market.marketId());
        MatchingEngine engine = MatchingEngine.restore(
                market.toDomain(),
                SelfTradePolicy.CANCEL_TAKER,
                market.lastArrivalSequence(),
                market.lastExecutionSequence(),
                active.stream().map(order -> order.restore(market)).toList());

        long arrivalSequence = increment(market.lastArrivalSequence(), "arrival sequence overflow");
        long orderId = store.insertDraft(command, market, arrivalSequence, hash);
        failureInjector.check(FailurePoint.AFTER_DRAFT_INSERT);

        OrderRequest request = toOrderRequest(command, market, orderId);
        MatchResult plan = engine.submit(request);
        StoredOrder taker = draft(orderId, command, market, arrivalSequence, hash);

        Map<Long, StoredOrder> orders = new HashMap<>();
        active.forEach(order -> orders.put(order.orderId(), order));
        orders.put(orderId, taker);

        Set<WalletKey> walletKeys = collectWalletKeys(plan, orders, taker, market);
        Map<WalletKey, WalletBalance> wallets = new HashMap<>(
                store.createAndLockWallets(walletKeys));

        long reservation = reservationFor(taker, market);
        taker = taker.side() == OrderSide.BUY
                ? taker.withReservation(0, reservation)
                : taker.withReservation(reservation, 0);
        orders.put(orderId, taker);
        WalletKey reserveKey = reservationWallet(taker, market);
        writeJournal(
                "RESERVE",
                "ORDER:" + orderId + ":RESERVE",
                List.of(new LedgerPosting(
                        reserveKey,
                        taker.side() == OrderSide.BUY ? "RESERVE_QUOTE" : "RESERVE_BASE",
                        ExactMath.negate(reservation),
                        reservation)),
                wallets);
        failureInjector.check(FailurePoint.AFTER_RESERVATION);

        Set<Long> changedOrderIds = new LinkedHashSet<>();
        changedOrderIds.add(orderId);
        for (Trade trade : plan.trades()) {
            settleTrade(trade, market, orders, wallets);
            changedOrderIds.add(trade.makerOrderId());
            changedOrderIds.add(trade.takerOrderId());
        }

        for (OrderUpdate update : plan.updates()) {
            StoredOrder current = requiredOrder(orders, update.orderId());
            orders.put(update.orderId(), current.apply(update));
            changedOrderIds.add(update.orderId());
        }

        for (long changedId : List.copyOf(changedOrderIds)) {
            StoredOrder order = requiredOrder(orders, changedId);
            if (OrderStateMachine.isTerminal(order.status())
                    && (order.reservedBaseRemaining() > 0 || order.reservedQuoteRemaining() > 0)) {
                releaseReservation(order, market, wallets);
                orders.put(changedId, order.clearReservation());
            }
        }

        for (long changedId : changedOrderIds) {
            store.updateOrder(requiredOrder(orders, changedId));
        }

        failureInjector.check(FailurePoint.BEFORE_OUTBOX_INSERT);
        for (long changedId : changedOrderIds) {
            StoredOrder changed = requiredOrder(orders, changedId);
            store.insertOutbox(
                    market.marketId(), "ORDER", changedId, increment(changed.version(),
                            "order event version overflow"), "ORDER_UPDATED", orderPayload(changed));
        }
        for (Trade trade : plan.trades()) {
            store.insertOutbox(
                    market.marketId(), "MARKET", market.marketId(), trade.executionSequence(),
                    "TRADE_EXECUTED", tradePayload(trade));
        }
        store.updateMarketSequences(
                market.marketId(), engine.lastArrivalSequence(), engine.lastExecutionSequence());
        store.completeIdempotency(
                command.userId(), OperationType.PLACE_ORDER, command.idempotencyKey(), orderId);
        return OrderCommandResult.from(requiredOrder(orders, orderId), false);
    }

    private OrderCommandResult cancelInTransaction(CancelOrderCommand command) {
        String hash = command.requestHash();
        IdempotencyClaim claim = store.claimIdempotency(
                command.userId(), OperationType.CANCEL_ORDER, command.idempotencyKey(), hash);
        if (claim.replay()) {
            return OrderCommandResult.from(store.loadOrder(claim.resultOrderId()), true);
        }

        MarketState market = store.lockMarket(command.market());
        StoredOrder order = store.lockOrder(command.orderId());
        if (order.marketId() != market.marketId()) {
            throw new ExchangeOperationException(ExchangeErrorCode.ORDER_NOT_FOUND,
                    "order does not belong to requested market");
        }
        if (order.userId() != command.userId()) {
            throw new ExchangeOperationException(ExchangeErrorCode.ORDER_NOT_OWNED,
                    "order does not belong to user");
        }
        if (OrderStateMachine.isTerminal(order.status())) {
            throw new ExchangeOperationException(ExchangeErrorCode.ORDER_NOT_ACTIVE,
                    "only active orders can be canceled");
        }

        WalletKey reserveKey = reservationWallet(order, market);
        Map<WalletKey, WalletBalance> wallets = new HashMap<>(
                store.createAndLockWallets(Set.of(reserveKey)));
        releaseReservation(order, market, wallets);
        StoredOrder canceled = order.cancelAndClearReservation();
        store.updateOrder(canceled);
        failureInjector.check(FailurePoint.BEFORE_OUTBOX_INSERT);
        store.insertOutbox(
                market.marketId(), "ORDER", order.orderId(), increment(order.version(),
                        "order event version overflow"), "ORDER_CANCELED", orderPayload(canceled));
        store.completeIdempotency(
                command.userId(), OperationType.CANCEL_ORDER,
                command.idempotencyKey(), order.orderId());
        return OrderCommandResult.from(canceled, false);
    }

    private void settleTrade(
            Trade trade,
            MarketState market,
            Map<Long, StoredOrder> orders,
            Map<WalletKey, WalletBalance> wallets
    ) {
        StoredOrder maker = requiredOrder(orders, trade.makerOrderId());
        StoredOrder taker = requiredOrder(orders, trade.takerOrderId());
        long quote = ExactMath.quote(trade.price().unscaled(), trade.quantity().unscaled());

        StoredOrder.FeeCharge makerCharge = maker.charge(OrderRole.MAKER, quote, market);
        StoredOrder.FeeCharge takerCharge = taker.charge(OrderRole.TAKER, quote, market);
        maker = makerCharge.order().fill(trade.quantity().unscaled());
        taker = takerCharge.order().fill(trade.quantity().unscaled());

        StoredOrder buyer = maker.side() == OrderSide.BUY ? maker : taker;
        StoredOrder seller = maker.side() == OrderSide.SELL ? maker : taker;
        long buyerFee = buyer.orderId() == maker.orderId()
                ? makerCharge.feeDelta() : takerCharge.feeDelta();
        long sellerFee = seller.orderId() == maker.orderId()
                ? makerCharge.feeDelta() : takerCharge.feeDelta();

        long newBuyerReservation = requiredBuyReservation(buyer, market);
        long releasedQuote = ExactMath.subtract(
                buyer.reservedQuoteRemaining(), newBuyerReservation);
        long buyerSpend = ExactMath.add(quote, buyerFee);
        long buyerRefund = ExactMath.subtract(releasedQuote, buyerSpend);
        if (buyerRefund < 0) {
            throw new ExchangeOperationException(ExchangeErrorCode.INSUFFICIENT_BALANCE,
                    "buy reservation did not cover trade and cumulative fee");
        }
        buyer = buyer.withReservation(0, newBuyerReservation);
        seller = seller.withReservation(
                ExactMath.subtract(seller.reservedBaseRemaining(), trade.quantity().unscaled()), 0);

        if (buyer.orderId() == maker.orderId()) {
            maker = buyer;
            taker = seller;
        } else {
            maker = seller;
            taker = buyer;
        }
        orders.put(maker.orderId(), maker);
        orders.put(taker.orderId(), taker);

        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(new LedgerPosting(
                new WalletKey(seller.userId(), market.baseAssetId()),
                "TRADE_SELL_BASE", 0, ExactMath.negate(trade.quantity().unscaled())));
        postings.add(new LedgerPosting(
                new WalletKey(buyer.userId(), market.baseAssetId()),
                "TRADE_BUY_BASE", trade.quantity().unscaled(), 0));
        postings.add(new LedgerPosting(
                new WalletKey(buyer.userId(), market.quoteAssetId()),
                "TRADE_BUY_QUOTE", buyerRefund, ExactMath.negate(releasedQuote)));
        postings.add(new LedgerPosting(
                new WalletKey(seller.userId(), market.quoteAssetId()),
                "TRADE_SELL_QUOTE", ExactMath.subtract(quote, sellerFee), 0));
        long totalFee = ExactMath.add(buyerFee, sellerFee);
        if (totalFee > 0) {
            postings.add(new LedgerPosting(
                    new WalletKey(market.feeWalletUserId(), market.quoteAssetId()),
                    "TRADE_FEE_QUOTE", totalFee, 0));
        }

        TradeRow row = new TradeRow(
                market.marketId(), trade.executionSequence(), trade.makerOrderId(),
                trade.takerOrderId(), trade.price().unscaled(), trade.quantity().unscaled(),
                quote, makerCharge.feeDelta(), takerCharge.feeDelta());
        store.insertTrade(row);
        failureInjector.check(FailurePoint.AFTER_TRADE_INSERT);
        writeJournal(
                "TRADE", "TRADE:" + market.marketId() + ":" + trade.executionSequence(),
                postings, wallets);
    }

    private void releaseReservation(
            StoredOrder order,
            MarketState market,
            Map<WalletKey, WalletBalance> wallets
    ) {
        long amount = order.side() == OrderSide.BUY
                ? order.reservedQuoteRemaining()
                : order.reservedBaseRemaining();
        if (amount == 0) {
            return;
        }
        WalletKey key = reservationWallet(order, market);
        writeJournal(
                "RELEASE",
                "ORDER:" + order.orderId() + ":RELEASE",
                List.of(new LedgerPosting(
                        key,
                        order.side() == OrderSide.BUY ? "RELEASE_QUOTE" : "RELEASE_BASE",
                        amount,
                        ExactMath.negate(amount))),
                wallets);
    }

    private void writeJournal(
            String type,
            String businessRef,
            List<LedgerPosting> postings,
            Map<WalletKey, WalletBalance> wallets
    ) {
        verifyBalanced(postings);
        String journalId = UUID.randomUUID().toString();
        store.insertJournal(journalId, type, businessRef);
        List<LedgerPosting> ordered = postings.stream()
                .filter(posting -> posting.availableDelta() != 0 || posting.lockedDelta() != 0)
                .sorted(Comparator.comparing(LedgerPosting::wallet)
                        .thenComparing(LedgerPosting::postingType))
                .toList();
        int walletUpdates = 0;
        for (LedgerPosting posting : ordered) {
            WalletBalance current = requiredWallet(wallets, posting.wallet());
            WalletBalance next = store.updateWallet(
                    current, posting.availableDelta(), posting.lockedDelta());
            wallets.put(posting.wallet(), next);
            walletUpdates++;
            if (walletUpdates == 2) {
                failureInjector.check(FailurePoint.AFTER_SECOND_WALLET_UPDATE);
            }
            failureInjector.check(FailurePoint.BEFORE_LEDGER_INSERT);
            store.insertLedgerEntry(
                    journalId, businessRef, next, posting.postingType(),
                    posting.availableDelta(), posting.lockedDelta());
        }
    }

    private static void verifyBalanced(List<LedgerPosting> postings) {
        Map<Long, Long> totals = new HashMap<>();
        for (LedgerPosting posting : postings) {
            long net = ExactMath.add(posting.availableDelta(), posting.lockedDelta());
            totals.merge(posting.wallet().assetId(), net, ExactMath::add);
        }
        if (totals.values().stream().anyMatch(total -> total != 0)) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "ledger journal is not balanced by asset: " + totals);
        }
    }

    private static Set<WalletKey> collectWalletKeys(
            MatchResult plan,
            Map<Long, StoredOrder> orders,
            StoredOrder taker,
            MarketState market
    ) {
        Set<WalletKey> keys = new HashSet<>();
        keys.add(reservationWallet(taker, market));
        for (Trade trade : plan.trades()) {
            StoredOrder maker = requiredOrder(orders, trade.makerOrderId());
            StoredOrder incoming = requiredOrder(orders, trade.takerOrderId());
            keys.add(new WalletKey(maker.userId(), market.baseAssetId()));
            keys.add(new WalletKey(maker.userId(), market.quoteAssetId()));
            keys.add(new WalletKey(incoming.userId(), market.baseAssetId()));
            keys.add(new WalletKey(incoming.userId(), market.quoteAssetId()));
            keys.add(new WalletKey(market.feeWalletUserId(), market.quoteAssetId()));
        }
        return keys;
    }

    private static long reservationFor(StoredOrder order, MarketState market) {
        return order.side() == OrderSide.SELL
                ? order.originalQuantity()
                : requiredBuyReservation(order, market);
    }

    private static long requiredBuyReservation(StoredOrder order, MarketState market) {
        long remainingMaximumQuote = ExactMath.quote(order.effectivePrice(), order.remainingQuantity());
        long remainingFeeBuffer = ExactMath.remainingFeeBuffer(
                order.cumulativeMakerQuote(), market.makerFeeBps(),
                order.cumulativeTakerQuote(), market.takerFeeBps(),
                remainingMaximumQuote, market.maximumFeeBps(),
                order.cumulativeFeeCharged());
        return ExactMath.add(remainingMaximumQuote, remainingFeeBuffer);
    }

    private static WalletKey reservationWallet(StoredOrder order, MarketState market) {
        long assetId = order.side() == OrderSide.BUY
                ? market.quoteAssetId()
                : market.baseAssetId();
        return new WalletKey(order.userId(), assetId);
    }

    private static StoredOrder draft(
            long orderId,
            PlaceOrderCommand command,
            MarketState market,
            long arrivalSequence,
            String hash
    ) {
        return new StoredOrder(
                orderId, command.userId(), market.marketId(), market.symbol(),
                command.side(), command.type(), command.timeInForce(), OrderStatus.NEW,
                command.price(), command.protectionPrice(), command.quantity(), command.quantity(),
                arrivalSequence, 0, 0, 0, 0, 0,
                command.idempotencyKey(), hash, 0);
    }

    private static OrderRequest toOrderRequest(
            PlaceOrderCommand command, MarketState market, long orderId) {
        Price price = command.price() == null
                ? null : Price.of(command.price(), market.priceScale());
        Price protection = command.protectionPrice() == null
                ? null : Price.of(command.protectionPrice(), market.priceScale());
        return new OrderRequest(
                orderId, command.userId(), market.toDomain().symbol(), command.side(),
                command.type(), command.timeInForce(), price, protection,
                Quantity.of(command.quantity(), market.quantityScale()));
    }

    private static StoredOrder requiredOrder(Map<Long, StoredOrder> orders, long orderId) {
        StoredOrder order = orders.get(orderId);
        if (order == null) {
            throw new ExchangeOperationException(ExchangeErrorCode.ORDER_NOT_FOUND,
                    "matching plan referenced unknown order: " + orderId);
        }
        return order;
    }

    private static WalletBalance requiredWallet(
            Map<WalletKey, WalletBalance> wallets, WalletKey key) {
        WalletBalance wallet = wallets.get(key);
        if (wallet == null) {
            throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                    "wallet was not collected before locking: " + key);
        }
        return wallet;
    }

    private static long increment(long value, String message) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW, message, exception);
        }
    }

    private static String orderPayload(StoredOrder order) {
        return "{\"orderId\":" + order.orderId()
                + ",\"userId\":" + order.userId()
                + ",\"side\":\"" + order.side().name() + "\""
                + ",\"orderType\":\"" + order.type().name() + "\""
                + ",\"timeInForce\":\"" + order.timeInForce().name() + "\""
                + ",\"status\":\"" + order.status().name() + "\""
                + ",\"price\":" + order.effectivePrice()
                + ",\"remainingQuantity\":" + order.remainingQuantity()
                + ",\"arrivalSequence\":" + order.arrivalSequence() + "}";
    }

    private static String tradePayload(Trade trade) {
        return "{\"executionSequence\":" + trade.executionSequence()
                + ",\"makerOrderId\":" + trade.makerOrderId()
                + ",\"takerOrderId\":" + trade.takerOrderId()
                + ",\"price\":" + trade.price().unscaled()
                + ",\"quantity\":" + trade.quantity().unscaled() + "}";
    }

    private <T> T executeWithDeadlockRetry(Supplier<T> work) {
        ConcurrencyFailureException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                T result = work.get();
                if (result == null) {
                    throw new IllegalStateException("transaction returned no result");
                }
                return result;
            } catch (ConcurrencyFailureException exception) {
                lastFailure = exception;
            }
        }
        throw new ExchangeOperationException(ExchangeErrorCode.CONCURRENT_UPDATE,
                "transaction failed after bounded deadlock retries", lastFailure);
    }
}
