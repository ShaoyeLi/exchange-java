# Verification

## Automated checks

Run the complete repository check with:

```bash
./scripts/verify.sh
```

This command executes:

- pure matching-domain and fixed-point arithmetic tests;
- ArchUnit package-dependency checks;
- MySQL transaction, locking, idempotency, rollback, restart, and constraint tests;
- Kafka/Redis outbox, inbox, retry, dead-letter, projection, and rebuild tests;
- EVM JSON-RPC decoding, confirmation, duplicate, cursor, and reorganization tests;
- authenticated API and observability integration tests;
- a clean TypeScript and Vite production build.

Integration tests use Testcontainers and require Docker.

## Runtime smoke test

With the full application stack running:

```bash
node scripts/smoke-test.mjs
```

The smoke test verifies login, same-key order replay, same-key conflict rejection, order cancellation, and release of the remaining reservation.

Optional short local load probes are available through `scripts/load-test.mjs`. They validate request flow and backlog drainage in a bounded development environment; they are not production throughput benchmarks.

## Evidence boundaries

A passing suite demonstrates the scenarios asserted by the checked-in tests. It does not prove production availability, custody security, multi-node matching safety, exhaustive smart-contract compatibility, or performance at exchange scale.

EVM tests exercise a real HTTP JSON-RPC adapter against controlled responses and a real MySQL state machine. They do not use production nodes or real funds. Browser behavior is validated by a production build and a local smoke walkthrough; there is no full Playwright end-to-end suite.
