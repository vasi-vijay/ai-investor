# Delivery report (phases 1–10)

All commands below were actually executed in the build sandbox (Linux, JDK 21.0.12, Maven 3.9.11, Node 22, MariaDB 10.11.14, Redis 7.0.15). Docker has no daemon there.

| Phase | Delivered | Verified by |
|---|---|---|
| 1 Plan | `docs/PLAN.md`; toolchain/docs reachability probed | `java -version`, `mvn -v`, `docker info`, `curl` probes (groww.in, sebi.gov.in, nseindia.com, docs.anthropic.com → blocked; PyPI/Maven/npm → reachable) |
| 2 Backend, schema, providers, indicators, strategies | Maven project; Flyway V1/V2 (14 tables); the 5 provider interfaces + mock + synthetic market; indicators; 3 strategies | `mvn compile`; migrations applied on **MariaDB** and H2; indicator + strategy tests |
| 3 Claude + audit | Official Anthropic Java SDK gateway (structured output), validator, prompt/snapshot, mock stand-in, full decision/AI record persistence | validator/service tests (malformed, timeout, refusal, budget, reuse); **no live Claude call** (no key) |
| 4 Risk + paper | Risk engine (equity & long-option models), persistent daily lock, kill switch, paper broker (slippage, partial fills, costs, stops), EOD reconciliation | 21 risk + 14 broker + 6 cost tests; restart-recovery tests |
| 5 Groww | Auth (token/TOTP/approval), HTTP transport, quotes, candles, account, instrument master, order provider (dormant) | 20 tests vs a local fake server; **never run against real Groww** |
| 6 Backtests | Same-engine backtests; A/B/C modes; dev/validation/OOS split; paired + cohort analysis; uncertainty | determinism, no-look-ahead, isolation, reconciliation tests; 60-day suite on MariaDB (`PILOT_REPORT.md`) |
| 7 Dashboard | React/TS (7 tabs), REST, websocket push, Spring Security (session, CSRF, roles, rate limit, redaction) | `tsc`, `vite build`, vitest; Chromium screenshots (desktop + 390px, no horizontal overflow); 8 API/security tests |
| 8 Live (dormant) | Gate, manual-approval workflow, ambiguity-safe reconcile, capability probe | 15 tests with mock broker; default-disabled proofs |
| 9 Build/test/e2e/security | see below | see below |
| 10 Docs + report | README, `docs/*`, `PILOT_REPORT.md`, compliance register | — |

## Test & check results
* `mvn test` (H2, MySQL mode): **137 tests, 0 failures**.
* Same suite on real MariaDB (all but the H2-catalog `MigrationTest`): **130 tests, 0 failures**.
* Frontend: `tsc --noEmit` clean, `vite build` ok, vitest 3/3. `npm audit --omit=dev`: 0 vulnerabilities.
* End-to-end on MariaDB + Redis: app boot → CSRF login → replay-driven paper trading → dashboard data; **`kill -9` + restart** resumed the same paper run with 2 open positions recovered and no cycle errors.
* 60-trading-day, 9-run comparison suite completed (~11 min); see `PILOT_REPORT.md`.
* Secret scan of logs and repository: no credentials found. Java dependency CVE scanning was **not** performed (no offline vulnerability database).

## Defects the testing found (and fixed)
1. VWAP "fresh trigger" filter only suppressed signals when alignment held on *all* of the previous N bars, allowing N repeat signals → now "any of the previous N bars".
2. AI validator's plausibility band rejected a legitimate echo of the strategy's own stop (6.6 ATR away) → band now includes the proposal's reach.
3. Mock option strikes were centred on a stale base price, so ATM contracts did not exist after drift → centred on expiry-week spot (found via the dashboard).
4. `LiveMarketView` keyed its cache by DB id (null for unpersisted option candidates) → NPE → keyed by symbol.
5. **MySQL-only:** driver returns `LocalDateTime`/`Boolean` where H2 returned `Timestamp`/numbers → ClassCastExceptions in backtest cohorts, EOD outcomes and live approvals, and broken `ai_agrees === 1` checks in the UI → driver-agnostic helpers, booleans normalised server-side.
6. Column widths (`SHORT`, `outcome_status`, long mock symbols) caught by strict-mode DBs.
7. Live approval re-checked gate and funds but not the risk engine on a fresh quote → now re-evaluated at approval.

## Known issues / limitations
See README "Known limitations", `GROWW_INTEGRATION.md` (⚠ unverified items, streaming not implemented) and `LIVE_TRADING.md` (no live position management).

## External credentials / subscriptions still required
Anthropic API key (for real Claude analysis); Groww Trading API access + a market-data subscription (live quotes, historical candles); Groww token/TOTP/API secret; nothing is needed for the mock pilot.

## Remaining compliance checks
All ten items in `COMPLIANCE.md` (SEBI/NSE/Groww) are **unverified** — the official sources were unreachable. Live trading stays disabled until a human completes them.
