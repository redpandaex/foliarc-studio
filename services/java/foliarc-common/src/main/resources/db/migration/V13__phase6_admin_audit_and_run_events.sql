CREATE TABLE IF NOT EXISTS agent_run_events (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id       UUID        NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    seq          INT         NOT NULL DEFAULT 0,
    event_type   VARCHAR(48) NOT NULL,
    agent_name   VARCHAR(32) NOT NULL DEFAULT 'writer',
    trace_id     VARCHAR(64) NOT NULL DEFAULT '',
    latency_ms   INT         NOT NULL DEFAULT 0,
    payload_json JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_run_events_run_seq ON agent_run_events (run_id, seq);
CREATE INDEX IF NOT EXISTS idx_agent_run_events_trace ON agent_run_events (trace_id) WHERE trace_id <> '';
CREATE INDEX IF NOT EXISTS idx_agent_run_events_created ON agent_run_events (created_at DESC);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id   UUID         NOT NULL REFERENCES users(id),
    operator_name VARCHAR(64)  NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32)  NOT NULL,
    target_id     VARCHAR(128) NOT NULL,
    before_json   JSONB,
    after_json    JSONB,
    client_ip     VARCHAR(64)  NOT NULL DEFAULT '',
    trace_id      VARCHAR(64)  NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created ON admin_audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_target ON admin_audit_logs (target_type, target_id);
