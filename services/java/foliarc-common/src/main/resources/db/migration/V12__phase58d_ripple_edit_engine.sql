CREATE TABLE IF NOT EXISTS ripple_jobs (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id                  UUID        NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id                 UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_section_id        UUID        NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    source_chapter_no        INT         NOT NULL,
    source_narrative_order   BIGINT      NOT NULL,
    vip_plan_code            VARCHAR(24) NOT NULL DEFAULT 'FREE',
    range_mode               VARCHAR(24) NOT NULL DEFAULT 'BIDIRECTIONAL',
    range_start_chapter      INT         NOT NULL,
    range_end_chapter        INT         NOT NULL,
    range_start_order        BIGINT      NOT NULL,
    range_end_order          BIGINT      NOT NULL,
    selected_volume_ids      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    max_candidates_cap       INT         NOT NULL DEFAULT 50,
    max_patch_batch_limit    INT         NOT NULL DEFAULT 20,
    status                   VARCHAR(32) NOT NULL DEFAULT 'ANALYZING',
    old_content_snapshot     TEXT        NOT NULL DEFAULT '',
    new_content_snapshot     TEXT        NOT NULL DEFAULT '',
    change_note              TEXT        NOT NULL DEFAULT '',
    change_summary           TEXT        NOT NULL DEFAULT '',
    fact_deltas_json         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    scanned_section_count    INT         NOT NULL DEFAULT 0,
    candidate_section_count  INT         NOT NULL DEFAULT 0,
    backward_conflict_count  INT         NOT NULL DEFAULT 0,
    forward_conflict_count   INT         NOT NULL DEFAULT 0,
    minor_mention_count      INT         NOT NULL DEFAULT 0,
    estimated_cost_micros    BIGINT      NOT NULL DEFAULT 0,
    frozen_cost_micros       BIGINT      NOT NULL DEFAULT 0,
    actual_cost_micros       BIGINT      NOT NULL DEFAULT 0,
    next_batch_no            INT         NOT NULL DEFAULT 1,
    error_message            TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ripple_jobs_book ON ripple_jobs (book_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ripple_jobs_owner_created ON ripple_jobs (owner_id, created_at);

CREATE TABLE IF NOT EXISTS ripple_impacts (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id             UUID        NOT NULL REFERENCES ripple_jobs(id) ON DELETE CASCADE,
    target_section_id  UUID        NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    chapter_no         INT         NOT NULL,
    section_no         INT         NOT NULL,
    narrative_order    BIGINT      NOT NULL,
    chapter_title      VARCHAR(200) NOT NULL DEFAULT '',
    direction          VARCHAR(16) NOT NULL,
    severity           VARCHAR(32) NOT NULL,
    conflict_reason    TEXT        NOT NULL DEFAULT '',
    matched_excerpt    TEXT        NOT NULL DEFAULT '',
    patch_status       VARCHAR(24) NOT NULL DEFAULT 'UNSELECTED',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ripple_impacts_job_order ON ripple_impacts (job_id, direction, narrative_order);

CREATE TABLE IF NOT EXISTS ripple_patches (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id             UUID        NOT NULL REFERENCES ripple_jobs(id) ON DELETE CASCADE,
    impact_id          UUID        NOT NULL REFERENCES ripple_impacts(id) ON DELETE CASCADE,
    target_section_id  UUID        NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    batch_no           INT         NOT NULL DEFAULT 1,
    base_version       BIGINT      NOT NULL,
    hunks_json         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status             VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ripple_patches_job ON ripple_patches (job_id, batch_no, status);
