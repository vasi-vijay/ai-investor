# AI Investor — Claude-assisted intraday trading workstation (NSE)

A working end-to-end pilot that measures how an LLM (Claude) contributes to intraday trading decisions on NSE equities and
NIFTY / BANK NIFTY **long options**, versus deterministic rules — in **paper mode** with every decision audited.
**Live trading is disabled** and cannot start without a separate, deliberate, multi-condition activation (`docs/LIVE_TRADING.md`).
Nothing here promises profitability; the whole point is to *measure* (`docs/PILOT_REPORT.md`).

| | |
|---|---|
| Backend | Java 21, Spring Boot 3.5 (Web, WebSocket, Security, JDBC), Flyway, MySQL/MariaDB, Redis (optional), official Anthropic Java SDK |
| Frontend | React 18 + TypeScript (Vite) |
| Default mode | `PAPER` (simulated orders) on **real Groww data** and **real Claude** — both need credentials; without them the app shows a setup state and generates **no** signals (never a silent mock) |
| Simulation | `DATA_SOURCE=MOCK` / `AI_PROVIDER=MOCK_HEURISTIC` must be selected **explicitly**; the UI then labels everything SIMULATED |

## Quick start

Prerequisites: JDK 21, Maven, Node 22, MySQL 8 or MariaDB 10.11 (Redis optional — falls back to in-memory).

```bash
# 1. database (once)
mysql -uroot -e "CREATE DATABASE aiinvestor CHARACTER SET utf8mb4; CREATE USER 'aiinvestor'@'%' IDENTIFIED BY 'dev-pass'; GRANT ALL ON aiinvestor.* TO 'aiinvestor'@'%';"

# 2. backend  (http://localhost:8080)
cd backend
export DB_USER=aiinvestor DB_PASSWORD=dev-pass APP_ADMIN_PASSWORD='choose-a-long-random-password'
# real integrations (optional now, required for signals): ANTHROPIC_API_KEY, GROWW_API_KEY+GROWW_TOTP_SECRET (or GROWW_ACCESS_TOKEN)
# no credentials and just want to look around?  export DATA_SOURCE=MOCK AI_PROVIDER=MOCK_HEURISTIC   (explicit simulation)
mvn -DskipTests package && java -jar target/ai-investor-0.1.0-SNAPSHOT.jar

# 3. frontend  (http://localhost:5173, proxies /api and /ws)
cd ../frontend && npm install && npm run dev
```
Sign in as `admin` with the password you set, then open **Broker Connection**. In explicit **MOCK replay** mode a simulated clock steps through synthetic trading days (a 5-minute bar every
3 s) so you can watch signals, AI reviews, risk decisions, paper fills and P&L at any time of day.

Docker Compose (needs a running Docker daemon — **not exercised in the build sandbox**, config validated only):
`cp .env.example .env`, fill secrets, `docker compose up --build`, open http://localhost:5173.

## Using real services (all optional)

| Want | Set (environment / secret file only — never in chat, UI or git) | Notes |
|---|---|---|
| Claude analysis (default provider) | `ANTHROPIC_API_KEY`, optionally `ANTHROPIC_MODEL` (default `claude-opus-5-5`), `app.ai.*` limits | `AI_PROVIDER=MOCK_HEURISTIC` is an explicit stand-in, **not Claude**; with no key, AI-dependent decisions are recorded as NO_TRADE |
| Groww market data (default source) | `GROWW_ENABLED=true`, `GROWW_ACCESS_TOKEN` *or* `GROWW_API_KEY`+`GROWW_TOTP_SECRET`/`GROWW_API_SECRET` | needs a Trading API account **with data entitlement**; response parsing is partly unverified — read `docs/GROWW_INTEGRATION.md` first |
| Paper AI mode | `app.paper.ai-mode` = `RULES_ONLY` \| `AI_SHADOW` (default) \| `AI_GATED`; changing it starts a **new** paper run | statistics are never mixed across runs/modes |

Config reference: `backend/src/main/java/com/aiinvestor/config/AppProperties.java` (every limit, cost, strategy and AI setting, with conservative defaults).
Strategy parameters: `app.strategies.<NAME>.<key>=…` or per-backtest overrides.

## Dashboard
13 sections: Overview · Live Markets (candles, EMA/RSI, quote with source/staleness) · AI Analysis · Strategies · Orders · Positions · Portfolio (Groww account, only when connected) · Risk Management · Backtesting · Performance Analytics · Decision Audit Trail · Broker Connection (connect/verify/reauthorise/disconnect, capability probe, live-activation tab) · Application Settings. Operating state is one of DISCONNECTED, AUTHENTICATING, CONNECTED_READ_ONLY, PAPER_TRADING, LIVE_TRADING_PENDING_AUTHORIZATION, LIVE_TRADING_ENABLED, AUTHENTICATION_EXPIRED, TRADING_PAUSED, EMERGENCY_LOCKED, CONNECTION_ERROR (derived from real facts; connecting never reaches live). Earlier detail: AI decisions (every evaluation;
detail view shows rules → AI → risk → outcome, raw AI output on demand) · Orders & positions (simulated fills; observed vs simulated price) · Analytics
(win rate with CI, profit factor, drawdown, by strategy/instrument/type/regime, rejected vs accepted, NO_TRADE frequency, AI stats) · Backtests (A/B/C comparison,
dev/validation/OOS split, paired differences, cohorts) · Risk & controls (persistent kill switch, limits, events) · Live (gate checklist, approvals).

## Tests
```bash
cd backend && mvn test            # 141 tests, H2 in MySQL mode (no services needed)
# same suite against a real MySQL-family DB (except the H2-catalog migration test):
mvn test -Dtest='!MigrationTest' -Dspring.datasource.url='jdbc:mysql://localhost:3306/aiinvestor_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' -Dspring.datasource.username=… -Dspring.datasource.password=…
cd frontend && npm run build && npm test   # tsc + vite build + 15 vitest tests
```
No test can place a real order: the default context has no real order provider, and live tests use the in-memory mock broker / a local fake HTTP server.

## What is implemented, tested, mocked, unverified

| Area | Implemented | Tested | Mocked / stand-in | Unverified / not done |
|---|---|---|---|---|
| Indicators (EMA/RSI/ATR/VWAP), 3 strategies | ✅ | ✅ unit + invariants over synthetic days | — | strategy *quality* on real data |
| Equity & long-option risk models, 20+ checks, kill switch, daily lock | ✅ | ✅ incl. persistence/restart | — | margin for short options is out of scope (rejected) |
| Paper broker: market/limit, slippage, partial fills, costs, EOD | ✅ | ✅ exact-number tests | fills use 1-min bars | intrabar order of events beyond "stop first" |
| Cost model | ✅ configurable | ✅ formulas | defaults are **approximations** | current Groww/NSE/government rates |
| Claude via official SDK, structured output, validation, audit | ✅ | ✅ validator, failures, timeouts, budgets via a scripted analyst | `MOCK_HEURISTIC` default | **no live Claude call was ever made** (no API key in the sandbox) |
| Groww REST adapter (auth flows, quotes, candles, account, orders, metadata) | ✅ | ✅ against a local fake server | mock providers by default | response payload shapes, rate limits, entitlements, instrument CSV columns, **streaming feed (not implemented)**; never run against real Groww |
| Backtests + A/B/C comparison, uncertainty, split | ✅ | ✅ determinism, no look-ahead, isolation, reconciliation | synthetic data | real-data results |
| Dashboard (React/TS) + REST + websocket + security | ✅ | ✅ API/security tests; UI built & screenshotted in Chromium | — | no automated UI tests beyond helpers |
| Live interface (gate, manual approval, ambiguity-safe) | ✅ dormant | ✅ with mock broker | — | live fills/positions/stops not managed; compliance **unverified** |
| Docker Compose | ✅ | config validated only | — | not run (no daemon here) |
| SEBI/NSE/Groww compliance review | register only | — | — | **not performed** — sources unreachable (`docs/COMPLIANCE.md`) |

## Documentation
`docs/PLAN.md` · `ARCHITECTURE.md` · `AI_SCHEMA.md` · `GROWW_INTEGRATION.md` · `COMPLIANCE.md` · `LIVE_TRADING.md` · `OPERATIONS.md` (backups, recovery) · `PILOT_REPORT.md` · `DELIVERY_REPORT.md`

## Known limitations
Quotes are polled (no streaming). Paper/backtest options use Black-Scholes on synthetic data or whatever option candles the provider returns. Backtest spreads for
candle-derived quotes are assumed (flagged). Holidays must be configured. A partially filled entry can re-consume a bar after a restart. The dashboard has a single
in-memory user store (admin + optional viewer) — adequate for a local pilot, not multi-user production. Redis is used for the tick lock and transient state only.
