# Pilot evaluation report

**Run date:** 2026-10-26 · **Environment:** MariaDB 10.11 + Redis 7, scheduler off, backtest via `POST /api/backtests` (chronological split) ·
**Data:** `MOCK` synthetic prices (deterministic random walk with trend/range/volatile days — **no real market edge was built in**) ·
**Analyst:** `MOCK_HEURISTIC` — a deterministic stand-in, **not Claude** (no `ANTHROPIC_API_KEY` was available in the build sandbox) ·
**Costs:** default approximate NSE intraday schedule (`app.costs.*`) · **Slippage:** 5 bps equity / 0.5% options · **Capital:** ₹10,00,000 simulated.

## What this report is — and is not

It demonstrates that the *measurement machinery* works end to end (identical data/costs/risk across modes, chronological dev/validation/out-of-sample
split, paired comparison, uncertainty, cohort analysis, full audit trail). It is **not** evidence about Claude, about the strategies, or about real
markets. Nothing here should be read as a profit projection.

## Headline results (net of simulated costs)

Modes use identical data, slippage, costs and risk limits. B trades exactly like A by construction (the analyst only reviews).

| Period | Mode | Trades | Net P&L | Return | Win rate (95% CI) | Profit factor | Avg trade (95% CI) | Max drawdown |
|---|---|---|---|---|---|---|---|---|
| DEVELOPMENT (2025-01-06→2025-02-24, 36d) | A rules-only | 245 | ₹-34,828 | -3.48% | 40% (34%–46%) | 0.75 | ₹-142 (₹-293 to ₹9) | ₹37,281 |
| DEVELOPMENT (2025-01-06→2025-02-24, 36d) | B rules + AI review | 245 | ₹-34,828 | -3.48% | 40% (34%–46%) | 0.75 | ₹-142 (₹-293 to ₹9) | ₹37,281 |
| DEVELOPMENT (2025-01-06→2025-02-24, 36d) | C AI-gated + risk | 186 | ₹-28,896 | -2.89% | 40% (33%–47%) | 0.72 | ₹-155 (₹-317 to ₹6) | ₹33,824 |
| VALIDATION (2025-02-25→2025-03-12, 12d) | A rules-only | 79 | ₹-11,359 | -1.14% | 42% (32%–53%) | 0.80 | ₹-144 (₹-476 to ₹188) | ₹24,916 |
| VALIDATION (2025-02-25→2025-03-12, 12d) | B rules + AI review | 79 | ₹-11,359 | -1.14% | 42% (32%–53%) | 0.80 | ₹-144 (₹-476 to ₹188) | ₹24,916 |
| VALIDATION (2025-02-25→2025-03-12, 12d) | C AI-gated + risk | 52 | ₹-1,215 | -0.12% | 44% (32%–58%) | 0.96 | ₹-23 (₹-405 to ₹358) | ₹8,835 |
| OUT_OF_SAMPLE (2025-03-13→2025-03-28, 12d) | A rules-only | 80 | ₹-24,183 | -2.42% | 35% (25%–46%) | 0.55 | ₹-302 (₹-568 to ₹-36) | ₹27,982 |
| OUT_OF_SAMPLE (2025-03-13→2025-03-28, 12d) | B rules + AI review | 80 | ₹-24,183 | -2.42% | 35% (25%–46%) | 0.55 | ₹-302 (₹-568 to ₹-36) | ₹27,982 |
| OUT_OF_SAMPLE (2025-03-13→2025-03-28, 12d) | C AI-gated + risk | 64 | ₹-12,982 | -1.30% | 39% (28%–51%) | 0.64 | ₹-203 (₹-464 to ₹58) | ₹18,313 |

### Paired daily difference — AI-gated minus rules-only

| Period | Days | AI better / worse | Mean difference per day | 95% CI |
|---|---|---|---|---|
| DEVELOPMENT | 36 | 20 / 16 | ₹165 | ₹-496 to ₹825 |
| VALIDATION | 12 | 8 / 4 | ₹845 | ₹-948 to ₹2,639 |
| OUT_OF_SAMPLE | 12 | 7 / 5 | ₹933 | ₹-652 to ₹2,519 |

Every confidence interval contains zero. AI-gated "lost less" mainly because it **took fewer trades** (e.g. 186 vs 245 in development), so it paid less in
costs and had less exposure to a losing rule set; that is not evidence of skill.

### Does the analyst's verdict separate winners from losers? (same signals, hypothetical outcome of the strategy's own levels, in R)

| Period | Cohort | Signals | Mean R | Hypothetical win rate (95% CI) |
|---|---|---|---|---|
| DEVELOPMENT | AI_AGREED | 538 | 0.011 | 40% (36%–44%) |
| DEVELOPMENT | AI_DISAGREED | 440 | 0.094 | 41% (37%–46%) |
| VALIDATION | AI_AGREED | 149 | 0.029 | 44% (36%–52%) |
| VALIDATION | AI_DISAGREED | 184 | -0.009 | 39% (32%–46%) |
| OUT_OF_SAMPLE | AI_AGREED | 186 | 0.135 | 45% (38%–52%) |
| OUT_OF_SAMPLE | AI_DISAGREED | 144 | 0.191 | 45% (37%–53%) |

If a useful filter existed, `AI_AGREED` would show a clearly higher mean R than `AI_DISAGREED`. It does not (and in two of three periods it is the lower one):
the stand-in has **no filtering value**, which is the correct finding for a heuristic built from three arbitrary thresholds on synthetic data.

## Reading the numbers honestly

* The rule-based strategies **lose money** on this synthetic data (profit factor 0.55–0.80). That is expected: the generator contains regimes but no exploitable
  edge, and costs + slippage are real. A profitable-looking result here would have been a red flag.
* Sample sizes: 245/79/80 trades for rules-only. The development sample is above the 30-trade threshold the app uses to stop warning, but a single
  synthetic period of 36 days is still not a basis for conclusions; out-of-sample is only 12 days.
* Parameters were **not** tuned on any period (defaults throughout), so out-of-sample is genuinely unseen here; any future tuning must happen on DEVELOPMENT only.
* Options trades are priced with Black-Scholes on the synthetic index (fixed IV smile). That is a plumbing test of the long-option risk model, not a view on option premia.
* Hypothetical outcomes for index signals use the **underlying** levels (cost-free, in R); they do not model option premium decay.

## What a real evaluation of Claude requires (not done here)

1. `DATA_SOURCE=GROWW` with a market-data entitlement (real historical candles; real order-book quotes for spread/liquidity) — and `docs/COMPLIANCE.md` reviewed.
2. `AI_PROVIDER=CLAUDE` + `ANTHROPIC_API_KEY`. The audit trail already stores raw output, token usage and latency; identical prompts are reused (`reuseIdenticalPrompts`)
   so re-running a comparison does not re-bill. Budget: roughly ~1k Claude calls for a 36-day shadow run at this signal rate (978 candidates here); token counts per call were **not
   measured** (estimate a few thousand input tokens for ≈40 candles + indicators) — measure with `ai_analysis_records.input_tokens/output_tokens` on a 20-call pilot before a long run.
3. Hundreds of trades per mode per period, several regimes, at least 3 months; report intervals (the app does) and the days where AI-gated underperformed (the app lists them).
4. Run **AI_SHADOW in paper** for weeks first (cost-controlled by `app.ai.max-calls-per-day`), then compare cohorts before ever letting AI gate anything.
5. Compare against *simple deterministic filters* (e.g. only trade when ATR% in a band) as the real baseline — an LLM must beat that, not merely beat nothing.

## Reproduce

```bash
export DB_PASSWORD=… APP_ADMIN_PASSWORD=… SCHEDULER_ENABLED=false
java -jar backend/target/ai-investor-0.1.0-SNAPSHOT.jar
# login via the dashboard (Backtests tab) or:
POST /api/backtests {{"name":"pilot-eval","from":"2025-01-06","to":"2025-04-04","developmentDays":36,"validationDays":12,"outOfSampleDays":12,"maxAiCalls":5000}}
```
Wall-clock on a single-core sandbox MariaDB: ≈11 minutes (9 runs: 3 periods × 3 modes; 175,533 signal rows, 3,446 AI records).
