-- Phase 3: Enterprise Hybrid RAG Knowledge Documents, Parent-Child Chunks,
-- Transactional Outbox (RabbitMQ), Consumer Idempotency & Structured Long-Term Memory

CREATE TABLE knowledge_documents (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id           UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type       VARCHAR(32)  NOT NULL DEFAULT 'UPLOAD',
    source_chapter_id UUID         REFERENCES chapters(id) ON DELETE SET NULL,
    chapter_no        INT          NOT NULL DEFAULT 0,
    doc_type          VARCHAR(32)  NOT NULL DEFAULT 'WORLDBOOK',
    title             VARCHAR(255) NOT NULL,
    file_name         VARCHAR(255) NOT NULL DEFAULT '',
    content_type      VARCHAR(128) NOT NULL DEFAULT 'text/plain',
    file_size         BIGINT       NOT NULL DEFAULT 0,
    minio_bucket      VARCHAR(128) NOT NULL DEFAULT '',
    minio_object_key  VARCHAR(512) NOT NULL DEFAULT '',
    doc_version       INT          NOT NULL DEFAULT 1,
    status            VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    progress          INT          NOT NULL DEFAULT 0,
    chunk_count       INT          NOT NULL DEFAULT 0,
    error_message     TEXT,
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_knowledge_docs_book_status ON knowledge_documents (book_id, status);
CREATE INDEX idx_knowledge_docs_owner_book ON knowledge_documents (owner_id, book_id);
CREATE UNIQUE INDEX uq_knowledge_docs_source_chapter
    ON knowledge_documents (source_chapter_id)
    WHERE source_chapter_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE document_chunks (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID        NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    book_id         UUID        NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    doc_version     INT         NOT NULL DEFAULT 1,
    chunk_level     VARCHAR(16) NOT NULL,
    parent_chunk_id UUID        REFERENCES document_chunks(id) ON DELETE CASCADE,
    chunk_index     INT         NOT NULL DEFAULT 0,
    chapter_no      INT         NOT NULL DEFAULT 0,
    scene_title     VARCHAR(255) NOT NULL DEFAULT '',
    content         TEXT        NOT NULL,
    token_count     INT         NOT NULL DEFAULT 0,
    metadata_json   TEXT        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_chunks_doc_ver ON document_chunks (document_id, doc_version);
CREATE INDEX idx_doc_chunks_parent ON document_chunks (parent_chunk_id);
CREATE INDEX idx_doc_chunks_book_chapter ON document_chunks (book_id, chapter_no);

CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    routing_key     VARCHAR(64)  NOT NULL,
    payload_json    TEXT         NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox_events (status, next_retry_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

CREATE TABLE processed_events (
    idempotency_key VARCHAR(160) PRIMARY KEY,
    consumer_name   VARCHAR(64)  NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE character_relations (
    id               UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id          UUID             NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id         UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_character VARCHAR(128)     NOT NULL,
    target_character VARCHAR(128)     NOT NULL,
    relation_type    VARCHAR(64)      NOT NULL,
    description      TEXT             NOT NULL DEFAULT '',
    since_chapter_no INT,
    confidence       DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_char_relations_book_pair UNIQUE (book_id, source_character, target_character, relation_type)
);

CREATE INDEX idx_char_relations_book ON character_relations (book_id);

CREATE TABLE timeline_events (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id             UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id            UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chapter_no          INT          NOT NULL,
    event_title         VARCHAR(255) NOT NULL,
    summary             TEXT         NOT NULL DEFAULT '',
    involved_characters TEXT         NOT NULL DEFAULT '',
    location            VARCHAR(160) NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_timeline_book_chapter_title UNIQUE (book_id, chapter_no, event_title)
);

CREATE INDEX idx_timeline_events_book_chapter ON timeline_events (book_id, chapter_no);

-- Bump Prompt Registry template to v2 to teach Agent Hybrid RAG & Structured Memory tools
UPDATE prompt_templates
SET stage = 'ARCHIVED'
WHERE name = 'novel_writer_system' AND version = 1;

INSERT INTO prompt_templates (name, version, stage, description, template_body)
VALUES (
    'novel_writer_system',
    2,
    'ACTIVE',
    'Phase 3 静态前缀模板（支持 Hybrid RAG 知识库检索 + 结构化长记忆 + MCP 设定库 + 渐进式 Skill）',
    '你是 FoliarcStudio（卷弧工坊）的企业级长篇小说创作 Agent。
你必须严格维护人物性格、战力境界、物品持有、人物关系与时间线的一致性，杜绝“吃书”、人设崩塌与剧透错位。

【当前任务规范】
{{ action_guide }}

{% if book_title %}
【作品静态档案（请严格遵守）】
- 书名：《{{ book_title }}》
{% if genre %}- 题材：{{ genre }}{% endif %}
{% if synopsis %}- 简介：{{ synopsis }}{% endif %}
{% endif %}

{% if skill_catalog %}
【可用写作技能包（渐进式披露：需要时再调用 load_writing_skill 读取完整规范）】
{{ skill_catalog }}
{% endif %}

【工作流原则】
1. 涉及设定集 PDF/Word 资料、前文情节细节或专有名词时，优先调用 `search_knowledge_base`（内置防剧透章节过滤与混合检索）获取原文片段。
2. 涉及人物最新状态、未回收伏笔、人物关系网或历史事件时间线时，优先调用 MCP 设定库工具或 `get_structured_memory` 核实，再动笔撰写。
3. 遇到打斗、伏笔、古风对话或卡文破局等强风格需求，按需调用 `load_writing_skill` 加载对应写作技能包。
4. 信息收集完毕后，直接输出高质量小说正文（或大纲），不要输出多余的客套话或工具调用解释。'
);
