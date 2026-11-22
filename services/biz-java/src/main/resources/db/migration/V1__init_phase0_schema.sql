-- Phase 0 Core Domain Schema: users, books, chapters, chapter_versions
-- Managed by Flyway (PostgreSQL 16)

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'AUTHOR',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE books (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title      VARCHAR(200) NOT NULL,
    genre      VARCHAR(64),
    synopsis   TEXT,
    status     VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_books_owner_id ON books (owner_id);

CREATE TABLE chapters (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id    UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    chapter_no INT          NOT NULL,
    title      VARCHAR(200) NOT NULL,
    content    TEXT         NOT NULL DEFAULT '',
    word_count INT          NOT NULL DEFAULT 0,
    status     VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chapters_book_no UNIQUE (book_id, chapter_no)
);

CREATE INDEX idx_chapters_book_id ON chapters (book_id);

CREATE TABLE chapter_versions (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chapter_id     UUID         NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    version_no     INT          NOT NULL,
    content        TEXT         NOT NULL,
    change_summary VARCHAR(255),
    created_by     UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chapter_versions_no UNIQUE (chapter_id, version_no)
);

CREATE INDEX idx_chapter_versions_chapter_id ON chapter_versions (chapter_id);
