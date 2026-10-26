# Groww integration — what is verified, what is not

**Source-of-truth caveat.** The official docs (`groww.in/trade-api/docs`) were **not reachable** from the build sandbox
(egress policy returned HTTP 403 for `groww.in`; this was not worked around). The REST surface below was therefore taken from
Groww's own published Python SDK, **`growwapi` 1.5.0 on PyPI** (downloaded and *read*, never executed). That is a primary
source for request shapes, but not for response payloads, limits, entitlements or policy. Re-check everything marked ⚠ against
the docs before relying on it.

## Verified from the official SDK source (request side)

| Capability | Method & path (base `https://api.groww.in/v1`) | Notes |
|---|---|---|
| Auth headers | `Authorization: Bearer <token>`, `x-api-version: 1.0`, `x-request-id`, `x-client-*` | Java adapter sends its own `x-client-id`/`x-client-platform` values (⚠ server acceptance unverified) |
| Token (TOTP) | `POST /token/api/access` bearer=API key, body `{"key_type":"totp","totp":"<code>"}` → `{"token": …}` | implemented (`GrowwAuthManager`) |
| Token (approval) | same endpoint, body `{"key_type":"approval","checksum":sha256(secret+ts),"timestamp":ts}` | implemented |
| Quote | `GET /live-data/quote?exchange&segment&trading_symbol` | also `/live-data/ltp`, `/live-data/ohlc` (batch; not used) |
| Historical candles | `GET /historical/candles?exchange&segment&groww_symbol&start_time&end_time&candle_interval` (`1minute`…`1month`) | `yyyy-MM-dd HH:mm:ss`; the older `/historical/candle/range` is deprecated |
| Expiries / contracts | `GET /historical/expiries`, `GET /historical/contracts` | not used: expiries/contracts come from the instrument master CSV |
| Option chain / Greeks | `GET /option-chain/exchange/{ex}/underlying/{u}` | not used |
| Instrument master | `https://growwapi-assets.groww.in/instruments/instrument.csv` | provably has `exchange, exchange_token, trading_symbol, groww_symbol`; other column names ⚠ |
| Place / modify / cancel | `POST /order/create`, `/order/modify`, `/order/cancel` | bodies exactly as the SDK builds them; `order_reference_id` is the idempotency/audit tag |
| Order read | `GET /order/status/{id}`, `/order/status/reference/{ref}`, `/order/detail/{id}`, `/order/list`, `/order/trades/{id}` | |
| Account | `GET /positions/user`, `/holdings/user`, `/margins/detail/user`, `POST /margins/detail/orders` | |
| Constants | products `CNC/MIS/NRML/…`, validity `DAY/IOC/…`, order types `MARKET/LIMIT/SL/SL_M`, segments `CASH/FNO`, exchanges `NSE/BSE` | |
| Live feed | NATS-over-websocket topics (`/ld/eq/nse/price.<key>`, `/ld/fo/nse/price.<key>`, order/position updates) with protobuf payloads and signed socket tokens | **not implemented** (see below) |

Error mapping in the SDK (401 auth, 403 authorisation, 404, 429 rate limit, 504 timeout, `status:"FAILURE"` envelope) is mirrored in
`GrowwHttp`.

## ⚠ Unverified (must be confirmed with the docs / Groww before use)

* **Response field names** for quotes, margins, positions, orders, candles (parsed tolerantly with aliases; unknown shapes fail closed).
  The historical-candle row layout `[timestamp, o, h, l, c, v(, oi)]` is assumed.
* **Rate limits** — the adapter throttles to `app.groww.max-requests-per-second` (default 5) and maps HTTP 429, but real limits are unknown.
* **Entitlements** — a web search snippet (third-party/Groww blog, not the docs) says free access covers auth/orders/portfolio but **not** live
  data or historical candles. Without those the app cannot generate executable signals (the data gate refuses) — paper trading then needs the mock source.
* **Token lifetime** — the same snippet says manually generated tokens expire daily ~06:00; treated as "expires when the API says 401".
* **Instrument CSV columns** other than the four above, the meaning of `instrument_type` values (`EQ`/`IDX`/`CE`/`PE` assumed) and the unit of
  `tick_size`. Implausible metadata (tick > ₹1, lot ≤ 0, missing expiry/strike) marks a contract *not tradable* instead of guessing.
* **`order_reference_id` constraints** (length/charset). The app uses `AI` + 14 hex chars.
* **Static-IP / API-access policy**, order tagging, and anything regulatory — see `COMPLIANCE.md`.
* **Historical request range limits** — the adapter requests one trading day per call.

## Not implemented

* Live streaming feed (NATS/protobuf). Quotes are **polled**; candles are built from 1-minute historical data. This is sufficient for the
  5-minute strategies but not for tick-level logic. A WebSocket reconnection requirement therefore applies only to the dashboard socket here.
* Smart orders (GTT/OCO), option-chain/Greeks endpoints, holdings.

## Without credentials

`DATA_SOURCE=MOCK` (default) runs everything on deterministic synthetic data (`SyntheticMarket`) with a mock instrument master, quotes, account
and order provider. To go real: set `DATA_SOURCE=GROWW` and supply `GROWW_ACCESS_TOKEN` (or `GROWW_API_KEY` + `GROWW_TOTP_SECRET`/`GROWW_API_SECRET`)
via environment/secret file — never through the UI or a chat.

## Update 2026-10-27 — verified against `groww.in/trade-api/docs/curl`

* Confirmed: token endpoint and key types, daily token expiry (06:00), daily approval for API-key flows, per-endpoint rate limits (auth 5/s, orders 10/s, live data 10/s, non-trading 20/s), required Trading API subscription, CASH/FNO scope, order-detail field names (`groww_order_id`, `order_status`, `filled_quantity`, `average_fill_price`, …).
* The adapter's default `max-requests-per-second=5` is below every published limit, so it is safe; per-minute caps (e.g. 300/min live data) are **not** separately enforced — keep the poll interval modest.
* Still unverified: quote/margin/position/candle response shapes, instrument CSV columns, streaming availability. The app treats market data as **REST polling** and says so in the UI.
* "No default mock": `DATA_SOURCE` now defaults to `GROWW`. Without credentials the engine is idle (reason shown), `/api/market/*` answers `unavailable`, and nothing is simulated unless `DATA_SOURCE=MOCK` is set explicitly.
