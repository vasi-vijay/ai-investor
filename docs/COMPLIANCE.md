# Compliance register (India) — status: **UNVERIFIED (partially sourced)**

**Result of the required review:** the review could **not** be performed in this build. The sandbox's egress policy blocked
`www.sebi.gov.in`, `www.nseindia.com` and `groww.in` (HTTP 403 on CONNECT; not circumvented). No regulatory statement in this repository
was verified against an official source on the implementation date (2026-10-26). **Consequently live trading is disabled and the
compliance attestation gate (`5_COMPLIANCE_ATTESTED`) can only be satisfied by a human who has done the verification below.**
Third-party blogs are deliberately not used as evidence.

Do not read the "leads" column as fact: it records what the implementer *recalls* or what the product implies, to tell you what to look for.

| # | Item to verify | Authoritative source to read | Lead (unverified) | Status | Code behaviour meanwhile |
|---|---|---|---|---|---|
| 1 | SEBI framework for **retail** participation in algorithmic trading: scope, who must register what, any exemption thresholds | sebi.gov.in → Legal → Circulars (algo-trading circulars) | A 2025 SEBI circular on retail algo trading exists (number/effective dates/thresholds not verified; implementation dates were reportedly extended) | ☐ unverified | live disabled |
| 2 | Whether a *personally developed* strategy using an API needs broker/exchange approval, registration or an algo ID | same + NSE circulars (nseindia.com → Resources → Circulars) + Groww | Do **not** assume exemption | ☐ unverified | live disabled |
| 3 | Order **tagging**/unique identifiers required on API orders | SEBI/NSE circulars, Groww docs | The app already tags orders with `order_reference_id` (`AI…`); it is *not* a regulatory algo ID | ☐ unverified | tag only |
| 4 | Authentication requirements (e.g. 2FA, token validity, session limits) | SEBI/NSE circulars, Groww docs | Groww tokens expire; app pauses on 401 and never bypasses auth | ☐ unverified | pause on 401 |
| 5 | **API rate limits** / order-per-second thresholds that change classification of the user | NSE circulars, Groww docs | adapter throttles to 5 req/s (config) | ☐ unverified | throttled |
| 6 | **Static IP** / IP whitelisting requirement for order APIs | Groww docs, SEBI/NSE circulars | none | ☐ unverified | not implemented |
| 7 | Intraday product (MIS) and index-option order rules, freeze quantities, lot-size changes | NSE contract specifications | lot/tick/expiry are read from the instrument master, never hardcoded | ☐ unverified | metadata-driven |
| 8 | Transaction-cost schedule (brokerage, STT, exchange, SEBI, stamp, GST) for the P&L model | Groww charges page, exchange/government notifications | defaults in `app.costs.*` are **approximations** | ☐ unverified | configurable |
| 9 | Record-keeping / audit expectations for algo orders | SEBI/NSE circulars | app persists every decision, order and approval (see `audit_logs`) | ☐ unverified | persisted |
| 10 | Market holidays and special sessions | NSE holiday list | `app.market.holidays` is operator-maintained; none are hardcoded | ☐ unverified | Mon–Fri only unless configured |

## How to use this register

1. Read each source on the day you intend to enable live trading; record **document, date, effective date and your conclusion** in the table above (or a copy).
2. Ask Groww support in writing about items 2, 3, 5, 6 for your account type.
3. Only then set `app.live.compliance-confirmed=true` **and** submit the in-app attestation (`POST /api/live/compliance-attestation`, note ≥ 20 chars saying who verified what and when). Both are required.
4. If any item cannot be confirmed, leave live trading off. Paper trading is unaffected.

This document is engineering guidance, not legal advice.

## Verification log — 2026-10-27 (second pass, with web access)

| Source read | What was actually confirmed | What was NOT readable |
|---|---|---|
| `groww.in/trade-api/docs/curl` | Token auth (`POST /v1/token/api/access`; TOTP and approval flows need **daily approval**); access token **expires daily 06:00**; token endpoint limit 150/24 h; rate limits: auth 5/s·30/min, orders 10/s·250/min, live data 10/s·300/min, non-trading 20/s·500/min; an **active Trading API subscription is required**; scope is CASH and FNO only | Quote/margin/position response field names; any regulatory statement. No streaming feed or static-IP requirement is *mentioned* on that page (absence of mention is not proof of absence) |
| `groww.in/trade-api/docs` (landing) | Links to the Python SDK and cURL docs only | No static-IP, algo-tagging, SEBI/NSE content |
| SEBI circular page (`sebi.gov.in`, retail algo trading) | Header only: circular **SEBI/HO/MIRSD/MIRSD-PoD/P/CIR/2025/0000013**, dated **04 Feb 2025**, "Safer participation of retail investors in Algorithmic trading" | The body (scope, thresholds, registration/tagging, static IP, authentication, effective dates) was **not retrievable** — the PDF/attachment must be read by a human |
| nseindia.com | not fetched in this pass | everything |

Consequences: item 5 (rate limits) is now sourced from Groww for *Groww's* limits (the NSE/SEBI classification thresholds remain unverified); item 4 (token lifetime) is confirmed for Groww. All other rows stay ☐ unverified, so **live order placement stays disabled** (`5_COMPLIANCE_ATTESTED` cannot pass).
