# Reliable Events and Live Market Data

## Boundary

The event pipeline publishes committed trading outbox rows to Kafka with at-least-once delivery,
applies events through a transactional inbox, and builds replaceable Redis and
WebSocket views. MySQL remains authoritative for orders, funds, trades, and the
consumer's durable projection.

```text
MySQL business transaction
  order/wallet/trade/ledger + outbox
                 |
                 v
leased outbox relay -- broker acknowledgement --> mark published
                 |
                 v
          Kafka, keyed by market
                 |
                 v
DB transaction: inbox claim + durable market projection
                 |
                 +--> Redis projection (best effort, rebuildable)
                 +--> WebSocket delta (best effort, gap detectable)
```

## Event contract

Every message is one versioned JSON envelope:

- `eventId`: stable UUID reused on every retry.
- `aggregateType`, `aggregateId`, `aggregateVersion`: source identity and version.
- `market`, `marketSequence`: market identity and transactionally allocated,
  monotonically increasing sequence.
- `type`, `occurredAt`, `schemaVersion`, `payload`.

Kafka records use `market` as the key. Consumers reject unsupported schema
versions explicitly. Clients compare `marketSequence`; a gap or regression means
they must fetch a new snapshot before applying later deltas.

## Relay lease and crash behavior

One database row grants a bounded single-active relay lease. Multiple application
instances may compete, but only the current owner may claim and publish the next
ordered batch. This deliberately preserves global database order at the current scale; it
is not a high-throughput partitioned dispatcher claim.

The relay waits for the Kafka send future with a finite timeout. It marks an
outbox row published only after broker acknowledgement. A failure increments a
bounded attempt counter, records a sanitized error, and schedules exponential
backoff. Exhausted rows enter `DEAD` and require an audited manual replay.

| Crash window | Durable result |
|---|---|
| before business commit | neither business state nor outbox exists |
| after DB commit, before send | lease expiry allows a later relay to send |
| after Kafka acknowledgement, before published mark | same `eventId` is resent |
| relay dies while holding lease | another instance takes over after lease expiry |

## Inbox and derived state

The consumer inserts `(consumerName, eventId)` and its durable projection row in
one MySQL transaction. Duplicate inserts do not repeat the projection effect.
Kafka offset acknowledgement occurs only after that transaction succeeds.

Redis is updated after the durable consumer transaction. Redis failure is logged
and counted but cannot roll back or erase authoritative MySQL facts. A rebuild
operation deletes only the target market's Redis keys and recreates them from the
durable projection. Redis Pub/Sub or WebSocket fan-out is intentionally treated
as at-most-once live notification, never replay storage.

## WebSocket recovery and backpressure

Public clients first fetch a MySQL-backed snapshot with its last market sequence,
then subscribe to `/topic/markets/{market}`. A delta is applied only when its
sequence is exactly the expected next value; otherwise the client resynchronizes.

Each WebSocket session has a bounded outbound queue. A slow session is closed
instead of blocking the Kafka consumer or growing memory without limit. Only
public market events are broadcast. Private order and balance data remains
behind authenticated REST endpoints and server-side ownership checks.

## Verification

`ReliableEventPipelineIT` runs against MySQL, Kafka, and Redis containers. It
covers broker acknowledgement, resend after an acknowledgement window, bounded
retry, dead-letter routing, inbox deduplication, durable projection updates,
Redis failure isolation, and projection rebuild.

## References

- [Spring Kafka sending and broker-result futures](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html)
- [Spring Boot Kafka configuration](https://docs.spring.io/spring-boot/reference/messaging/kafka.html)
- [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/)
- [Testcontainers generic Redis container](https://java.testcontainers.org/features/creating_container/)
- [Redis Pub/Sub delivery boundary](https://redis.io/docs/latest/develop/pubsub/)
