-- Flash loan service durable schema (embedded H2, file-backed).
-- Every successful loan unit commits its pool balance changes, loan
-- record and ledger entries in a single database transaction. Uncommitted
-- changes are rolled back automatically by H2 recovery after a crash.

CREATE TABLE IF NOT EXISTS pools (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    currency    VARCHAR(32) NOT NULL,
    balance     BIGINT NOT NULL,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS service_config (
    id               TINYINT PRIMARY KEY,
    fee_rate         DECIMAL(12, 8) NOT NULL,
    max_loan_amount  BIGINT NOT NULL,
    max_steps        INT NOT NULL,
    timeout_ms       BIGINT NOT NULL,
    version          BIGINT NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS loans (
    request_id       VARCHAR(128) PRIMARY KEY,
    pool_id          BIGINT NOT NULL,
    currency         VARCHAR(32) NOT NULL,
    amount           BIGINT NOT NULL,
    fee              BIGINT NOT NULL,
    amount_returned  BIGINT NOT NULL,
    status           VARCHAR(16) NOT NULL,
    config_version   BIGINT NOT NULL,
    failure_code     VARCHAR(64),
    failure_reason   VARCHAR(512),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(128),
    seq            BIGINT NOT NULL,
    step           INT NOT NULL,
    account        VARCHAR(160) NOT NULL,
    currency       VARCHAR(32) NOT NULL,
    delta          BIGINT NOT NULL,
    balance_after  BIGINT NOT NULL,
    action         VARCHAR(32) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_account_seq ON ledger (account, seq);
CREATE INDEX IF NOT EXISTS ix_ledger_request ON ledger (request_id);

-- Deterministic default configuration row (version 1).
MERGE INTO service_config
    (id, fee_rate, max_loan_amount, max_steps, timeout_ms, version, updated_at)
KEY (id)
VALUES (1, 0.001, 1000000000, 32, 5000, 1, CURRENT_TIMESTAMP);
