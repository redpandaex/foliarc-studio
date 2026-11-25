-- Phase 4: Multi-Agent Editorial Team Orchestration, PostgreSQL Blackboard Checkpoints
-- & Human-in-the-Loop (HITL) Outline Approval Interrupt/Resume

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS current_node VARCHAR(48),
    ADD COLUMN IF NOT EXISTS interrupt_type VARCHAR(48),
    ADD COLUMN IF NOT EXISTS interrupt_payload_json TEXT,
    ADD COLUMN IF NOT EXISTS route_intent VARCHAR(48),
    ADD COLUMN IF NOT EXISTS revision_count INT NOT NULL DEFAULT 0;

CREATE TABLE agent_checkpoints (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id     UUID        NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    step_no    INT         NOT NULL,
    node_name  VARCHAR(48) NOT NULL,
    status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    state_json TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_agent_checkpoints_run_step UNIQUE (run_id, step_no)
);

CREATE INDEX idx_agent_checkpoints_run_step ON agent_checkpoints (run_id, step_no DESC);
