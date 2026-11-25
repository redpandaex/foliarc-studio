ALTER TABLE books ADD COLUMN IF NOT EXISTS precision_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';

CREATE TABLE IF NOT EXISTS volumes (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id          UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    volume_no        INT          NOT NULL,
    title            VARCHAR(200) NOT NULL DEFAULT '',
    arc_goal         TEXT         NOT NULL DEFAULT '',
    pacing_notes     TEXT         NOT NULL DEFAULT '',
    realm_scope      VARCHAR(120) NOT NULL DEFAULT '',
    start_chapter_no INT,
    end_chapter_no   INT,
    status           VARCHAR(24)  NOT NULL DEFAULT 'WRITING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_volumes_book_no UNIQUE (book_id, volume_no)
);

ALTER TABLE chapters
    ADD COLUMN IF NOT EXISTS volume_id UUID REFERENCES volumes(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS structure_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE';

CREATE TABLE IF NOT EXISTS chapter_sections (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id         UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    chapter_id      UUID         NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    chapter_no      INT          NOT NULL,
    section_no      INT          NOT NULL DEFAULT 1,
    narrative_order BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL DEFAULT '',
    content         TEXT         NOT NULL DEFAULT '',
    word_count      INT          NOT NULL DEFAULT 0,
    content_hash    VARCHAR(64)  NOT NULL DEFAULT '',
    summary_dirty   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sections_chapter_no UNIQUE (chapter_id, section_no),
    CONSTRAINT uq_sections_book_order UNIQUE (book_id, narrative_order)
);

CREATE INDEX IF NOT EXISTS idx_sections_book_order_desc
    ON chapter_sections (book_id, narrative_order DESC);

CREATE TABLE IF NOT EXISTS section_versions (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id     UUID         NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    version_no     INT          NOT NULL,
    content        TEXT         NOT NULL DEFAULT '',
    change_summary VARCHAR(255),
    created_by     UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_section_versions UNIQUE (section_id, version_no)
);

CREATE TABLE IF NOT EXISTS section_summaries (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id             UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id            UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chapter_id          UUID         NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    section_id          UUID         NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    chapter_no          INT          NOT NULL,
    section_no          INT          NOT NULL DEFAULT 1,
    narrative_order     BIGINT       NOT NULL,
    chapter_title       VARCHAR(200) NOT NULL DEFAULT '',
    section_title       VARCHAR(200) NOT NULL DEFAULT '',
    content_hash        VARCHAR(64)  NOT NULL DEFAULT '',
    time_and_place      VARCHAR(255) NOT NULL DEFAULT '',
    summary_text        TEXT         NOT NULL,
    state_changes       TEXT         NOT NULL DEFAULT '',
    clue_signals        TEXT         NOT NULL DEFAULT '',
    pov_notes           TEXT         NOT NULL DEFAULT '',
    involved_characters TEXT         NOT NULL DEFAULT '',
    ending_hook         TEXT         NOT NULL DEFAULT '',
    status              VARCHAR(24)  NOT NULL DEFAULT 'READY',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_section_summaries_section UNIQUE (section_id)
);

CREATE INDEX IF NOT EXISTS idx_section_summaries_book_order
    ON section_summaries (book_id, narrative_order DESC);

CREATE TABLE IF NOT EXISTS story_rollup_summaries (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id          UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier_level       INT          NOT NULL,
    seq_no           INT          NOT NULL,
    volume_id        UUID         REFERENCES volumes(id) ON DELETE SET NULL,
    start_order      BIGINT       NOT NULL,
    end_order        BIGINT       NOT NULL,
    start_chapter_no INT          NOT NULL,
    end_chapter_no   INT          NOT NULL,
    title            VARCHAR(200) NOT NULL DEFAULT '',
    summary_text     TEXT         NOT NULL,
    open_threads     TEXT         NOT NULL DEFAULT '',
    source_hash      VARCHAR(64)  NOT NULL DEFAULT '',
    status           VARCHAR(24)  NOT NULL DEFAULT 'READY',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rollup_book_tier_seq UNIQUE (book_id, tier_level, seq_no)
);

CREATE INDEX IF NOT EXISTS idx_rollup_book_tier_order
    ON story_rollup_summaries (book_id, tier_level, end_order DESC);

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS narrative_order BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS section_id UUID;

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL;

-- Copy legacy data in place while retaining the original chapter and summary tables.
INSERT INTO volumes (book_id, owner_id, volume_no, title)
SELECT b.id, b.owner_id, 1, '第一卷'
FROM books b
WHERE NOT EXISTS (SELECT 1 FROM volumes v WHERE v.book_id = b.id)
ON CONFLICT (book_id, volume_no) DO NOTHING;

UPDATE chapters c
SET volume_id = v.id
FROM volumes v
WHERE c.book_id = v.book_id AND v.volume_no = 1 AND c.volume_id IS NULL;

INSERT INTO chapter_sections (book_id, chapter_id, chapter_no, section_no, narrative_order,
                              title, content, word_count, content_hash, summary_dirty, is_default,
                              created_at, updated_at)
SELECT c.book_id, c.id, c.chapter_no, 1, c.chapter_no::bigint * 1000 + 1,
       c.title, c.content, c.word_count, c.content_hash, c.summary_dirty, TRUE,
       c.created_at, c.updated_at
FROM chapters c
WHERE NOT EXISTS (SELECT 1 FROM chapter_sections s WHERE s.chapter_id = c.id)
ON CONFLICT (chapter_id, section_no) DO NOTHING;

INSERT INTO section_summaries (book_id, owner_id, chapter_id, section_id, chapter_no,
                               section_no, narrative_order, chapter_title, section_title, content_hash,
                               summary_text, state_changes, involved_characters, ending_hook,
                               status, created_at, updated_at)
SELECT cs.book_id, cs.owner_id, cs.chapter_id, s.id, s.chapter_no,
       s.section_no, s.narrative_order, cs.chapter_title, s.title, cs.content_hash,
       cs.summary_text, cs.key_events, cs.involved_characters, cs.ending_hook,
       CASE WHEN s.summary_dirty AND s.content_hash <> cs.content_hash THEN 'STALE' ELSE cs.status END,
       cs.created_at, cs.updated_at
FROM chapter_summaries cs
JOIN chapter_sections s ON s.chapter_id = cs.chapter_id AND s.is_default
ON CONFLICT (section_id) DO NOTHING;

INSERT INTO story_rollup_summaries (book_id, owner_id, tier_level, seq_no, start_order, end_order,
                                    start_chapter_no, end_chapter_no, title, summary_text,
                                    source_hash, status, created_at, updated_at)
SELECT book_id, owner_id, 1, volume_no, start_chapter_no::bigint * 1000 + 1,
       end_chapter_no::bigint * 1000 + 999, start_chapter_no, end_chapter_no,
       '第' || volume_no || '卷', summary_text, source_hash, status, created_at, updated_at
FROM volume_summaries
ON CONFLICT (book_id, tier_level, seq_no) DO NOTHING;
