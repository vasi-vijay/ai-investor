# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Claude-assisted NSE intraday trading workstation: Java 21 / Spring Boot 3.5 backend (`backend/`), React 18 + TypeScript + Vite dashboard (`frontend/`), MySQL/MariaDB via Flyway, optional Redis. Orders are **paper (simulated)**; live order placement is dormant and gated. `docs/` holds ARCHITECTURE, GROWW_INTEGRATION, LIVE_TRADING, COMPLIANCE, OPERATIONS — read the relevant one before touching those areas.

## Commands

```bash
# backend (from backend/)
mvn -DskipTests package                      # build jar; use `mvn clean package` if "Unable to find main class" (stale target/)
mvn test                                     # all tests, H2 in MySQL mode, no external services
mvn test -Dtest=NoMockFallbackTest           # single class; -Dtest='Class#method' for one method
java -jar target/ai-investor-0.1.0-SNAPSHOT.jar

# frontend (from frontend/)
npm run dev                                  # :5173, proxies /api and /ws to :8080
npm run build                                # tsc --noEmit && vite build (this is the typecheck)
npm test                                     # vitest; npx vitest run src/ui.test.tsx for one file
```

Run Maven builds one at a time: concurrent `mvn` runs in `backend/` corrupt `target/` (empty jar / missing main class).

Backend env: `DB_URL` (default `localhost:3306/aiinvestor`), `DB_USER`, `DB_PASSWORD`, `APP_ADMIN_PASSWORD` (required to log in). Credentials come only from the environment: `ANTHROPIC_API_KEY`, `GROWW_ACCESS_TOKEN` or `GROWW_API_KEY` + `GROWW_TOTP_SECRET`/`GROWW_API_SECRET`.

## Architecture (the parts that span files)

- **One engine, three contexts.** `TradingEngine.step()` runs paper-live (`PaperTradingService`, scheduler-driven), replay and backtests (`BacktestService`); only the `MarketView` differs (`LiveMarketView` vs `SeriesBackedView`). Pipeline per candle: data gate → indicators → strategies (`strategy/`) → `OptionPlanner` for index underlyings → AI stage (`AiAnalysisService`, modes `RULES_ONLY|AI_SHADOW|AI_GATED`) → `RiskEngine` → `PaperBroker`. Every stage is persisted (`trading_signals` is the decision record).
- **Providers are interfaces** (`market/`, `broker/`): `MarketDataProvider`, `HistoricalDataProvider`, `InstrumentMetadataProvider`, `BrokerAccountProvider`, `BrokerOrderProvider`. Implementations are chosen by `app.data.source` in `AppConfig` / `GrowwConfig`: `GROWW` (real, `groww/`) or `MOCK` (`mock/`, synthetic). Instrument lot/tick/expiry/strike must come from the instrument master, never hardcoded.
- **No silent mock fallback (invariant).** Defaults are `DATA_SOURCE=GROWW`, `AI_PROVIDER=CLAUDE`. Mock beans exist only when `DATA_SOURCE=MOCK`, and `HeuristicMockAnalyst` only when `AI_PROVIDER=MOCK_HEURISTIC`. Tests set both in `src/test/resources/application-test.yml` and `TestProps`. When Groww is unauthenticated, `IntegrationService.engineBlockReason()` makes `PaperTradingService.runCycle()` return early and `/api/market/*` reports `unavailable`; an unconfigured Claude yields an ERROR AI record → NO_TRADE.
- **`api/IntegrationService`** is the single source of truth for data readiness, Claude status and the derived operating state (`DISCONNECTED … LIVE_TRADING_ENABLED`). The state is computed from real facts (Groww auth state, kill switch, pause flag, `LiveExecutionGate`), not stored; nothing reachable from "connected" leads to live. `BrokerController` (`/api/broker/*`) and `MarketController` (`/api/market/*`) sit on it; `OverviewService` embeds it.
- **Risk is independent of AI.** `RiskEngine` (separate equity and long-option models; short options always rejected), the persisted daily-loss lock (`daily_pnl`) and `KillSwitch` (`application_settings`) are never changed by AI output or the frontend. Limits are config only (`AppProperties`).
- **Live trading** is isolated in `live/` (`LiveExecutionGate` checks all preconditions on every proposal and approval; `LiveOrderService` does manual-approval flow with ambiguity-safe reconciliation). The real `GrowwOrders` bean exists only if `app.groww.enabled` and `app.live.enabled` are both true. Do not weaken or bypass the gate; never place real orders in tests.
- **Run separation.** Every run has `run_mode` (`BACKTEST|PAPER|LIVE`); `RunRepository.getOrCreatePaperRun` reuses a run only for the same name **and data source**, so simulated and real-data statistics never share a run.
- **Auth:** `GrowwAuthManager` holds token/state (`NOT_CONFIGURED, DISCONNECTED, OK, AUTH_EXPIRED, AUTH_FAILED`), re-authorises only via Groww's documented token flows with a cooldown; 401 pauses broker-dependent work. Dashboard auth is session cookie + CSRF double-submit (`security/`); admin-only endpoints use `@PreAuthorize("hasRole('ADMIN')")`.
- **DB:** Flyway forward-only migrations in `backend/src/main/resources/db/migration` (V1 core, V2 live approvals). Add new `V<n>__*.sql`; never edit applied ones. Repositories use `JdbcClient` and must stay driver-agnostic (MySQL returns `LocalDateTime`/`Boolean` where H2 returns `Timestamp`/numbers — use the `Db` helpers).
- **Frontend:** hash-routed single page; `App.tsx` shell fetches `/api/overview` into `StatusContext` (`shell/`), pages live in `views/`, shared kit in `ui.tsx` (`useApi`, `DataTable`, `SourceTag`, `ErrorBoundary`). Operating-state labels/glyphs are in `shell/status.ts` (mode must never be conveyed by colour alone). Every financial figure carries a `SourceTag` (LIVE / HISTORICAL / SIMULATION). Nothing secret goes to browser storage (only the theme preference).

## Conventions worth knowing

- Money uses `BigDecimal`; timestamps are UTC in the DB and rendered as IST in the UI.
- Logs/exceptions go through the redactors in `util/`; do not log tokens or API keys.
- The Groww response payload shapes are partly unverified (see `docs/GROWW_INTEGRATION.md`); parsing fails closed. Compliance items in `docs/COMPLIANCE.md` are unverified, which is why live trading stays disabled.
