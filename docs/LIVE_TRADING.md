# Live trading — activation procedure (disabled by default)

Live order placement exists as an **isolated, gated interface**. In the default configuration no bean able to reach an order endpoint is even
created (`GrowwOrders` needs `app.live.enabled=true` **and** `app.groww.enabled=true` **and** `app.data.source=GROWW`), and no automated test,
backtest or start-up path calls it. Claude/AI output has **no code path** into live ordering: it is parsed into a validated assessment
that only influences paper/backtest *gating*, and live proposals are created by an authenticated admin through `/api/live/orders/propose`.

## Preconditions (all enforced by `LiveExecutionGate`, re-checked on every proposal and approval)

1. `app.live.enabled=true` (configuration).
2. In-app authorisation: admin posts the exact phrase to `/api/live/authorize` (audited; stored in `application_settings`).
3. Groww authentication state `OK` (adapter active).
4. A successful **read-only capability probe** (`/api/live/probe-capabilities`: funds, quote, historical candles; never calls order endpoints) within 24 h.
5. Compliance attested: `app.live.compliance-confirmed=true` **and** in-app attestation (see `COMPLIANCE.md`).
6. Real market data (`DATA_SOURCE=GROWW`).
7. Per order: independent risk engine (re-run on a fresh quote at approval time), available funds ≥ order value × 1.02, `app.live.max-order-value`, broker-sourced metadata, long-only options, lot multiples.
8. Persistent audit log writable and kill switch **off**.
9. The dashboard labels the account scope **LIVE**; live rows (`run_mode='LIVE'`, `simulated=0`) are the only ones the schema allows to be non-simulated.

## Per-order workflow (manual approval of every order)

`propose` → row `PENDING_APPROVAL` (expires after `approval-ttl-minutes`) → human `approve` → submit once → `SUBMITTED`.

* **No automatic retries.** A timeout / 5xx / transport error during submission yields `UNKNOWN_RECONCILE`. Before any new attempt the broker
  is asked for the order by `order_reference_id`; an existing order is *adopted*, never re-sent. If the broker has no record, the order is only
  declared `FAILED` after a 2-minute wait.
* Rejections (400), auth expiry (401) and rate limits (429) end in `FAILED` for that proposal; nothing is retried.
* The kill switch blocks proposals and approvals immediately.

## Not covered

Live position/stop management, broker-side stop orders and live reconciliation of fills into P&L are **not** implemented; this interface places
manually approved entry orders and mirrors their status. Treat it as a scaffold to review, not a production execution system.
