# Operations

## Secrets
Environment variables or secret files only (`.env` is git-ignored; `.env.example` has placeholders). Never paste credentials into a chat, ticket
or commit. The app: redacts bearer tokens/keys/JWTs/`sk-ant-…` from every log line and error body (`Redactor`, logback converters), never returns
configuration through the API, never puts credentials in AI prompts, never stores tokens in the browser.
`GROWW_ACCESS_TOKEN_FILE` lets a rotated token be picked up without restart. Rotate `APP_ADMIN_PASSWORD` by restarting with a new value (or supply a bcrypt hash).

## Health, shutdown, recovery
* `GET /actuator/health` (public, no details) — DB; Redis health is disabled because Redis is optional (falls back to in-memory with a log line).
* Graceful shutdown (`server.shutdown=graceful`, scheduler awaits in-flight cycle). Open simulated positions/orders persist.
* **Restart recovery:** the PAPER run is reloaded by name; open positions are re-managed against the 1-minute bars since they opened; the daily
  loss lock, kill switch and AI call counters are database state. (Limitation: a partially filled entry order may re-consume bar volume it already
  used before the restart.)
* Dashboard websocket: server push with client-side exponential back-off reconnect; REST polling continues if the socket is down.

## Backups (MySQL)
The database is the audit trail — back it up.
```bash
# daily logical backup (consistent snapshot, no table locks for InnoDB)
mysqldump --single-transaction --routines --triggers -h <host> -u <user> -p aiinvestor | gzip > aiinvestor-$(date +%F).sql.gz
# restore into an empty schema
gunzip -c aiinvestor-2026-10-26.sql.gz | mysql -h <host> -u <user> -p aiinvestor
```
Keep ≥ 30 days, store off-host, test a restore regularly. Flyway recreates the schema; never edit applied migrations — add `V3__…sql`.
Redis holds only transient state (locks, last-tick) and needs no backup.

## Performance note
Backtests issue many small queries: 60 trading days × 3 modes × 6 instruments took ~11 minutes on a single-core sandbox MariaDB.
Use shorter windows while iterating; run long suites off-hours.
