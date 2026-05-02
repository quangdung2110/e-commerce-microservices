-- ============================================================
-- Promotion Service - Initial Schema
-- Theo thiết kế promotion_db_design.md (100% compliance)
-- ============================================================

-- ============================================================
-- 1.1. campaigns (Chiến dịch Khuyến mãi)
-- Theo thiết kế promotion_db_design.md - Section 1.1
-- ============================================================
CREATE TABLE campaigns (
    id            BIGSERIAL       PRIMARY KEY,
    name          VARCHAR(255)    NOT NULL,
    start_time    TIMESTAMPTZ     NOT NULL,
    end_time      TIMESTAMPTZ     NOT NULL,
    status        VARCHAR(50)     NOT NULL DEFAULT 'UPCOMING'
                                  CHECK (status IN ('UPCOMING', 'ACTIVE', 'ENDED', 'SUSPENDED')),
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_campaigns_status ON campaigns(status);
CREATE INDEX idx_campaigns_time ON campaigns(start_time, end_time);

-- ============================================================
-- 1.2. promotions (Mã giảm giá Khuyến mãi - Cart Vouchers)
-- Theo thiết kế promotion_db_design.md - Section 1.2
-- ============================================================
CREATE TABLE promotions (
    id                   BIGSERIAL       PRIMARY KEY,
    campaign_id          BIGINT          NULL REFERENCES campaigns(id),
    owner_type           VARCHAR(255)    NOT NULL CHECK (owner_type IN ('PLATFORM', 'SELLER')),
    owner_id             BIGINT          NULL,
    code                 VARCHAR(50)     NOT NULL,
    type                 VARCHAR(50)     NOT NULL
                                         CHECK (type IN ('UNLIMITED_ALL', 'LIMITED_FLASH', 'SELLER_LIMITED')),
    discount_type        VARCHAR(50)     NOT NULL
                                         CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT', 'FREE_SHIPPING')),
    discount_value       DECIMAL(10, 2)  NOT NULL,
    max_discount_amount  DECIMAL(10, 2)  NULL,
    min_purchase_amount  DECIMAL(10, 2)  NULL,
    conditions_rule      JSONB           NULL,
    priority             INT             NOT NULL DEFAULT 0,
    start_time           TIMESTAMPTZ     NOT NULL,
    end_time             TIMESTAMPTZ     NOT NULL,
    status               VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE'
                                         CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_promotions_campaign ON promotions(campaign_id);
CREATE INDEX idx_promotions_code ON promotions(code);
CREATE INDEX idx_promotions_status_time ON promotions(status, start_time, end_time);
CREATE INDEX idx_promotions_owner ON promotions(owner_type, owner_id);
CREATE INDEX idx_promotions_min_purchase ON promotions(min_purchase_amount) WHERE status = 'ACTIVE';

-- ============================================================
-- 1.3. promotion_budgets (Ngân sách & Tồn kho)
-- Theo thiết kế promotion_db_design.md - Section 1.3
-- Tách bảng ngân sách để tránh Row Lock Contention
-- ============================================================
CREATE TABLE promotion_budgets (
    promotion_id    BIGINT          PRIMARY KEY REFERENCES promotions(id),
    total_quantity  INT             NOT NULL DEFAULT 0,
    used_quantity   INT             NOT NULL DEFAULT 0,
    total_budget    DECIMAL(15, 2)  NOT NULL DEFAULT 0,
    used_budget     DECIMAL(15, 2)  NOT NULL DEFAULT 0
);

-- ============================================================
-- 1.4. coupon_claims (Lịch sử Tranh mã - Flash Sale SPOP)
-- Theo thiết kế promotion_db_design.md - Section 1.4
-- Batch-insert 1000 records status=AVAILABLE khi admin tạo flash sale
-- ============================================================
CREATE TABLE coupon_claims (
    id              BIGSERIAL       PRIMARY KEY,
    promotion_id    BIGINT          NOT NULL REFERENCES promotions(id),
    owner_id        BIGINT          NULL,
    status          VARCHAR(50)     NOT NULL DEFAULT 'AVAILABLE'
                                    CHECK (status IN ('AVAILABLE', 'CLAIMED')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Theo thiết kế: Partial Unique Index — 1 user chỉ claim 1 suất per promotion
CREATE UNIQUE INDEX uq_coupon_claims_promotion_owner
    ON coupon_claims(promotion_id, owner_id)
    WHERE owner_id IS NOT NULL;

CREATE INDEX idx_coupon_claims_promotion_status ON coupon_claims(promotion_id, status);

-- ============================================================
-- 1.5. user_coupon_wallets (Ví Voucher của User)
-- Theo thiết kế promotion_db_design.md - Section 1.5
-- ============================================================
CREATE TABLE user_coupon_wallets (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    promotion_id    BIGINT          NOT NULL REFERENCES promotions(id),
    status          VARCHAR(50)     NOT NULL DEFAULT 'SAVED'
                                    CHECK (status IN ('SAVED', 'USED', 'EXPIRED')),
    saved_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    used_at         TIMESTAMPTZ     NULL,
    expired_at      TIMESTAMPTZ     NULL
);

-- Theo thiết kế: Unique Constraint
CREATE UNIQUE INDEX uq_wallet_user_promotion ON user_coupon_wallets(user_id, promotion_id);
-- Theo thiết kế: Composite Index cho "GET /wallet?status=SAVED"
CREATE INDEX idx_wallet_user_status ON user_coupon_wallets(user_id, status);

-- ============================================================
-- 1.6. coupon_usages (Sổ cái Phân tán - Ledger & Audit Trail)
-- Theo thiết kế promotion_db_design.md - Section 1.6
-- ============================================================
CREATE TABLE coupon_usages (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             BIGINT          NOT NULL,
    promotion_id        BIGINT          NOT NULL REFERENCES promotions(id),
    order_id            BIGINT          NOT NULL,
    discount_amount     DECIMAL(10, 2)  NOT NULL,
    platform_funding    DECIMAL(10, 2)  NOT NULL DEFAULT 0,
    seller_funding      DECIMAL(10, 2)  NOT NULL DEFAULT 0,
    status              VARCHAR(50)     NOT NULL DEFAULT 'RESERVED'
                                        CHECK (status IN ('RESERVED', 'REDEEMED', 'CANCELLED', 'REFUNDED')),
    reserved_at         TIMESTAMPTZ     NULL,
    redeemed_at         TIMESTAMPTZ     NULL,
    cancelled_at        TIMESTAMPTZ     NULL,
    refunded_at         TIMESTAMPTZ     NULL
);

-- Theo thiết kế: Unique Constraint (order_id, promotion_id)
CREATE UNIQUE INDEX uq_usage_order_promotion ON coupon_usages(order_id, promotion_id);
CREATE INDEX idx_usages_user ON coupon_usages(user_id);
CREATE INDEX idx_usages_order ON coupon_usages(order_id);
CREATE INDEX idx_usages_status_reserved ON coupon_usages(status, reserved_at) WHERE status = 'RESERVED';

-- ============================================================
-- 1.7. promotion_event_idempotency (Chống lặp Message Kafka)
-- Theo thiết kế promotion_db_design.md - Section 1.7
-- ============================================================
CREATE TABLE promotion_event_idempotency (
    event_id        VARCHAR(255)    PRIMARY KEY,
    order_id        BIGINT          NOT NULL,
    action_type     VARCHAR(50)     NOT NULL
                                    CHECK (action_type IN ('REDEEM_COUPON', 'CANCEL_COUPON', 'REFUND_COUPON')),
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_idempotency_order ON promotion_event_idempotency(order_id);
CREATE INDEX idx_idempotency_processed ON promotion_event_idempotency(processed_at);

-- ============================================================
-- 1.8. promotion_outbox (Outbox Pattern — Reliable Event Publishing)
-- Theo thiết kế promotion_db_design.md - Section 1.8
-- Theo thiết kế promotion_outbox_pattern.md
-- ============================================================
CREATE TABLE promotion_outbox (
    event_id        UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)     NOT NULL,
    aggregate_id    VARCHAR(255)    NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD')),
    retry_count     INT             NOT NULL DEFAULT 0,
    max_retries     INT             NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMPTZ     NULL,
    error_message   TEXT            NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Theo thiết kế promotion_outbox_pattern.md - Section 4.1: Polling query
CREATE INDEX idx_outbox_pending ON promotion_outbox(status, next_retry_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_outbox_gc ON promotion_outbox(status, created_at)
    WHERE status IN ('PUBLISHED', 'DEAD');

-- ============================================================
-- 2.1. markdown_rules (Luật Giảm giá Gạch chéo)
-- Theo thiết kế promotion_db_design.md - Section 2.1
-- ============================================================
CREATE TABLE markdown_rules (
    id                   BIGSERIAL       PRIMARY KEY,
    campaign_id          BIGINT          NULL REFERENCES campaigns(id),
    owner_id             BIGINT          NULL,
    owner_type           VARCHAR(50)     NOT NULL CHECK (owner_type IN ('PLATFORM', 'SELLER')),
    name                 VARCHAR(255)    NOT NULL,
    scope_type           VARCHAR(50)     NOT NULL CHECK (scope_type IN ('PRODUCT', 'CATEGORY', 'BRAND', 'ALL')),
    ref_id               BIGINT          NULL,
    discount_type        VARCHAR(50)     NOT NULL CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    discount_value       DECIMAL(10, 2)  NOT NULL,
    max_discount_amount  DECIMAL(10, 2)  NULL,
    start_time           TIMESTAMPTZ     NOT NULL,
    end_time             TIMESTAMPTZ     NOT NULL,
    status               VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE'
                                         CHECK (status IN ('ACTIVE', 'INACTIVE', 'ENDED')),
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_markdown_status ON markdown_rules(status);
CREATE INDEX idx_markdown_scope ON markdown_rules(scope_type, ref_id);
CREATE INDEX idx_markdown_campaign ON markdown_rules(campaign_id);
CREATE INDEX idx_markdown_active_end ON markdown_rules(status, end_time) WHERE status = 'ACTIVE';
