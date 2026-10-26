-- Core schema. Portable across MySQL 8 / MariaDB 10.11 and H2 (MODE=MySQL, used by tests).
-- All timestamps are UTC (DATETIME(3)). Prices DECIMAL(18,4); money DECIMAL(18,2).
-- Every trade-related row carries run_id + run_mode so BACKTEST / PAPER / LIVE are never mixed.

CREATE TABLE trading_runs (
    id               VARCHAR(36)   NOT NULL,
    run_mode         VARCHAR(16)   NOT NULL,
    name             VARCHAR(128)  NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    ai_mode          VARCHAR(16)   NOT NULL,
    data_source      VARCHAR(16)   NOT NULL,
    initial_capital  DECIMAL(18,2) NOT NULL,
    period_start     DATETIME(3)   NULL,
    period_end       DATETIME(3)   NULL,
    period_label     VARCHAR(32)   NULL,
    config_json      LONGTEXT      NULL,
    report_json      LONGTEXT      NULL,
    started_at       DATETIME(3)   NOT NULL,
    ended_at         DATETIME(3)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_run_mode CHECK (run_mode IN ('BACKTEST','PAPER','LIVE')),
    CONSTRAINT ck_run_ai_mode CHECK (ai_mode IN ('RULES_ONLY','AI_SHADOW','AI_GATED'))
);
CREATE INDEX ix_runs_mode ON trading_runs (run_mode, started_at);

CREATE TABLE instruments (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    exchange          VARCHAR(8)    NOT NULL,
    segment           VARCHAR(12)   NOT NULL,
    instrument_type   VARCHAR(12)   NOT NULL,
    trading_symbol    VARCHAR(96)   NOT NULL,
    groww_symbol      VARCHAR(128)  NULL,
    name              VARCHAR(200)  NULL,
    underlying_symbol VARCHAR(32)   NULL,
    expiry_date       DATE          NULL,
    strike            DECIMAL(18,4) NULL,
    option_type       VARCHAR(2)    NULL,
    lot_size          INT           NOT NULL DEFAULT 1,
    tick_size         DECIMAL(10,4) NOT NULL DEFAULT 0.0500,
    exchange_token    VARCHAR(128)  NULL,
    tradable          TINYINT(1)    NOT NULL DEFAULT 1,
    source            VARCHAR(16)   NOT NULL,
    refreshed_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_instrument UNIQUE (exchange, segment, trading_symbol),
    CONSTRAINT ck_instr_type CHECK (instrument_type IN ('EQUITY','INDEX','OPTION')),
    CONSTRAINT ck_instr_opt CHECK (option_type IS NULL OR option_type IN ('CE','PE')),
    CONSTRAINT ck_instr_lot CHECK (lot_size > 0),
    CONSTRAINT ck_instr_tick CHECK (tick_size > 0)
);
CREATE INDEX ix_instr_underlying ON instruments (underlying_symbol, expiry_date);

CREATE TABLE market_candles (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    instrument_id  BIGINT        NOT NULL,
    interval_code  VARCHAR(8)    NOT NULL,
    bucket_start   DATETIME(3)   NOT NULL,
    open_price     DECIMAL(18,4) NOT NULL,
    high_price     DECIMAL(18,4) NOT NULL,
    low_price      DECIMAL(18,4) NOT NULL,
    close_price    DECIMAL(18,4) NOT NULL,
    volume         BIGINT        NOT NULL DEFAULT 0,
    open_interest  BIGINT        NULL,
    source         VARCHAR(16)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_candle UNIQUE (instrument_id, interval_code, bucket_start, source),
    CONSTRAINT fk_candle_instr FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT ck_candle_ohlc CHECK (high_price >= low_price AND high_price >= open_price AND high_price >= close_price
                                     AND low_price <= open_price AND low_price <= close_price)
);

CREATE TABLE strategy_configurations (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_name  VARCHAR(48)  NOT NULL,
    version        VARCHAR(16)  NOT NULL,
    params_json    LONGTEXT     NOT NULL,
    enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_strategy_cfg UNIQUE (strategy_name, version)
);

-- trading_signals.id is the decision ID: one row per strategy evaluation (including NO_TRADE).
CREATE TABLE trading_signals (
    id                 VARCHAR(36)   NOT NULL,
    run_id             VARCHAR(36)   NOT NULL,
    run_mode           VARCHAR(16)   NOT NULL,
    idempotency_key    VARCHAR(160)  NOT NULL,
    created_at         DATETIME(3)   NOT NULL,
    bar_time           DATETIME(3)   NOT NULL,
    instrument_id      BIGINT        NOT NULL,
    underlying_symbol  VARCHAR(32)   NULL,
    strategy_name      VARCHAR(48)   NOT NULL,
    strategy_version   VARCHAR(16)   NOT NULL,
    timeframe          VARCHAR(8)    NOT NULL,
    rule_decision      VARCHAR(20)   NOT NULL,
    rule_reasons_json  LONGTEXT      NULL,
    proposed_entry     DECIMAL(18,4) NULL,
    proposed_stop      DECIMAL(18,4) NULL,
    proposed_target    DECIMAL(18,4) NULL,
    risk_reward        DECIMAL(10,4) NULL,
    market_regime      VARCHAR(24)   NULL,
    indicators_json    LONGTEXT      NULL,
    snapshot_json      LONGTEXT      NULL,
    snapshot_hash      VARCHAR(64)   NULL,
    traded_instrument_id BIGINT      NULL,
    ai_analysis_id     VARCHAR(36)   NULL,
    ai_decision        VARCHAR(20)   NULL,
    ai_agrees          TINYINT(1)    NULL,
    disagreement_note  VARCHAR(255)  NULL,
    risk_decision      VARCHAR(16)   NOT NULL DEFAULT 'NOT_EVALUATED',
    risk_reasons_json  LONGTEXT      NULL,
    final_status       VARCHAR(16)   NOT NULL,
    rejection_reason   VARCHAR(500)  NULL,
    order_id           VARCHAR(36)   NULL,
    position_id        VARCHAR(36)   NULL,
    outcome_status     VARCHAR(40)   NULL,
    outcome_pnl        DECIMAL(18,2) NULL,
    subsequent_move_json LONGTEXT    NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_signal_idem UNIQUE (run_id, idempotency_key),
    CONSTRAINT fk_signal_run FOREIGN KEY (run_id) REFERENCES trading_runs (id),
    CONSTRAINT fk_signal_instr FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT ck_signal_rule CHECK (rule_decision IN ('BUY_CANDIDATE','SELL_CANDIDATE','NO_TRADE','INSUFFICIENT_DATA')),
    CONSTRAINT ck_signal_final CHECK (final_status IN ('NO_TRADE','REJECTED','APPROVED','ERROR'))
);
CREATE INDEX ix_signal_run_time ON trading_signals (run_id, bar_time);
CREATE INDEX ix_signal_strategy ON trading_signals (run_id, strategy_name, final_status);

-- Raw model output (raw_response) is kept apart from validated application state (parsed_json).
CREATE TABLE ai_analysis_records (
    id                 VARCHAR(36)  NOT NULL,
    run_id             VARCHAR(36)  NOT NULL,
    run_mode           VARCHAR(16)  NOT NULL,
    signal_id          VARCHAR(36)  NOT NULL,
    created_at         DATETIME(3)  NOT NULL,
    analyst            VARCHAR(24)  NOT NULL,
    model              VARCHAR(80)  NULL,
    prompt_version     VARCHAR(24)  NOT NULL,
    schema_version     VARCHAR(24)  NOT NULL,
    prompt_hash        VARCHAR(64)  NOT NULL,
    request_json       LONGTEXT     NULL,
    raw_response       LONGTEXT     NULL,
    parsed_json        LONGTEXT     NULL,
    validation_status  VARCHAR(16)  NOT NULL,
    validation_errors  LONGTEXT     NULL,
    latency_ms         BIGINT       NULL,
    input_tokens       BIGINT       NULL,
    output_tokens      BIGINT       NULL,
    reused_from_id     VARCHAR(36)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_run FOREIGN KEY (run_id) REFERENCES trading_runs (id),
    CONSTRAINT ck_ai_status CHECK (validation_status IN ('VALID','INVALID','ERROR','TIMEOUT','REFUSED','SKIPPED'))
);
CREATE INDEX ix_ai_signal ON ai_analysis_records (signal_id);
CREATE INDEX ix_ai_hash ON ai_analysis_records (prompt_hash, analyst);

CREATE TABLE trade_orders (
    id                VARCHAR(36)   NOT NULL,
    run_id            VARCHAR(36)   NOT NULL,
    run_mode          VARCHAR(16)   NOT NULL,
    client_order_id   VARCHAR(80)   NOT NULL,
    signal_id         VARCHAR(36)   NULL,
    position_id       VARCHAR(36)   NULL,
    instrument_id     BIGINT        NOT NULL,
    side              VARCHAR(4)    NOT NULL,
    order_type        VARCHAR(8)    NOT NULL,
    purpose           VARCHAR(12)   NOT NULL,
    quantity          INT           NOT NULL,
    limit_price       DECIMAL(18,4) NULL,
    trigger_price     DECIMAL(18,4) NULL,
    plan_stop_loss    DECIMAL(18,4) NULL,
    plan_target       DECIMAL(18,4) NULL,
    plan_initial_risk DECIMAL(18,2) NULL,
    plan_strategy     VARCHAR(48)   NULL,
    status            VARCHAR(12)   NOT NULL,
    filled_quantity   INT           NOT NULL DEFAULT 0,
    avg_fill_price    DECIMAL(18,4) NULL,
    reject_reason     VARCHAR(500)  NULL,
    simulated         TINYINT(1)    NOT NULL DEFAULT 1,
    broker_order_id   VARCHAR(64)   NULL,
    broker_status     VARCHAR(32)   NULL,
    created_at        DATETIME(3)   NOT NULL,
    updated_at        DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_client UNIQUE (run_id, client_order_id),
    CONSTRAINT fk_order_run FOREIGN KEY (run_id) REFERENCES trading_runs (id),
    CONSTRAINT fk_order_instr FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT ck_order_side CHECK (side IN ('BUY','SELL')),
    CONSTRAINT ck_order_type CHECK (order_type IN ('MARKET','LIMIT')),
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING','PARTIAL','FILLED','CANCELLED','REJECTED')),
    CONSTRAINT ck_order_qty CHECK (quantity > 0 AND filled_quantity >= 0 AND filled_quantity <= quantity),
    CONSTRAINT ck_order_paper_sim CHECK (run_mode = 'LIVE' OR simulated = 1)
);
CREATE INDEX ix_order_run_status ON trade_orders (run_id, status);

-- market_price = observed market reference price; fill_price = simulated execution price (never a Groww fill when simulated=1)
CREATE TABLE trade_executions (
    id               VARCHAR(36)   NOT NULL,
    run_id           VARCHAR(36)   NOT NULL,
    run_mode         VARCHAR(16)   NOT NULL,
    order_id         VARCHAR(36)   NOT NULL,
    instrument_id    BIGINT        NOT NULL,
    side             VARCHAR(4)    NOT NULL,
    quantity         INT           NOT NULL,
    market_price     DECIMAL(18,4) NOT NULL,
    fill_price       DECIMAL(18,4) NOT NULL,
    slippage_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    brokerage        DECIMAL(18,4) NOT NULL DEFAULT 0,
    statutory_costs  DECIMAL(18,4) NOT NULL DEFAULT 0,
    total_costs      DECIMAL(18,4) NOT NULL DEFAULT 0,
    simulated        TINYINT(1)    NOT NULL DEFAULT 1,
    broker_trade_id  VARCHAR(64)   NULL,
    executed_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_exec_order FOREIGN KEY (order_id) REFERENCES trade_orders (id),
    CONSTRAINT ck_exec_qty CHECK (quantity > 0),
    CONSTRAINT ck_exec_paper_sim CHECK (run_mode = 'LIVE' OR simulated = 1)
);
CREATE INDEX ix_exec_run ON trade_executions (run_id, executed_at);
CREATE INDEX ix_exec_order ON trade_executions (order_id);

CREATE TABLE positions (
    id                 VARCHAR(36)   NOT NULL,
    run_id             VARCHAR(36)   NOT NULL,
    run_mode           VARCHAR(16)   NOT NULL,
    instrument_id      BIGINT        NOT NULL,
    signal_id          VARCHAR(36)   NULL,
    strategy_name      VARCHAR(48)   NULL,
    side               VARCHAR(5)    NOT NULL,
    quantity           INT           NOT NULL,
    open_quantity      INT           NOT NULL,
    avg_entry_price    DECIMAL(18,4) NOT NULL,
    stop_loss          DECIMAL(18,4) NULL,
    target_price       DECIMAL(18,4) NULL,
    status             VARCHAR(8)    NOT NULL,
    opened_at          DATETIME(3)   NOT NULL,
    closed_at          DATETIME(3)   NULL,
    avg_exit_price     DECIMAL(18,4) NULL,
    exit_reason        VARCHAR(24)   NULL,
    realized_pnl_gross DECIMAL(18,2)  NOT NULL DEFAULT 0,
    total_costs        DECIMAL(18,2)  NOT NULL DEFAULT 0,
    realized_pnl_net   DECIMAL(18,2)  NOT NULL DEFAULT 0,
    initial_risk       DECIMAL(18,2)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pos_run FOREIGN KEY (run_id) REFERENCES trading_runs (id),
    CONSTRAINT fk_pos_instr FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT ck_pos_side CHECK (side IN ('LONG','SHORT')),
    CONSTRAINT ck_pos_status CHECK (status IN ('OPEN','CLOSED'))
);
CREATE INDEX ix_pos_run_status ON positions (run_id, status);

CREATE TABLE daily_pnl (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    run_id           VARCHAR(36)   NOT NULL,
    run_mode         VARCHAR(16)   NOT NULL,
    trade_date       DATE          NOT NULL,
    realized_pnl_net DECIMAL(18,2) NOT NULL DEFAULT 0,
    total_costs      DECIMAL(18,2) NOT NULL DEFAULT 0,
    trades_count     INT           NOT NULL DEFAULT 0,
    wins             INT           NOT NULL DEFAULT 0,
    losses           INT           NOT NULL DEFAULT 0,
    consecutive_losses INT         NOT NULL DEFAULT 0,
    locked           TINYINT(1)    NOT NULL DEFAULT 0,
    lock_reason      VARCHAR(255)  NULL,
    locked_at        DATETIME(3)   NULL,
    updated_at       DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_daily UNIQUE (run_id, trade_date),
    CONSTRAINT fk_daily_run FOREIGN KEY (run_id) REFERENCES trading_runs (id)
);

CREATE TABLE risk_events (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    run_id      VARCHAR(36)   NULL,
    run_mode    VARCHAR(16)   NULL,
    created_at  DATETIME(3)   NOT NULL,
    signal_id   VARCHAR(36)   NULL,
    event_type  VARCHAR(24)   NOT NULL,
    check_name  VARCHAR(48)   NULL,
    severity    VARCHAR(8)    NOT NULL,
    detail      VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_risk_run ON risk_events (run_id, created_at);

CREATE TABLE application_settings (
    setting_key    VARCHAR(80)  NOT NULL,
    setting_value  VARCHAR(500) NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    updated_by     VARCHAR(64)  NOT NULL,
    PRIMARY KEY (setting_key)
);

CREATE TABLE audit_logs (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(3)   NOT NULL,
    actor        VARCHAR(64)   NOT NULL,
    action       VARCHAR(48)   NOT NULL,
    entity_type  VARCHAR(32)   NULL,
    entity_id    VARCHAR(64)   NULL,
    detail       VARCHAR(2000) NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_audit_time ON audit_logs (created_at);

-- Defaults: paper mode, kill switch off, live NOT authorized.
INSERT INTO application_settings (setting_key, setting_value, updated_at, updated_by) VALUES
  ('execution_mode', 'PAPER', CURRENT_TIMESTAMP(3), 'migration'),
  ('kill_switch', 'OFF', CURRENT_TIMESTAMP(3), 'migration'),
  ('live_authorized', 'NO', CURRENT_TIMESTAMP(3), 'migration');
