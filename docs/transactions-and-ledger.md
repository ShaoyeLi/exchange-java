# Transactions, Reservations, and Ledger

## Goal

An order command becomes one recoverable MySQL commit containing the
order, balance reservations, trades, settlement entries, order updates, and
outbox records. MySQL is authoritative; a long-lived in-memory book is not.

## Why the in-memory book is not durable state

An in-memory order book cannot participate in a database rollback. Mutating it
before commit creates a split state when SQL fails; mutating it after commit
creates a crash window before memory catches up. This implementation therefore chooses a slower
but explicit correctness model:

1. Claim or load the idempotency row inside the transaction.
2. Lock the market sequencer row with `SELECT ... FOR UPDATE`.
3. Load active orders in persisted price-time order and restore a transaction-local
   engine with each order's original quantity, remaining quantity, status, arrival
   sequence, used order ID, and the market's last execution sequence.
4. Insert the new order as an uncommitted draft and calculate a matching plan in
   the transaction-local engine without changing any shared in-memory state.
5. Collect every buyer, seller, and fee wallet touched by the plan. Create missing
   wallet rows with sorted idempotent inserts, then lock the complete wallet set in
   deterministic `(user_id, asset_id)` order.
6. Reserve the taker's funds and apply all planned settlement postings.
7. Persist order updates, trades, wallet balances, append-only ledger entries, and
   outbox rows. Advance both market arrival and execution sequences.
8. Commit once; discard the transaction-local engine and every SQL change on rollback.

This model is deliberately not presented as a high-throughput production
matcher. A later version may replace rebuilding with a replicated command log
and snapshot recovery, while preserving the same domain and ledger contracts.

## Tables

- `assets`: symbol and scale.
- `markets`: base/quote assets, tick, step, minimums, maker/taker fee rates,
  `last_arrival_sequence`, and `last_execution_sequence`. These are the last
  committed values; only a successful transaction advances them.
- `wallets`: unique `(user_id, asset_id)`, available, locked, version.
- `orders`: typed order fields, original/remaining quantity, arrival sequence,
  status, `reserved_base_remaining`, `reserved_quote_remaining`, idempotency key,
  request hash, cumulative maker/taker filled quote, cumulative charged fee, and
  version.
- `trades`: market sequence, maker/taker IDs, maker price, quantity and fees.
- `ledger_journals`: one immutable journal ID per reserve, release, trade, deposit,
  withdrawal, or adjustment operation.
- `ledger_entries`: append-only wallet postings with available/locked deltas. A
  journal groups the postings; `(business_ref, wallet_id, posting_type)` uniquely
  identifies each posting without preventing multiple postings in one journal.
- `outbox_events`: committed domain events awaiting publication.

All numeric amounts are fixed-point `BIGINT`. Database checks enforce non-negative
wallet balances and valid order quantities in addition to application checks.

## Idempotency contract

The unique key is `(user_id, operation_type, idempotency_key)`. The row also
stores a canonical request hash and the resulting order ID.

- Same key and same hash: return the original result.
- Same key and different hash: return an idempotency conflict.
- Concurrent inserts: the database unique constraint chooses one financial
  effect; the losing request reloads and applies the same-key rules.

## Restoration contract

The matching engine exposes a restoration API that inserts active persisted
orders without matching them. Restoration preserves `originalQuantity`,
`remainingQuantity`, `PARTIALLY_FILLED` status, `arrivalSequence`, and order ID.
Rows are loaded by side, price priority, and arrival sequence; FIFO never uses a
reload timestamp. The engine starts from the two persisted `last_*_sequence`
values and increments only for a new accepted command or trade. Rollback therefore
does not consume a committed sequence.
Database-generated order IDs and the primary key prevent reuse of terminal IDs;
active IDs are also restored into the engine index.

## Reservation and settlement

- Maker and taker fee rates are basis points configured on the market and are
  restricted to 0 through 10,000 inclusive. Both fees are charged in quote asset.
- Fees use cumulative-difference charging per order, not independent rounding per
  fill. Persist cumulative maker quote, cumulative taker quote, and fee already
  charged. After a fill, compute
  `ceil((makerQuote * makerBps + takerQuote * takerBps) / 10_000)` and charge only
  the increase over the persisted cumulative fee. Intermediate multiplication uses
  checked `BigInteger`; the final fee must fit `BIGINT`. Fee totals therefore do
  not depend on how an order is split across fills.
- SELL reserves base quantity and persists the same value as
  `reserved_base_remaining`.
- BUY reserves `limit-or-protection price * quantity +
  ceil(total quote * max(makerBps, takerBps) / 10_000)` and persists it as
  `reserved_quote_remaining`. The cumulative weighted fee cannot exceed this
  upper bound even when the order is first taker and later maker.
- After one or more fills, required buy reservation is recomputed from the exact
  remaining quantity. `old reservation - new required reservation` leaves locked;
  actual trade quote plus buyer fee is spent, and the difference is released to
  available as price improvement or unused fee buffer.
- Each fill moves base from seller locked to buyer available. It moves quote from
  buyer locked to seller available and the explicit fee wallet. Buyer and seller
  fees are separate postings and together equal the fee wallet credit.
- A seller's quote proceeds are `trade quote - seller fee`; its order-level base
  reservation decreases by exactly the filled quantity.
- Cancellation releases the persisted order-level remaining reservation. It never
  infers an order's ownership from the wallet's aggregate locked balance.

Every wallet delta and its ledger entry are inserted in the same transaction.
For each internal journal and asset, the sum of `delta_available + delta_locked`
is zero. Historical journals and entries are never updated or deleted.

## Lock order and failure behavior

Every command uses one global lock order: idempotency row, one market sequencer,
then the complete sorted wallet-key set. Missing wallets are inserted idempotently
in that same sorted order before `SELECT ... FOR UPDATE`. No code may reserve a
wallet before all affected wallets are known and locked. Deadlocks receive a
bounded retry outside the transaction. Any injected failure before commit must
leave no order, trade, balance, ledger, or outbox partial result.

## Verification

`ExchangeTransactionIT` uses MySQL 8.4 through Testcontainers to cover rollback,
concurrency, idempotency, partial fills, cancellation, price improvement, schema
constraints, and order-book restoration after creating a new service instance.
