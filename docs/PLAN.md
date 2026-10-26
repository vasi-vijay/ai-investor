# Project plan (Phase 1)

Workspace was empty. Toolchain found: Java 21, Maven 3.9, Node 22, Docker CLI (no daemon), Redis; MariaDB installed locally for real-DB validation.
Official-docs review: `groww.in`, `sebi.gov.in`, `nseindia.com` were blocked by the sandbox (403) — Groww's REST surface was taken from the official
`growwapi` SDK on PyPI instead (see `GROWW_INTEGRATION.md`); regulatory review is **outstanding** (see `COMPLIANCE.md`).

Order of work (as requested): 2 backend/schema/providers/indicators/strategies → 3 Claude + audit → 4 risk + paper → 5 Groww → 6 backtests + AI-vs-rules
→ 7 dashboard → 8 gated live interface → 9 build/test/e2e → 10 docs + pilot report. Decisions: Spring `JdbcClient` (no JPA) for explicit SQL;
portable SQL (MySQL/MariaDB/H2) so tests run anywhere and a real-DB run validates drivers; official Anthropic Java SDK; Groww over plain `java.net.http`
(no Python service); streaming feed deferred (REST polling).
