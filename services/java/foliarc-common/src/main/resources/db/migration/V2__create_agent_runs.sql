-- Phase 1: Agent Streaming Runs & Cooperative Pause/Resume Checkpoints

CREATE TABLE agent_runs (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id          UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    chapter_id       UUID         REFERENCES chapters(id) ON DELETE SET NULL,
    owner_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action           VARCHAR(32)  NOT NULL,
    model_alias      VARCHAR(64)  NOT NULL DEFAULT 'writer-main',
    instruction      TEXT         NOT NULL DEFAULT '',
    chapter_context  TEXT         NOT NULL DEFAULT '',
    status           VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    accumulated_text TEXT         NOT NULL DEFAULT '',
    last_seq         INT          NOT NULL DEFAULT 0,
    last_event_id    VARCHAR(64),
    input_tokens     INT          NOT NULL DEFAULT 0,
    cached_tokens    INT          NOT NULL DEFAULT 0,
    output_tokens    INT          NOT NULL DEFAULT 0,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_runs_owner_chapter ON agent_runs (owner_id, chapter_id, created_at DESC);
CREATE INDEX idx_agent_runs_book_id ON agent_runs (book_id);
