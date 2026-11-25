-- Phase 5: Enterprise Distributed Two-Phase Token Billing Ledger, Monthly Budget Cap
-- & Guardrail Security Audit Logs

CREATE TABLE billing_accounts (
    user_id               UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    balance_micros        BIGINT      NOT NULL DEFAULT 50000000 CHECK (balance_micros >= 0),
    frozen_micros         BIGINT      NOT NULL DEFAULT 0 CHECK (frozen_micros >= 0),
    monthly_budget_micros BIGINT      NOT NULL DEFAULT 100000000 CHECK (monthly_budget_micros > 0),
    monthly_spent_micros  BIGINT      NOT NULL DEFAULT 0 CHECK (monthly_spent_micros >= 0),
    budget_month          VARCHAR(7)  NOT NULL DEFAULT TO_CHAR(NOW(), 'YYYY-MM'),
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE billing_ledger_entries (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    run_id               UUID         REFERENCES agent_runs(id) ON DELETE SET NULL,
    request_id           VARCHAR(128) NOT NULL UNIQUE,
    entry_type           VARCHAR(24)  NOT NULL,
    amount_micros        BIGINT       NOT NULL,
    balance_after_micros BIGINT       NOT NULL,
    frozen_after_micros  BIGINT       NOT NULL,
    model_alias          VARCHAR(64),
    input_tokens         INT          NOT NULL DEFAULT 0,
    cached_tokens        INT          NOT NULL DEFAULT 0,
    output_tokens        INT          NOT NULL DEFAULT 0,
    cache_saved_micros   BIGINT       NOT NULL DEFAULT 0,
    sandbox_calls        INT          NOT NULL DEFAULT 0,
    description          VARCHAR(255) NOT NULL DEFAULT '',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_billing_ledger_user_created ON billing_ledger_entries (user_id, created_at DESC);
CREATE INDEX idx_billing_ledger_run_id ON billing_ledger_entries (run_id);

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS frozen_micros BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS settled_micros BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS billing_status VARCHAR(24) NOT NULL DEFAULT 'NONE';

CREATE TABLE guardrail_audit_logs (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID,
    book_id    UUID,
    run_id     UUID,
    stage      VARCHAR(16) NOT NULL,
    action     VARCHAR(16) NOT NULL,
    rule_code  VARCHAR(64) NOT NULL,
    detail     TEXT        NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_guardrail_audit_user ON guardrail_audit_logs (user_id, created_at DESC);
