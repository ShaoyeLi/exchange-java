# Application and Security Model

## Trust chain

```text
email/password -> BCrypt verification -> signed JWT
                                      |
React request -> Authorization header + Idempotency-Key
                                      |
Spring Security -> authenticated principal -> application command
                                      |
                                MySQL transaction
```

The order request DTO does not contain a trusted user ID. Controllers derive the caller from the JWT subject, and account queries, cancellations, and withdrawal cancellation enforce object ownership on the server. Regular users cannot access `/api/ops/**` or sensitive Actuator endpoints.

The application uses stateless bearer authentication. Demo credentials and the local JWT secret exist only to make the Docker Compose environment reproducible; deployments must provide their own values through environment variables.

## User workflows

The trading view combines:

- market configuration, L2 order-book snapshot, and recent trades;
- LIMIT/MARKET order entry with valid time-in-force choices;
- available and locked balances;
- active orders, order history, and the user's trades.

After an order or cancellation command, the client reloads authoritative snapshots. STOMP market events trigger public-data refreshes; they are notifications rather than financial truth.

The portfolio and activity views show balances, deposit states, withdrawal states, and reservations. A withdrawal request enters `REVIEW` and moves value from available to locked. Cancellation releases that reservation and appends ledger evidence. No code constructs, signs, or broadcasts an on-chain withdrawal transaction.

## Amount handling

The backend sends integer amounts together with their scale. The frontend uses `BigInt` to parse decimal input without floating-point arithmetic, rejects excessive decimal places and unsafe ranges, and formats values for display. Final balance and ledger calculations always remain server-side.

## Operations view

An administrator can inspect:

- pending and dead outbox records;
- deposits and withdrawals awaiting review;
- chain cursor, chain ID, scanner state, and blocking reason.

The operations page is intentionally read-only. Dead-letter replay, deposit adjudication, and withdrawal approval require auditable workflows and dual-control rules that are outside this implementation.

## Browser and API delivery

- `frontend/src/App.tsx` contains the trading, portfolio, activity, and operations views.
- `frontend/src/api.ts` centralizes JWT headers, idempotency keys, error handling, and WebSocket URLs.
- `frontend/nginx.conf` serves the SPA and proxies REST and STOMP traffic in the containerized stack.
- The root and frontend Dockerfiles use multi-stage builds; the backend image runs as a non-root user.
