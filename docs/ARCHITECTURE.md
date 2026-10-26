# Architecture

```
            ┌────────────── React/TS dashboard (nginx or Vite) ──────────────┐
            │  session cookie + CSRF, REST polling + websocket push          │
            └───────────────────────────────┬────────────────────────────────┘
                                            │ /api  /ws
┌───────────────────────────────────────────▼────────────────────────────────────────────┐
│ Spring Boot 3.5 (Java 21)                                                              │
│  security: session login, roles ADMIN/VIEWER, CSRF, rate limit, redaction              │
│  api: Dashboard / Control / Live controllers, EventBroadcaster (ws)                    │
│                                                                                        │
│  PaperTradingService (scheduler, restart recovery, EOD) ──► TradingEngine.step(t)      │
│  BacktestService ─────────────────────────────────────────► (same engine)              │
│                                                                                        │
│  TradingEngine.step(run, MarketView, t):                                               │
│   1 PaperBroker.processBars  (1m bars → entry fills, partial fills, stops/targets)     │
│   2 square-off at 15:15 IST                                                            │
│   3 per instrument: data gate → indicators → each Strategy (A/B/C)                     │
│   4 candidate → [index: OptionPlanner picks a long CE/PE from metadata + liquidity]    │
│   5 AI stage by mode (RULES_ONLY | AI_SHADOW | AI_GATED) → validated assessment       │
│   6 RiskEngine (equity model | long-option model, 20+ checks, exact reasons)           │
│   7 PaperBroker.submitEntry (idempotent client_order_id)                               │
│   8 every step persisted: trading_signals, ai_analysis_records, risk_events, …         │
│                                                                                        │
│  MarketView: LiveMarketView (providers) | SeriesBackedView (backtest/replay slicing)   │
│  Providers (interfaces): MarketData, Historical, BrokerAccount, BrokerOrder, Metadata  │
│    └─ Mock*  (default)    └─ Groww* (REST, needs creds; order provider only if live)   │
│  AI: AiAnalyst → AnthropicSdkGateway (official SDK) | HeuristicMockAnalyst             │
│  Live: LiveExecutionGate + LiveOrderService (manual approval; dormant by default)      │
└───────────────┬───────────────────────────────────────────────┬────────────────────────┘
                │ JDBC + Flyway (V1 core, V2 live approvals)    │ Redis (locks/transient, optional)
           MySQL 8 / MariaDB                                  Redis
```

## Key design decisions
* **One engine, three contexts.** Paper-live, replay and backtests run the *same* `TradingEngine`, risk engine, broker simulator and cost model;
  only the `MarketView` differs. A backtest cannot differ from paper in rules, only in data.
* **No look-ahead.** Strategies see completed candles only (`end ≤ asOf`); orders fill on bars starting at/after the decision; a bar touching both
  stop and target counts as a stop. Tests prove day-1 decisions are identical whether or not later days exist.
* **Mode separation.** Every run has `run_mode` (`BACKTEST|PAPER|LIVE`); orders/executions/positions/signals/daily P&L carry run id and mode;
  analytics are per run; a DB check forbids `simulated=0` outside LIVE.
* **Independent risk engine.** Sole authority on size/approval; no AI input into limits; limits are configuration only; the daily-loss lock and the
  kill switch are persisted and never cleared by code.
* **Equity vs options models.** Equity: stop-distance sizing under per-trade risk, exposure, allocation, liquidity, spread and cost/reward caps.
  Long options: contract validity (expiry/strike/lot/tick from metadata), minimum days to expiry (no expiry day by default), premium range, spread %,
  volume/OI, rapid premium move, lots from the loss budget with total premium at risk reported; **sell-to-open is always rejected**.
* **Honest quotes.** Backtest/replay quotes are derived from candles (spread assumed) and flagged `quote_is_derived_from_candles`; risk checks say "ASSUMED".

## Strategies (all parameters configurable: `app.strategies.<NAME>.<key>`, per-backtest overrides, defaults in code)
A `VWAP_TREND` — close vs VWAP, EMA9/21 alignment and slope, volume confirmation, RSI band, *fresh trigger* (no alignment in the previous N bars), ATR stop, R multiple target.
B `OPENING_RANGE_BREAKOUT` — configurable opening range, buffered first-close breakout, volume vs OR volume, range-width filter, last-entry time, duplicate suppression, OR/ATR stops.
C `MOMENTUM_PULLBACK` — established trend, pullback to EMA21/VWAP with RSI retrace, re-entry bar through the prior extreme, stop beyond the pullback, R:R check.
Indices have no volume: VWAP degrades to an equal-weighted proxy and volume confirmation is marked not applicable (disclosed in reasons and the snapshot).

## Tables
`trading_runs`, `instruments`, `market_candles`, `strategy_configurations`, `trading_signals` (the decision record), `ai_analysis_records`,
`trade_orders`, `trade_executions` (market_price vs fill_price), `positions`, `daily_pnl` (also the persistent lock), `risk_events`,
`application_settings`, `audit_logs`, `live_order_approvals`. `strategy_configurations` stores immutable snapshots of the *effective* strategy parameters (defaults + configuration + run overrides, keyed `version#hash`);
the same parameters are copied into `trading_runs.config_json` together with risk limits, execution assumptions and AI model/prompt version, so any decision can be
reproduced from its run. `market_candles` archives every completed 5-minute candle the paper-live engine analysed (idempotent, labelled with its `source`);
backtest data is re-derivable (mock is deterministic) and is not archived. Each signal also stores its snapshot JSON and SHA-256 hash.
