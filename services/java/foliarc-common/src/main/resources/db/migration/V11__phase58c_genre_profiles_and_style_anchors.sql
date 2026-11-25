ALTER TABLE books
    ADD COLUMN IF NOT EXISTS genre_code VARCHAR(32) NOT NULL DEFAULT 'XIANXIA_FANTASY',
    ADD COLUMN IF NOT EXISTS sub_genre_tags VARCHAR(255) NOT NULL DEFAULT '';

UPDATE books
SET genre_code = CASE
    WHEN genre ~ '恐怖|灵异|怪谈|克苏鲁|惊悚' THEN 'HORROR_SUPERNATURAL'
    WHEN genre ~ '古典|历史|世情|志怪|武侠|权谋|明清' THEN 'CLASSICAL_HISTORICAL'
    WHEN genre ~ '悬疑|推理|刑侦|侦探|探案' THEN 'MYSTERY_THRILLER'
    WHEN genre ~ '科幻|赛博|末世|星际|废土' THEN 'SCIFI_APOCALYPSE'
    WHEN genre ~ '都市|现实|职场|情感|商战' THEN 'URBAN_REALISM'
    ELSE genre_code
END
WHERE genre_code = 'XIANXIA_FANTASY' AND genre IS NOT NULL;

ALTER TABLE characters
    ADD COLUMN IF NOT EXISTS custom_attributes JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE characters AS c
SET custom_attributes = jsonb_build_object('realm', c.realm_or_level)
FROM books AS b
WHERE b.id = c.book_id AND b.genre_code = 'XIANXIA_FANTASY'
    AND c.realm_or_level <> '' AND c.custom_attributes = '{}'::jsonb;

CREATE TABLE IF NOT EXISTS style_anchors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL DEFAULT '',
    excerpt_text TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_style_anchors_book_order
    ON style_anchors (book_id, sort_order, created_at);

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS genre_codes VARCHAR(255) NOT NULL DEFAULT '';
