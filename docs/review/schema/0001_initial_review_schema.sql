-- Review Service — initial schema
-- doc_id: review-schema-0001
-- Nguồn chuẩn của schema Review. Giải thích ownership, index và constraint nằm ở
-- docs/review/data.md. Không sửa file này mà không cập nhật data.md tương ứng.
--
-- Yêu cầu: PostgreSQL 14+ với wal_level = logical (phục vụ Debezium).

CREATE SCHEMA IF NOT EXISTS review;
SET search_path TO review, public;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- 1. Enum types
-- ---------------------------------------------------------------------------

CREATE TYPE eligibility_status AS ENUM (
    'PENDING',      -- còn quyền viết đánh giá
    'REVIEWED',     -- đã viết, còn đúng một lượt sửa
    'EDIT_LOCKED',  -- đã dùng hết lượt sửa
    'EXPIRED'       -- hết hạn trước khi viết đánh giá
);

CREATE TYPE review_status AS ENUM (
    'APPROVED',
    'HIDDEN_BY_REPORT',
    'REMOVED'
);

CREATE TYPE report_reason AS ENUM (
    'SPAM',
    'OFFENSIVE',
    'FAKE',
    'OTHER'
);

CREATE TYPE report_status AS ENUM (
    'PENDING',
    'ACTIONED',
    'DISMISSED'
);

CREATE TYPE review_media_type AS ENUM (
    'IMAGE',
    'VIDEO'
);

-- ---------------------------------------------------------------------------
-- 2. review_eligibilities
--    Quyền đánh giá 1-1-1-1 theo (order_id, product_id, sku_id, user_id).
-- ---------------------------------------------------------------------------

CREATE TABLE review_eligibilities (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID           NOT NULL,
    order_id      BIGINT         NOT NULL,
    product_id    VARCHAR(64)    NOT NULL,
    sku_id        VARCHAR(64)    NOT NULL,
    variant_name  VARCHAR(255),
    status        eligibility_status NOT NULL DEFAULT 'PENDING',
    attempt_count SMALLINT       NOT NULL DEFAULT 0,
    expires_at    TIMESTAMPTZ    NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_eligibility_business_key
        UNIQUE (order_id, product_id, sku_id, user_id),
    CONSTRAINT ck_eligibility_attempt_range
        CHECK (attempt_count BETWEEN 0 AND 2),
    CONSTRAINT ck_eligibility_expiry_after_creation
        CHECK (expires_at > created_at),
    -- REVIEW-INV-003: attempt_count phải khớp với state, chặn mọi cặp giá trị vô nghĩa.
    CONSTRAINT ck_eligibility_status_matches_attempts CHECK (
        (status = 'PENDING'     AND attempt_count = 0) OR
        (status = 'EXPIRED'     AND attempt_count = 0) OR
        (status = 'REVIEWED'    AND attempt_count = 1) OR
        (status = 'EDIT_LOCKED' AND attempt_count = 2)
    )
);

-- Danh sách "chờ đánh giá" của một người dùng.
CREATE INDEX ix_eligibility_user_status
    ON review_eligibilities (user_id, status, expires_at);

-- Reconciliation: quét eligibility quá hạn.
CREATE INDEX ix_eligibility_expiry_sweep
    ON review_eligibilities (expires_at)
    WHERE status = 'PENDING';

-- Reconciliation: quét row REVIEWED bị kẹt.
CREATE INDEX ix_eligibility_stuck_reviewed
    ON review_eligibilities (updated_at)
    WHERE status = 'REVIEWED';

-- ---------------------------------------------------------------------------
-- 3. reviews
-- ---------------------------------------------------------------------------

CREATE TABLE reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    eligibility_id   UUID          NOT NULL
        REFERENCES review_eligibilities (id) ON DELETE RESTRICT,
    product_id       VARCHAR(64)   NOT NULL,
    sku_id           VARCHAR(64)   NOT NULL,
    variant_name     VARCHAR(255),
    user_id          UUID          NOT NULL,
    user_name        VARCHAR(100)  NOT NULL,
    rating           SMALLINT      NOT NULL,
    content          TEXT,
    status           review_status NOT NULL DEFAULT 'APPROVED',
    report_count     INTEGER       NOT NULL DEFAULT 0,
    has_media        BOOLEAN       NOT NULL DEFAULT FALSE,
    is_admin_replied BOOLEAN       NOT NULL DEFAULT FALSE,
    is_anonymized    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- REVIEW-INV-002: một eligibility sinh tối đa một review.
    CONSTRAINT uq_review_eligibility UNIQUE (eligibility_id),
    CONSTRAINT ck_review_rating_range CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_review_report_count_non_negative CHECK (report_count >= 0)
);

-- Danh sách đánh giá của một sản phẩm, mới nhất trước.
CREATE INDEX ix_review_product_recent
    ON reviews (product_id, created_at DESC)
    WHERE status = 'APPROVED';

-- Lọc theo biến thể đã mua.
CREATE INDEX ix_review_product_sku_recent
    ON reviews (product_id, sku_id, created_at DESC)
    WHERE status = 'APPROVED';

-- Lọc theo số sao.
CREATE INDEX ix_review_product_rating_recent
    ON reviews (product_id, rating, created_at DESC)
    WHERE status = 'APPROVED';

-- Lọc "chỉ đánh giá có ảnh/video".
CREATE INDEX ix_review_product_with_media
    ON reviews (product_id, created_at DESC)
    WHERE status = 'APPROVED' AND has_media = TRUE;

-- Ẩn danh theo user_id khi nhận USER_DELETED.
CREATE INDEX ix_review_user ON reviews (user_id);

-- Reconciliation aggregate: đếm lại theo product_id.
CREATE INDEX ix_review_aggregate_recount
    ON reviews (product_id, rating)
    WHERE status = 'APPROVED';

-- ---------------------------------------------------------------------------
-- 4. review_media
-- ---------------------------------------------------------------------------

CREATE TABLE review_media (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id     UUID         NOT NULL
        REFERENCES reviews (id) ON DELETE CASCADE,
    media_type    review_media_type NOT NULL,
    object_key    VARCHAR(512) NOT NULL,
    media_url     VARCHAR(512) NOT NULL,
    thumbnail_url VARCHAR(512),
    sort_order    SMALLINT     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_media_review_sort UNIQUE (review_id, sort_order),
    CONSTRAINT ck_media_sort_order_non_negative CHECK (sort_order >= 0)
);

CREATE INDEX ix_media_review ON review_media (review_id, sort_order);

-- ---------------------------------------------------------------------------
-- 5. review_reports
-- ---------------------------------------------------------------------------

CREATE TABLE review_reports (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id     UUID          NOT NULL
        REFERENCES reviews (id) ON DELETE CASCADE,
    reporter_id   UUID          NOT NULL,
    reason        report_reason NOT NULL,
    detail        TEXT,
    status        report_status NOT NULL DEFAULT 'PENDING',
    resolved_by   UUID,
    resolved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- REVIEW-BR-011: một người báo cáo một đánh giá tối đa một lần.
    CONSTRAINT uq_report_review_reporter UNIQUE (review_id, reporter_id),
    -- ACTIONED do hệ thống đặt khi chạm ngưỡng nên không có resolved_by;
    -- DISMISSED luôn là quyết định của một admin cụ thể.
    CONSTRAINT ck_report_resolution_consistent CHECK (
        (status = 'PENDING'   AND resolved_by IS NULL AND resolved_at IS NULL) OR
        (status = 'ACTIONED'  AND resolved_at IS NOT NULL) OR
        (status = 'DISMISSED' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX ix_report_review_status ON review_reports (review_id, status);

-- Hàng đợi kiểm duyệt của admin.
CREATE INDEX ix_report_moderation_queue
    ON review_reports (created_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 6. review_admin_replies
-- ---------------------------------------------------------------------------

CREATE TABLE review_admin_replies (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id  UUID        NOT NULL
        REFERENCES reviews (id) ON DELETE CASCADE,
    admin_id   UUID        NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- REVIEW-BR-015: mỗi đánh giá tối đa một phản hồi đang hoạt động.
    CONSTRAINT uq_admin_reply_review UNIQUE (review_id),
    CONSTRAINT ck_admin_reply_content_not_blank CHECK (length(btrim(content)) > 0)
);

-- ---------------------------------------------------------------------------
-- 7. product_rating_aggregates
--    Materialized view thủ công theo product_id, cập nhật bất đồng bộ.
-- ---------------------------------------------------------------------------

CREATE TABLE product_rating_aggregates (
    product_id       VARCHAR(64) PRIMARY KEY,
    total_1_star     INTEGER     NOT NULL DEFAULT 0,
    total_2_star     INTEGER     NOT NULL DEFAULT 0,
    total_3_star     INTEGER     NOT NULL DEFAULT 0,
    total_4_star     INTEGER     NOT NULL DEFAULT 0,
    total_5_star     INTEGER     NOT NULL DEFAULT 0,
    total_with_media INTEGER     NOT NULL DEFAULT 0,
    total_reviews    INTEGER     NOT NULL DEFAULT 0,
    average_rating   NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    review_revision  BIGINT      NOT NULL DEFAULT 1,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_aggregate_buckets_non_negative CHECK (
        total_1_star >= 0 AND total_2_star >= 0 AND total_3_star >= 0 AND
        total_4_star >= 0 AND total_5_star >= 0 AND
        total_with_media >= 0
    ),
    -- REVIEW-INV-005: total_reviews phải bằng tổng bucket.
    CONSTRAINT ck_aggregate_total_matches_buckets CHECK (
        total_reviews = total_1_star + total_2_star + total_3_star
                      + total_4_star + total_5_star
    ),
    CONSTRAINT ck_aggregate_media_within_total CHECK (total_with_media <= total_reviews),
    CONSTRAINT ck_aggregate_average_range CHECK (average_rating BETWEEN 0 AND 5),
    -- Contract Search yêu cầu review_revision >= 1.
    CONSTRAINT ck_aggregate_revision_positive CHECK (review_revision >= 1)
);

-- Keyset export cho Search dùng khóa chính; không cần index bổ sung.
-- Giám sát độ trễ hội tụ.
CREATE INDEX ix_aggregate_updated_at ON product_rating_aggregates (updated_at);

-- ---------------------------------------------------------------------------
-- 8. review_takedowns — dấu vết kiểm toán cho việc gỡ nội dung theo pháp lý
-- ---------------------------------------------------------------------------

CREATE TABLE review_takedowns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id     UUID         NOT NULL REFERENCES reviews (id) ON DELETE RESTRICT,
    legal_case_id VARCHAR(128) NOT NULL,
    requested_by  UUID         NOT NULL,
    approved_by   UUID         NOT NULL,
    reason        TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_takedown_requires_two_people CHECK (requested_by <> approved_by)
);

CREATE INDEX ix_takedown_review ON review_takedowns (review_id);
CREATE INDEX ix_takedown_case ON review_takedowns (legal_case_id);

-- ---------------------------------------------------------------------------
-- 9. review_outbox — nguồn duy nhất để event rời khỏi service (REVIEW-INV-007)
--    Partition theo tháng để cleanup bằng DROP PARTITION thay vì DELETE.
-- ---------------------------------------------------------------------------

CREATE TABLE review_outbox (
    event_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type     VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    correlation_id VARCHAR(128),
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, created_at),
    CONSTRAINT ck_outbox_aggregate_type CHECK (
        aggregate_type IN ('REVIEW', 'REVIEW_METRIC', 'ADMIN_REPLY')
    )
) PARTITION BY RANGE (created_at);

-- Partition được tạo trước bởi scheduled job; hai partition khởi tạo:
CREATE TABLE review_outbox_y2026m09 PARTITION OF review_outbox
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE review_outbox_y2026m10 PARTITION OF review_outbox
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

-- Giám sát backlog: phát hiện event chưa được connector publish.
CREATE INDEX ix_outbox_created_at ON review_outbox (created_at);

-- ---------------------------------------------------------------------------
-- 10. Publication cho Debezium
-- ---------------------------------------------------------------------------

-- Chỉ outbox được replicate; bảng domain không đi qua CDC.
CREATE PUBLICATION review_outbox_publication FOR TABLE review_outbox
    WITH (publish_via_partition_root = true);
