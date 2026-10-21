# Architecture and Data Ownership

## Runtime topology

Exchange Java is a modular monolith. Trading commands execute in one Spring Boot process, while package boundaries keep transport, application orchestration, matching, persistence, messaging, chain indexing, and observability concerns separate. The matching domain has no dependency on Spring, JDBC, Kafka, Redis, or Web3j.

```text
React client
  | Authorization: Bearer <JWT>
  | Idempotency-Key: <client-generated key>
  v
REST controllers
  v
application transaction --------> transaction-local matching engine
  |
  +--> MySQL: orders / wallets / trades / ledger / outbox
                                      |
                                      v
                              leased outbox relay
                                      |
                                      v
                     Kafka -> inbox + durable projection
                                   |                |
                                   v                v
                              Redis view       STOMP clients

EVM JSON-RPC -> bounded scanner -> blocks / cursor / deposits
                                     |
                                     v
                              confirmation gate
                                     |
                                     v
                              wallet + deposit ledger
```

## Order command flow

1. Spring Security verifies the JWT and derives the authenticated user. The client cannot provide a trusted `userId` in the order body.
2. The application claims `(user, operation, idempotency key)` and compares a canonical request hash.
3. MySQL locks the market sequencer and loads active orders in persisted price-time order.
4. A transaction-local matcher restores the active book, reserves the taker's funds, and produces trades and order updates.
5. The same database transaction writes orders, trades, wallet balances, ledger journals, ledger entries, market sequences, and outbox events.
6. A relay publishes committed outbox rows to Kafka and marks them published only after broker acknowledgement.
7. A consumer commits its inbox claim and durable market projection together, then updates Redis and WebSocket subscribers on a best-effort basis.

If any database step fails before commit, the complete command rolls back. Kafka, Redis, and WebSocket never determine whether a financial command committed.

## State ownership

| State | Authoritative owner | Replaceable or derived copy |
|---|---|---|
| Orders and order status | MySQL `orders` | React view |
| Available and locked balances | MySQL `wallets` + ledger evidence | React view |
| Trades | MySQL `trades` | Market projection, Redis, WebSocket |
| Idempotency result | MySQL `idempotency_records` | None |
| Event delivery state | MySQL `outbox_events` / `inbox_events` | Kafka record |
| Market snapshot | MySQL durable projection | Redis and browser state |
| Deposit state | MySQL chain tables | Operations page |

## Numeric and accounting model

Prices, quantities, balances, and fees use scaled integer values rather than `double`. A market defines price and quantity scales, valid increments, minimums, and maker/taker fee rates.

- A BUY order locks quote currency for its protected notional plus a fee buffer.
- A SELL order locks base currency.
- Each fill moves base from seller locked balance to buyer available balance.
- Quote moves from buyer locked balance to seller available balance and the fee wallet.
- Better-than-limit execution releases unused quote and fee buffer.
- Cancellation releases only the reservation still attached to that order.

Ledger journals and entries are append-only. Internal trade journals balance per asset; deposits and withdrawals are explicit external-boundary events.

## EVM and internal balance states

```text
canonical Transfer -> SEEN -> confirmation threshold -> CREDITED
                         |                              |
shallow reorganization -> ORPHANED     credited reorg -> BLOCKED
```

An ERC-20 log is identified by `(chainId, transactionHash, logIndex)`. Scanning and crediting are separate transactions. A shallow reorganization can rewind uncredited events. If a reorganization affects an already credited deposit, the network is blocked for operator reconciliation rather than silently deleting ledger history.

## Trust and observability

- Login, public market data, WebSocket handshake, and health probes are public.
- Account, order, and withdrawal APIs require a valid JWT and derive ownership from the token subject.
- Operations and sensitive Actuator endpoints require the `ADMIN` role.
- Request IDs, traces, operation timers, error counters, and backlog gauges connect transport symptoms to business operations.

## Deliberate boundaries

The implementation excludes private-key custody, withdrawal transaction signing and broadcasting, hot/cold-wallet operations, MPC/HSM, AML/KYT, replicated matching consensus, multi-region failover, and production capacity guarantees.

Kafka delivery is at least once. Stable event IDs and a transactional inbox prevent duplicate projection effects; they do not turn Kafka and MySQL into one distributed transaction.
