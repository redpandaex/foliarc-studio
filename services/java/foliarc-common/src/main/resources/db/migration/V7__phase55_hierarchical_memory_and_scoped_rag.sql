-- Phase 5.5: Hierarchical Long-Term Memory (Chapter/Volume Summaries + Auto-Emergent L0 Charter),
-- Cascade Dirty Invalidation, and Three-Scope Knowledge Base (BOOK / USER / GLOBAL + Zero-Copy Grants)

-- 1. Zero-config auto-emergent L0 Story Charter on books (author_defined > auto_inferred)
ALTER TABLE books
    ADD COLUMN IF NOT EXISTS author_charter TEXT,
    ADD COLUMN IF NOT EXISTS auto_charter TEXT,
    ADD COLUMN IF NOT EXISTS charter_source VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS charter_chapter_count INT NOT NULL DEFAULT 0;

-- 2. Content hash & cascade dirty invalidation tracking on chapters
ALTER TABLE chapters
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS summary_dirty BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Hierarchical L2 Chapter Summaries (150~250 chars structured recap per chapter)
CREATE TABLE IF NOT EXISTS chapter_summaries (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id             UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id            UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chapter_id          UUID         NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    chapter_no          INT          NOT NULL,
    chapter_title       VARCHAR(200) NOT NULL DEFAULT '',
    content_hash        VARCHAR(64)  NOT NULL DEFAULT '',
    summary_text        TEXT         NOT NULL,
    key_events          TEXT         NOT NULL DEFAULT '',
    involved_characters TEXT         NOT NULL DEFAULT '',
    ending_hook         TEXT         NOT NULL DEFAULT '',
    status              VARCHAR(24)  NOT NULL DEFAULT 'READY',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chapter_summaries_chapter UNIQUE (chapter_id)
);

CREATE INDEX IF NOT EXISTS idx_chapter_summaries_book_no
    ON chapter_summaries (book_id, chapter_no);

-- 4. Hierarchical L2 Volume / Arc Summaries (10 chapters rolled up into one milestone)
CREATE TABLE IF NOT EXISTS volume_summaries (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id          UUID        NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    volume_no        INT         NOT NULL,
    start_chapter_no INT         NOT NULL,
    end_chapter_no   INT         NOT NULL,
    summary_text     TEXT        NOT NULL,
    source_hash      VARCHAR(64) NOT NULL DEFAULT '',
    status           VARCHAR(24) NOT NULL DEFAULT 'READY',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_volume_summaries_book_vol UNIQUE (book_id, volume_no)
);

CREATE INDEX IF NOT EXISTS idx_volume_summaries_book_vol
    ON volume_summaries (book_id, volume_no);

-- 5. Three-Scope Knowledge Base extensions (BOOK / USER / GLOBAL) + SHA-256 dedup + readonly guard
ALTER TABLE knowledge_documents
    ALTER COLUMN book_id DROP NOT NULL;

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS scope VARCHAR(24) NOT NULL DEFAULT 'BOOK',
    ADD COLUMN IF NOT EXISTS uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS genre_tags VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS content_sha256 VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS is_readonly BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_knowledge_docs_scope_enabled
    ON knowledge_documents (scope, enabled)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_docs_owner_scope
    ON knowledge_documents (owner_id, scope)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_docs_dedup
    ON knowledge_documents (owner_id, scope, content_sha256)
    WHERE deleted_at IS NULL AND content_sha256 <> '';

ALTER TABLE document_chunks
    ALTER COLUMN book_id DROP NOT NULL;

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS scope VARCHAR(24) NOT NULL DEFAULT 'BOOK';

-- 6. Zero-copy admin-to-author document grants (no duplicate vectors!)
CREATE TABLE IF NOT EXISTS knowledge_grants (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID        NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    grantee_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    granted_by      UUID        REFERENCES users(id) ON DELETE SET NULL,
    permission      VARCHAR(24) NOT NULL DEFAULT 'READ',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_knowledge_grants_doc_user UNIQUE (document_id, grantee_user_id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_grants_grantee
    ON knowledge_grants (grantee_user_id);

-- 7. Per-book opt-in/opt-out bindings for USER / ASSIGNED / GLOBAL reference docs
CREATE TABLE IF NOT EXISTS book_knowledge_bindings (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id     UUID        NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    document_id UUID        NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_book_knowledge_bindings UNIQUE (book_id, document_id)
);

CREATE INDEX IF NOT EXISTS idx_book_kb_bindings_book
    ON book_knowledge_bindings (book_id, enabled);
