-- Promotion Service baseline schema (docs/promotion/data.md §3–§4).
-- PostgreSQL là source of truth; Redis chỉ là runtime projection có thể rebuild.
-- Không có coupon_usages hoặc bảng event-idempotency tổng quát (ADR-013, ADR-016).

-- ---------------------------------------------------------------- campaigns
CREATE TABLE campaigns (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(80)  NOT NULL,
    name               VARCHAR(255) NOT NULL,
    description        TEXT         NULL,
    publication_status VARCHAR(16)  NOT NULL,
    start_time         TIMESTAMPTZ  NOT NULL,
    end_time           TIMESTAMPTZ  NOT NULL,
    -- Optimistic concurrency / cache / schedule fence, không phải definition version.
    revision           BIGINT       NOT NULL DEFAULT 1,
    created_by         VARCHAR(128) NOT NULL,
    updated_by         VARCHAR(128) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_campaigns_code UNIQUE (code),
    CONSTRAINT chk_campaigns_status CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'PAUSED', 'CANCELLED')),
    CONSTRAINT chk_campaigns_window CHECK (start_time < end_time),
    CONSTRAINT chk_campaigns_revision CHECK (revision >= 1)
);
-- Fallback scan / rebuild schedule.
CREATE INDEX idx_campaigns_status_window ON campaigns (publication_status, start_time, end_time);

-- --------------------------------------------------------------- promotions
CREATE TABLE promotions (
    id                   BIGSERIAL PRIMARY KEY,
    campaign_id          BIGINT       NOT NULL REFERENCES campaigns (id),
    code                 VARCHAR(80)  NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    description          TEXT         NULL,
    distribution_mode    VARCHAR(16)  NOT NULL,
    discount_type        VARCHAR(16)  NOT NULL,
    -- basis points (PERCENTAGE) hoặc VND (FIXED_VND)
    discount_value       BIGINT       NOT NULL,
    max_discount_vnd     BIGINT       NULL,
    min_order_amount_vnd BIGINT       NOT NULL DEFAULT 0,
    conditions_rule      JSONB        NOT NULL,
    rule_schema_version  SMALLINT     NOT NULL,
    validity_mode        VARCHAR(24)  NOT NULL,
    valid_for_seconds    BIGINT       NULL,
    redeem_until         TIMESTAMPTZ  NULL,
    is_enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_promotions_code UNIQUE (code),
    CONSTRAINT chk_promotions_distribution CHECK (distribution_mode IN ('PUBLIC_SAVE', 'LIMITED_CLAIM', 'DIRECT_GRANT')),
    CONSTRAINT chk_promotions_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED_VND')),
    CONSTRAINT chk_promotions_discount_value CHECK (
        (discount_type = 'PERCENTAGE' AND discount_value BETWEEN 1 AND 10000)
        OR (discount_type = 'FIXED_VND' AND discount_value >= 1 AND max_discount_vnd IS NULL)
    ),
    CONSTRAINT chk_promotions_max_discount CHECK (max_discount_vnd IS NULL OR max_discount_vnd >= 1),
    CONSTRAINT chk_promotions_min_order CHECK (min_order_amount_vnd >= 0),
    CONSTRAINT chk_promotions_rule_object CHECK (jsonb_typeof(conditions_rule) = 'object'),
    CONSTRAINT chk_promotions_rule_schema CHECK (rule_schema_version >= 1),
    -- Validity mode chỉ đi với tham số tương ứng; không có mặc định ngầm.
    CONSTRAINT chk_promotions_validity CHECK (
        (validity_mode = 'CAMPAIGN_END' AND valid_for_seconds IS NULL AND redeem_until IS NULL)
        OR (validity_mode = 'DURATION_AFTER_SAVE' AND valid_for_seconds >= 1 AND redeem_until IS NULL)
        OR (validity_mode = 'FIXED_DATE' AND valid_for_seconds IS NULL AND redeem_until IS NOT NULL)
    )
);
CREATE INDEX idx_promotions_campaign_enabled ON promotions (campaign_id, is_enabled);

-- --------------------------------------------------------- promotion_budgets
CREATE TABLE promotion_budgets (
    promotion_id              BIGINT      PRIMARY KEY REFERENCES promotions (id),
    total_claim_quantity      BIGINT      NULL,
    claimed_quantity          BIGINT      NOT NULL DEFAULT 0,
    total_redemption_quantity BIGINT      NULL,
    used_quantity             BIGINT      NOT NULL DEFAULT 0,
    total_budget_vnd          BIGINT      NULL,
    used_budget_vnd           BIGINT      NOT NULL DEFAULT 0,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_budget_totals CHECK (
        (total_claim_quantity IS NULL OR total_claim_quantity >= 1)
        AND (total_redemption_quantity IS NULL OR total_redemption_quantity >= 1)
        AND (total_budget_vnd IS NULL OR total_budget_vnd >= 1)
    ),
    CONSTRAINT chk_budget_counters CHECK (
        claimed_quantity >= 0 AND used_quantity >= 0 AND used_budget_vnd >= 0
        AND (total_claim_quantity IS NULL OR claimed_quantity <= total_claim_quantity)
        AND (total_redemption_quantity IS NULL OR used_quantity <= total_redemption_quantity)
        AND (total_budget_vnd IS NULL OR used_budget_vnd <= total_budget_vnd)
    )
);

-- ------------------------------------------------------------ coupon_claims
CREATE TABLE coupon_claims (
    id           BIGSERIAL PRIMARY KEY,
    promotion_id BIGINT      NOT NULL REFERENCES promotions (id),
    status       VARCHAR(16) NOT NULL,
    owner_id     UUID        NULL,
    claimed_at   TIMESTAMPTZ NULL,
    -- Campaign revision tại acquisition (claim-gate Lua); replay cho ClaimVoucherResponse.
    claimed_campaign_revision BIGINT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_claims_status CHECK (status IN ('AVAILABLE', 'CLAIMED')),
    CONSTRAINT chk_claims_owner CHECK (
        (status = 'AVAILABLE' AND owner_id IS NULL AND claimed_at IS NULL AND claimed_campaign_revision IS NULL)
        OR (status = 'CLAIMED' AND owner_id IS NOT NULL AND claimed_at IS NOT NULL AND claimed_campaign_revision IS NOT NULL)
    )
);
CREATE INDEX idx_claims_promotion_status_id ON coupon_claims (promotion_id, status, id);
CREATE UNIQUE INDEX uq_claims_promotion_owner ON coupon_claims (promotion_id, owner_id) WHERE owner_id IS NOT NULL;

-- ------------------------------------------------------ user_coupon_wallets
CREATE TABLE user_coupon_wallets (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             UUID        NOT NULL,
    promotion_id        BIGINT      NOT NULL REFERENCES promotions (id),
    coupon_claim_id     BIGINT      NULL REFERENCES coupon_claims (id),
    status              VARCHAR(16) NOT NULL,
    saved_at            TIMESTAMPTZ NOT NULL,
    expired_at          TIMESTAMPTZ NOT NULL,
    used_order_id       BIGINT      NULL,
    discount_amount_vnd BIGINT      NULL,
    used_at             TIMESTAMPTZ NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wallets_user_promotion UNIQUE (user_id, promotion_id),
    CONSTRAINT uq_wallets_claim UNIQUE (coupon_claim_id),
    CONSTRAINT chk_wallets_status CHECK (status IN ('SAVED', 'USED', 'EXPIRED')),
    CONSTRAINT chk_wallets_usage CHECK (
        (status = 'USED' AND used_order_id IS NOT NULL AND discount_amount_vnd IS NOT NULL AND used_at IS NOT NULL)
        OR (status IN ('SAVED', 'EXPIRED') AND used_order_id IS NULL AND discount_amount_vnd IS NULL AND used_at IS NULL)
    ),
    CONSTRAINT chk_wallets_amount CHECK (discount_amount_vnd IS NULL OR discount_amount_vnd >= 0)
);
CREATE INDEX idx_wallets_user_status_expiry ON user_coupon_wallets (user_id, status, expired_at);
CREATE INDEX idx_wallets_promotion_status ON user_coupon_wallets (promotion_id, status);
-- Một platform voucher cho một order (PROMO-INV-001).
CREATE UNIQUE INDEX uq_wallets_used_order ON user_coupon_wallets (used_order_id) WHERE used_order_id IS NOT NULL;

-- ----------------------------------------------------------- markdown_rules
CREATE TABLE markdown_rules (
    id                      BIGSERIAL PRIMARY KEY,
    campaign_id             BIGINT      NOT NULL REFERENCES campaigns (id),
    product_id              UUID        NOT NULL,
    discount_type           VARCHAR(16) NOT NULL,
    discount_value          BIGINT      NOT NULL,
    max_discount_vnd        BIGINT      NULL,
    priority                INT         NOT NULL DEFAULT 0,
    is_enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Operational invalidation overlay do Product tombstone; không sửa definition.
    invalidated_at          TIMESTAMPTZ NULL,
    invalidation_reason     VARCHAR(32) NULL,
    source_product_revision BIGINT      NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_markdown_campaign_product UNIQUE (campaign_id, product_id),
    CONSTRAINT chk_markdown_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED_VND')),
    CONSTRAINT chk_markdown_discount_value CHECK (
        (discount_type = 'PERCENTAGE' AND discount_value BETWEEN 1 AND 10000)
        OR (discount_type = 'FIXED_VND' AND discount_value >= 1 AND max_discount_vnd IS NULL)
    ),
    CONSTRAINT chk_markdown_max_discount CHECK (max_discount_vnd IS NULL OR max_discount_vnd >= 1),
    CONSTRAINT chk_markdown_source_revision CHECK (source_product_revision IS NULL OR source_product_revision > 0),
    CONSTRAINT chk_markdown_invalidation CHECK (
        (invalidated_at IS NULL AND invalidation_reason IS NULL AND source_product_revision IS NULL)
        OR (invalidated_at IS NOT NULL AND invalidation_reason = 'PRODUCT_DELETED' AND source_product_revision IS NOT NULL)
    )
);
CREATE INDEX idx_markdown_product_enabled ON markdown_rules (product_id, is_enabled);
CREATE INDEX idx_markdown_product_source_revision ON markdown_rules (product_id, source_product_revision)
    WHERE source_product_revision IS NOT NULL;

-- ------------------------------------------------ catalog_product_tombstones
CREATE TABLE catalog_product_tombstones (
    product_id              UUID        PRIMARY KEY,
    source_product_revision BIGINT      NOT NULL CHECK (source_product_revision > 0),
    product_deleted_at      TIMESTAMPTZ NOT NULL,
    received_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ------------------------------------------------ promotion_command_receipts
CREATE TABLE promotion_command_receipts (
    id                      UUID         PRIMARY KEY,
    operation_type          VARCHAR(24)  NOT NULL,
    idempotency_key         VARCHAR(255) NOT NULL,
    promotion_id            BIGINT       NOT NULL REFERENCES promotions (id),
    order_id                BIGINT       NOT NULL,
    wallet_id               BIGINT       NOT NULL REFERENCES user_coupon_wallets (id),
    request_hash            CHAR(64)     NOT NULL,
    amount_vnd              BIGINT       NOT NULL CHECK (amount_vnd >= 0),
    result_code             VARCHAR(64)  NOT NULL,
    resulting_wallet_status VARCHAR(16)  NOT NULL,
    response_payload        JSONB        NOT NULL,
    completed_at            TIMESTAMPTZ  NOT NULL,
    -- NULL khi commit; set sau khi Redis finalize/repair đã hội tụ (ADR-016).
    redis_finalized_at      TIMESTAMPTZ  NULL,
    retain_until            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_receipts_key UNIQUE (idempotency_key),
    CONSTRAINT uq_receipts_business UNIQUE (operation_type, promotion_id, order_id),
    CONSTRAINT chk_receipts_operation CHECK (operation_type IN ('CONFIRM', 'CANCEL_CONFIRMED')),
    CONSTRAINT chk_receipts_wallet_status CHECK (resulting_wallet_status IN ('USED', 'SAVED', 'EXPIRED'))
);
CREATE INDEX idx_receipts_unfinalized ON promotion_command_receipts (completed_at) WHERE redis_finalized_at IS NULL;
CREATE INDEX idx_receipts_retain_until ON promotion_command_receipts (retain_until);

-- --------------------------------------------------- promotion_outbox_events
-- Append-only; Debezium/Kafka Connect sở hữu relay/retry. Ở v1 chỉ chứa
-- com.ecommerce.promotion.product-markdown-snapshot.v1.
CREATE TABLE promotion_outbox_events (
    event_id        UUID         PRIMARY KEY,
    event_type      VARCHAR(120) NOT NULL,
    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    partition_key   VARCHAR(64)  NOT NULL,
    source_revision BIGINT       NULL,
    dedup_key       VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_outbox_dedup UNIQUE (dedup_key)
);
CREATE INDEX idx_outbox_created_at ON promotion_outbox_events (created_at);

-- Monotonic ordering guard cho markdown snapshot theo product (PROMO-INV-010).
CREATE SEQUENCE markdown_source_revision_seq AS BIGINT START WITH 1 INCREMENT BY 1;
