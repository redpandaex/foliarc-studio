-- Phase 5.5 (+ VIP Entitlements Center):
-- Four-tier dynamic membership plans (FREE / VIP / SVIP / STUDIO),
-- user subscriptions with auto-expiry fallback, and idempotent order ledger.

-- 1. VIP plan catalog & dynamic entitlements configuration (hot-updatable via admin API)
CREATE TABLE IF NOT EXISTS vip_plans (
    plan_code            VARCHAR(24)  PRIMARY KEY,
    display_name         VARCHAR(64)  NOT NULL,
    monthly_price_cents  INT          NOT NULL DEFAULT 0,
    monthly_grant_micros BIGINT       NOT NULL DEFAULT 20000000,
    credit_discount_bps  INT          NOT NULL DEFAULT 10000,
    entitlements_json    JSONB        NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order           INT          NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 2. Active user subscription (missing or expired automatically falls back to FREE)
CREATE TABLE IF NOT EXISTS user_subscriptions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    plan_code     VARCHAR(24)  NOT NULL REFERENCES vip_plans(plan_code),
    status        VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ,
    auto_renew    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_order_id VARCHAR(128),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_subs_expires
    ON user_subscriptions (status, expires_at)
    WHERE expires_at IS NOT NULL;

-- 3. Idempotent VIP order ledger (CREDITS / ADMIN_GRANT / external payment providers)
CREATE TABLE IF NOT EXISTS vip_orders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_code       VARCHAR(24)  NOT NULL REFERENCES vip_plans(plan_code),
    duration_days   INT          NOT NULL DEFAULT 30,
    amount_cents    INT          NOT NULL DEFAULT 0,
    payment_channel VARCHAR(32)  NOT NULL DEFAULT 'CREDITS',
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PAID',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_vip_orders_user_created
    ON vip_orders (user_id, created_at DESC);

-- 4. Seed the 4 standard tiers matching docs/architecture/05-VIP-ENTITLEMENTS-SYSTEM.md
INSERT INTO vip_plans (
    plan_code, display_name, monthly_price_cents, monthly_grant_micros,
    credit_discount_bps, entitlements_json, enabled, sort_order
) VALUES
(
    'FREE',
    '免费版',
    0,
    20000000,
    10000,
    '{
      "rippleMaxRangeChapters": 50,
      "rippleAllowFullBook": false,
      "rippleMaxCandidates": 20,
      "rippleMaxPatchChapters": 5,
      "rippleDailyScanLimit": 3,
      "maxMemoryChapters": 100,
      "maxSectionChars": 20000,
      "maxStyleAnchors": 0,
      "maxForeshadowings": 20,
      "userLibraryMaxDocs": 5,
      "userLibraryMaxTotalBytes": 10485760,
      "rpmLimit": 10,
      "tpmLimit": 40000,
      "priorityQueue": false
    }'::jsonb,
    TRUE,
    1
),
(
    'VIP',
    '进阶作者 VIP',
    2900,
    200000000,
    10000,
    '{
      "rippleMaxRangeChapters": 300,
      "rippleAllowFullBook": false,
      "rippleMaxCandidates": 50,
      "rippleMaxPatchChapters": 20,
      "rippleDailyScanLimit": 20,
      "maxMemoryChapters": 1000,
      "maxSectionChars": 50000,
      "maxStyleAnchors": 1,
      "maxForeshadowings": 0,
      "userLibraryMaxDocs": 30,
      "userLibraryMaxTotalBytes": 62914560,
      "rpmLimit": 30,
      "tpmLimit": 150000,
      "priorityQueue": false
    }'::jsonb,
    TRUE,
    2
),
(
    'SVIP',
    '长篇大神 SVIP',
    6900,
    600000000,
    9000,
    '{
      "rippleMaxRangeChapters": 1000,
      "rippleAllowFullBook": false,
      "rippleMaxCandidates": 100,
      "rippleMaxPatchChapters": 50,
      "rippleDailyScanLimit": 60,
      "maxMemoryChapters": 10000,
      "maxSectionChars": 100000,
      "maxStyleAnchors": 3,
      "maxForeshadowings": 0,
      "userLibraryMaxDocs": 100,
      "userLibraryMaxTotalBytes": 314572800,
      "rpmLimit": 60,
      "tpmLimit": 400000,
      "priorityQueue": true
    }'::jsonb,
    TRUE,
    3
),
(
    'STUDIO',
    '工作室万章版',
    19900,
    2000000000,
    8000,
    '{
      "rippleMaxRangeChapters": 2000,
      "rippleAllowFullBook": true,
      "rippleMaxCandidates": 150,
      "rippleMaxPatchChapters": 100,
      "rippleDailyScanLimit": 0,
      "maxMemoryChapters": 50000,
      "maxSectionChars": 100000,
      "maxStyleAnchors": 5,
      "maxForeshadowings": 0,
      "userLibraryMaxDocs": 500,
      "userLibraryMaxTotalBytes": 2147483648,
      "rpmLimit": 120,
      "tpmLimit": 1000000,
      "priorityQueue": true
    }'::jsonb,
    TRUE,
    4
)
ON CONFLICT (plan_code) DO NOTHING;
