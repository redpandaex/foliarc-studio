-- Phase 2: Novel Lore Bible (Characters, Worldbook, Foreshadowings), Prompt Registry & Agent Run Mode

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS mode VARCHAR(16) NOT NULL DEFAULT 'AGENT';

CREATE TABLE characters (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id          UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    name             VARCHAR(100) NOT NULL,
    role_type        VARCHAR(32)  NOT NULL DEFAULT 'SUPPORTING',
    personality      TEXT         NOT NULL DEFAULT '',
    realm_or_level   VARCHAR(120) NOT NULL DEFAULT '',
    current_location VARCHAR(160) NOT NULL DEFAULT '',
    status_notes     TEXT         NOT NULL DEFAULT '',
    inventory        TEXT         NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_characters_book_name UNIQUE (book_id, name)
);

CREATE INDEX idx_characters_book_id ON characters (book_id);

CREATE TABLE world_entries (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id    UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    category   VARCHAR(32)  NOT NULL DEFAULT 'WORLD',
    title      VARCHAR(160) NOT NULL,
    content    TEXT         NOT NULL DEFAULT '',
    tags       VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_world_entries_book_id ON world_entries (book_id);

CREATE TABLE foreshadowings (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id             UUID         NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    title               VARCHAR(160) NOT NULL,
    description         TEXT         NOT NULL DEFAULT '',
    planted_chapter_no  INT,
    resolved_chapter_no INT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_foreshadowings_book_status ON foreshadowings (book_id, status);

CREATE TABLE prompt_templates (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL,
    version       INT          NOT NULL,
    stage         VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    template_body TEXT         NOT NULL,
    description   VARCHAR(255) NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_prompt_templates_name_version UNIQUE (name, version)
);

INSERT INTO prompt_templates (name, version, stage, description, template_body)
VALUES (
    'novel_writer_system',
    1,
    'ACTIVE',
    'Phase 2 静态前缀模板（KV Cache 友好：角色规则 + 渐进式 Skill 目录 + 书目设定固定在前缀）',
    '你是 FoliarcStudio（卷弧工坊）的企业级长篇小说创作 Agent。
你必须严格维护人物性格、战力境界、物品持有与世界观设定的一致性，杜绝“吃书”与人设崩塌。

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
1. 涉及具体人物状态、世界观名词、未回收伏笔或前文细节时，优先调用可用工具（含 MCP 设定库工具）核实，再动笔撰写。
2. 遇到打斗、伏笔、古风对话或卡文破局等强风格需求，按需调用 load_writing_skill 加载对应写作技能包。
3. 信息收集完毕后，直接输出高质量小说正文（或大纲），不要输出多余的客套话或工具调用解释。'
);
