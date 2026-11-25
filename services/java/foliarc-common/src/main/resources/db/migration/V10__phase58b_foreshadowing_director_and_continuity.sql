ALTER TABLE foreshadowings
    ADD COLUMN IF NOT EXISTS code VARCHAR(16) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS importance VARCHAR(24) NOT NULL DEFAULT 'SUBPLOT',
    ADD COLUMN IF NOT EXISTS reveal_policy VARCHAR(24) NOT NULL DEFAULT 'HIDE_TRUTH',
    ADD COLUMN IF NOT EXISTS surface_clue TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS hidden_truth TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS planted_section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS planted_narrative_order BIGINT,
    ADD COLUMN IF NOT EXISTS planted_quote TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS resolved_section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS target_chapter_min INT,
    ADD COLUMN IF NOT EXISTS target_chapter_max INT,
    ADD COLUMN IF NOT EXISTS involved_characters TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS hint_history_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'AUTHOR',
    ADD COLUMN IF NOT EXISTS author_locked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_foreshadowings_book_code
    ON foreshadowings (book_id, code) WHERE code <> '';

ALTER TABLE characters
    ADD COLUMN IF NOT EXISTS aliases TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_seen_order BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS arc_scope VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS locked_fields JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS character_state_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
    narrative_order BIGINT NOT NULL,
    section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL,
    snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    source VARCHAR(16) NOT NULL DEFAULT 'AI_EXTRACTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_char_snapshots_char_order
    ON character_state_snapshots (character_id, narrative_order DESC);
CREATE INDEX IF NOT EXISTS idx_char_snapshots_section
    ON character_state_snapshots (section_id);

ALTER TABLE character_relations
    ADD COLUMN IF NOT EXISTS source_section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS narrative_order BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'AUTHOR',
    ADD COLUMN IF NOT EXISTS known_by TEXT NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE timeline_events
    ADD COLUMN IF NOT EXISTS source_section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS narrative_order BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'AUTHOR',
    ADD COLUMN IF NOT EXISTS known_by TEXT NOT NULL DEFAULT 'PUBLIC';

-- Preserve authored keys while allowing the same fact to be sourced from multiple sections.
CREATE UNIQUE INDEX IF NOT EXISTS uq_char_relations_author
    ON character_relations (book_id, source_character, target_character, relation_type) WHERE source = 'AUTHOR';
CREATE UNIQUE INDEX IF NOT EXISTS uq_char_relations_ai_section
    ON character_relations (source_section_id, book_id, source_character, target_character, relation_type)
    WHERE source = 'AI_EXTRACTED' AND source_section_id IS NOT NULL;
ALTER TABLE character_relations DROP CONSTRAINT IF EXISTS uq_char_relations_book_pair;

CREATE UNIQUE INDEX IF NOT EXISTS uq_timeline_events_author
    ON timeline_events (book_id, chapter_no, event_title) WHERE source = 'AUTHOR';
CREATE UNIQUE INDEX IF NOT EXISTS uq_timeline_events_ai_section
    ON timeline_events (source_section_id, book_id, chapter_no, event_title)
    WHERE source = 'AI_EXTRACTED' AND source_section_id IS NOT NULL;
ALTER TABLE timeline_events DROP CONSTRAINT IF EXISTS uq_timeline_book_chapter_title;

ALTER TABLE chapter_sections
    ADD COLUMN IF NOT EXISTS pov_character VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS pov_character VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS foreshadowing_directives_json JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS memory_proposals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    section_id UUID REFERENCES chapter_sections(id) ON DELETE SET NULL,
    proposal_type VARCHAR(32) NOT NULL,
    target_entity_id UUID,
    target_name VARCHAR(160) NOT NULL DEFAULT '',
    summary VARCHAR(500) NOT NULL DEFAULT '',
    diff_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_memory_proposals_book_status
    ON memory_proposals (book_id, status, created_at DESC);

WITH existing_codes AS (
    SELECT book_id, MAX(substring(upper(code) FROM '^#F([0-9]+)$')::bigint) AS max_code
      FROM foreshadowings
     WHERE code <> ''
     GROUP BY book_id
), numbered AS (
    SELECT f.id, (COALESCE(e.max_code, 0) + row_number() OVER (
        PARTITION BY f.book_id ORDER BY f.created_at, f.id))::text AS code_number
      FROM foreshadowings f
      LEFT JOIN existing_codes e ON e.book_id = f.book_id
     WHERE f.code = ''
)
UPDATE foreshadowings f
   SET code = '#F' || lpad(n.code_number, GREATEST(2, length(n.code_number)), '0')
  FROM numbered n
 WHERE f.id = n.id;

UPDATE foreshadowings SET surface_clue = description WHERE surface_clue = '' AND description <> '';
UPDATE foreshadowings
   SET status = CASE WHEN planted_chapter_no IS NULL THEN 'PLANNED' ELSE 'PLANTED' END
 WHERE status = 'OPEN';
UPDATE characters SET lifecycle_status = 'CORE_PINNED'
 WHERE role_type = 'PROTAGONIST' AND lifecycle_status = 'ACTIVE';
