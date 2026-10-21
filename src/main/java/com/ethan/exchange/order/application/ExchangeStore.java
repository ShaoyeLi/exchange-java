package com.ethan.exchange.order.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ExchangeStore {

    IdempotencyClaim claimIdempotency(
            long userId, OperationType operation, String key, String requestHash);

    void completeIdempotency(
            long userId, OperationType operation, String key, long orderId);

    MarketState lockMarket(String symbol);

    List<StoredOrder> loadActiveOrders(long marketId);

    StoredOrder loadOrder(long orderId);

    StoredOrder lockOrder(long orderId);

    long insertDraft(
            PlaceOrderCommand command,
            MarketState market,
            long arrivalSequence,
            String requestHash);

    Map<WalletKey, WalletBalance> createAndLockWallets(Set<WalletKey> keys);

    WalletBalance updateWallet(WalletBalance current, long availableDelta, long lockedDelta);

    void insertJournal(String journalId, String type, String businessRef);

    void insertLedgerEntry(
            String journalId, String businessRef, WalletBalance wallet,
            String postingType, long availableDelta, long lockedDelta);

    void updateOrder(StoredOrder order);

    void insertTrade(TradeRow trade);

    void updateMarketSequences(long marketId, long lastArrival, long lastExecution);

    void insertOutbox(
            long marketId,
            String aggregateType,
            long aggregateId,
            long aggregateVersion,
            String eventType,
            String payload);
}
