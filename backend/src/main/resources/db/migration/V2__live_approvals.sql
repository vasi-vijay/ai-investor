-- Manual-approval queue for LIVE orders. Rows exist only if live trading was enabled, authorised and gated.
-- No order reaches a broker without an APPROVED row created by a human (approved_by != proposed_by is not required
-- in a single-operator pilot, but both are recorded).
CREATE TABLE live_order_approvals (
    id               VARCHAR(36)   NOT NULL,
    run_id           VARCHAR(36)   NOT NULL,
    signal_id        VARCHAR(36)   NULL,
    instrument_id    BIGINT        NOT NULL,
    side             VARCHAR(4)    NOT NULL,
    order_type       VARCHAR(8)    NOT NULL,
    quantity         INT           NOT NULL,
    limit_price      DECIMAL(18,4) NULL,
    est_order_value  DECIMAL(18,2) NOT NULL,
    reference        VARCHAR(40)   NOT NULL,
    risk_json        LONGTEXT      NULL,
    status           VARCHAR(20)   NOT NULL,
    proposed_by      VARCHAR(64)   NOT NULL,
    proposed_at      DATETIME(3)   NOT NULL,
    expires_at       DATETIME(3)   NOT NULL,
    decided_by       VARCHAR(64)   NULL,
    decided_at       DATETIME(3)   NULL,
    broker_order_id  VARCHAR(64)   NULL,
    local_order_id   VARCHAR(36)   NULL,
    note             VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_live_ref UNIQUE (reference),
    CONSTRAINT fk_live_instr FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT ck_live_status CHECK (status IN ('PENDING_APPROVAL','REJECTED','EXPIRED','SUBMITTED','UNKNOWN_RECONCILE','FAILED','CANCELLED'))
);
CREATE INDEX ix_live_status ON live_order_approvals (status, proposed_at);
