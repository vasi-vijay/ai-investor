# Claude assessment schema (`schema_version` 1, `prompt_version` p1)

Claude is called through the **official Anthropic Java SDK** with structured outputs: the JSON schema is derived from
`ClaudeAssessmentWire` (so the API constrains the shape), then the **raw text is stored untouched** (`ai_analysis_records.raw_response`) and parsed
again by our own `AiResponseValidator`. Only a fully validated `AiAssessment` ever reaches application logic (`parsed_json`).

No `temperature`/`top_p` is sent (current models reject non-default values); thinking uses the model default (adaptive); `effort`, `model`,
`timeout`, `max_tokens`, call budgets are configuration (`app.ai.*`). The default model id is configuration only (`ANTHROPIC_MODEL`).
Refusals (`stop_reason=refusal`) are recorded as `REFUSED` and treated as NO_TRADE; the optional server-side fallback beta is **not** enabled.

```jsonc
{
  "instrument": "RELIANCE",                  // must equal the requested trading symbol
  "decision": "BUY_CANDIDATE | SELL_CANDIDATE | NO_TRADE | INSUFFICIENT_DATA",
  "strategy": "VWAP_TREND",                  // must equal the requested strategy
  "timeframe": "5m",                         // must equal the requested timeframe
  "confidence": 0-100,                       // subjective, UNCALIBRATED; never a probability of profit
  "entry_price": 123.45 | null,              // required (with stop/target) for candidates, ordered correctly, plausibly near price
  "stop_loss": 122.10 | null,
  "target_price": 126.00 | null,
  "supporting_conditions": ["…"],            // which snapshot conditions support the candidate
  "contradicting_conditions": ["…"],         // which contradict it
  "invalidation_conditions": ["…"],          // evidence that would invalidate it
  "missing_information": ["…"],              // what was missing
  "strategy_agreement": "AGREES | DISAGREES",
  "disagreement_explanation": "…",           // why it disagrees with the rules ("" if it agrees)
  "reject_trade": true | false,              // whether the trade should be rejected
  "reasoning": ["…"],                        // non-empty
  "data_quality": "VALID | STALE | INCOMPLETE | INCONSISTENT"
}
```

## Validation (any failure ⇒ response rejected, recorded, treated as NO_TRADE)
malformed/empty JSON · decision outside the enum · instrument/strategy/timeframe mismatch · confidence outside 0–100 · empty reasoning ·
non-positive/non-finite prices · candidate missing entry/stop/target · levels not ordered for the direction · a price farther than
`max(6×ATR, 2% of price, 1.5× the strategy's own level reach)` from the last close · unknown extra fields are *dropped* (never acted on).
Timeouts, API errors, missing key, budget exhaustion, refusals are recorded with their own status (`TIMEOUT`, `ERROR`, `SKIPPED`, `REFUSED`).

## What Claude sees (compact snapshot, `snapshot_version` 1)
Data source label, UTC/IST time, session status, instrument, timeframe, freshness (bar/quote age, derived-vs-real quote), last N **completed**
candles (N = `app.ai.max-candles-in-prompt`), deterministic EMA/VWAP/RSI/ATR/volume/trend/volatility, spread & liquidity (null for indices),
the strategy proposal (entry/stop/target/R:R/supporting/contradicting/invalidation), the selected option contract plan when the signal is on an
index, the current paper position, and the remaining risk budget. No credentials, account ids or free text from external sources.

## What Claude can and cannot do
It **can** agree/disagree with a rule-based candidate. It **cannot** place orders, size positions, change limits, switch mode or authorise
itself: order prices/sizes always come from the deterministic plan and risk engine; AI-proposed prices are only validated and recorded.
In `AI_GATED` mode an unusable or disagreeing verdict *rejects* the candidate; in `AI_SHADOW` it is recorded for later comparison and does not
change trading; in `RULES_ONLY` no call is made.

## MOCK_HEURISTIC
`AI_PROVIDER=MOCK_HEURISTIC` (default) is a deterministic stand-in with three fixed filters. It exercises the same prompt→validate→persist→gate
path offline. **Its results say nothing about Claude** and are labelled `MOCK_HEURISTIC` in every record and report.
