-- =============================================================================
-- Cart Service — initial cold store schema
-- =============================================================================
-- Vai trò: PostgreSQL là bản chiếu bền vững của cart state, KHÔNG phải nguồn
-- chân lý. Nguồn chân lý là Redis; xem docs/cart/decisions/ADR-001.
--
-- Hệ quả thiết kế:
--   * Không có foreign key thật giữa cart_items và carts, nên CASCADE DELETE
--     không tồn tại. Xóa giỏ phải xóa cart_items trước, carts sau, cùng một
--     transaction.
--   * Mọi câu lệnh ghi từ consumer đều phải kèm guard so sánh stream_id.
--   * Mốc thời gian nghiệp vụ (added_at, updated_at trong snapshot) là epoch
--     milliseconds dạng chuỗi, để nhất quán với Redis hash value.
--
-- Tài liệu liên quan: docs/cart/data.md
-- =============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS cart;

SET search_path TO cart, public;

-- -----------------------------------------------------------------------------
-- carts — dòng master theo vòng đời của một giỏ
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS carts (
    -- Bằng đúng Redis key: '{cart}:' || user_id. Không parse ngược ra user_id.
    cart_id                      VARCHAR(255) PRIMARY KEY,

    -- Lấy trực tiếp từ event payload, không tách chuỗi từ cart_id.
    user_id                      VARCHAR(255) NOT NULL,

    status                       VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',

    -- Redis Stream Entry ID mới nhất đã áp dụng cho TRẠNG THÁI giỏ.
    -- Guard chống event đến sai thứ tự. NULL nghĩa là chưa từng áp dụng event nào.
    last_event_stream_id         VARCHAR(64),

    -- Cronjob đánh dấu bỏ quên ghi mốc này sau khi XADD thành công.
    -- NULL trên một dòng ABANDONED nghĩa là event chưa được publish -> publish bù.
    abandoned_event_published_at TIMESTAMPTZ,

    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_carts_status CHECK (
        status IN ('ACTIVE', 'ABANDONED', 'DELETED', 'CHECKED_OUT')
    )
);

COMMENT ON TABLE  carts IS
    'Bản chiếu vòng đời giỏ hàng. Nguồn chân lý là Redis.';
COMMENT ON COLUMN carts.cart_id IS
    'Bằng đúng Redis key {cart}:{user_id}. Đổi format key là breaking change.';
COMMENT ON COLUMN carts.last_event_stream_id IS
    'Guard thứ tự: chỉ cập nhật trạng thái khi stream_id của event lớn hơn giá trị này.';
COMMENT ON COLUMN carts.abandoned_event_published_at IS
    'NULL trên dòng ABANDONED nghĩa là CART_ABANDONED chưa publish; cronjob publish bù.';

-- Tra cứu vận hành và phân tích theo người dùng.
CREATE INDEX IF NOT EXISTS idx_carts_user_id
    ON carts (user_id);

-- Cronjob quét giỏ bỏ quên và giỏ quá hạn dọn dẹp.
CREATE INDEX IF NOT EXISTS idx_carts_status_updated_at
    ON carts (status, updated_at);

-- Bước reconcile của cronjob đánh dấu bỏ quên: index bộ phận, rất nhỏ.
CREATE INDEX IF NOT EXISTS idx_carts_abandoned_unpublished
    ON carts (updated_at)
    WHERE status = 'ABANDONED' AND abandoned_event_published_at IS NULL;

-- -----------------------------------------------------------------------------
-- cart_items — một dòng cho mỗi SKU trong một giỏ
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cart_items (
    -- Định dạng cố định: cart_id || ':' || sku_id.
    -- Dùng khóa ghép sẵn để upsert và batch delete chỉ cần một cột.
    item_id           VARCHAR(255) PRIMARY KEY,

    -- Tham chiếu ở tầng ứng dụng tới carts.cart_id. KHÔNG có FK constraint.
    cart_id           VARCHAR(255) NOT NULL,

    -- Lặp lại từ event payload để truy vấn phân tích không phải join.
    user_id           VARCHAR(255) NOT NULL,

    product_id        VARCHAR(255) NOT NULL,
    sku_id            VARCHAR(255) NOT NULL,

    quantity          INTEGER      NOT NULL,

    -- Giá tại thời điểm thêm vào giỏ. Cột riêng chứ không nằm trong snapshot vì
    -- cần cho truy vấn phân tích và cho so sánh chênh lệch giá. Bất biến.
    unit_price_at_add NUMERIC(15, 2) NOT NULL DEFAULT 0.00,

    -- Snapshot hiển thị: product_id, product_name, variant_info, image_url,
    -- is_available, added_at, updated_at.
    -- KHÔNG chứa: unit_price_at_add (cột riêng), current_price (runtime),
    --             cart_id / sku_id / user_id (đã có cột riêng).
    product_snapshot  JSONB        NOT NULL,

    -- Redis Stream Entry ID mới nhất đã áp dụng cho DÒNG này. Guard thứ tự.
    redis_stream_id   VARCHAR(64),

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_cart_items_quantity_positive CHECK (quantity > 0)
);

COMMENT ON TABLE  cart_items IS
    'Bản chiếu các item trong giỏ. Không có FK tới carts; xóa phải làm thủ công theo thứ tự.';
COMMENT ON COLUMN cart_items.item_id IS
    'cart_id || '':'' || sku_id. Khóa dùng cho upsert và batch delete.';
COMMENT ON COLUMN cart_items.unit_price_at_add IS
    'Bất biến sau khi tạo dòng. Không đưa vào product_snapshot.';
COMMENT ON COLUMN cart_items.product_snapshot IS
    'Chỉ các field hiển thị tĩnh. current_price là runtime và không bao giờ được lưu.';
COMMENT ON COLUMN cart_items.redis_stream_id IS
    'Guard thứ tự: chỉ ghi đè khi stream_id của event lớn hơn giá trị này.';

-- Index nóng nhất: đọc toàn bộ item khi rehydrate, và xóa theo giỏ khi dọn dữ liệu.
CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id
    ON cart_items (cart_id);

-- Truy vấn phân tích theo sản phẩm.
CREATE INDEX IF NOT EXISTS idx_cart_items_sku_id
    ON cart_items (sku_id);

-- Truy vấn phân tích theo người dùng mà không cần join carts.
CREATE INDEX IF NOT EXISTS idx_cart_items_user_id
    ON cart_items (user_id);

COMMIT;

-- =============================================================================
-- Ngữ nghĩa ghi mà consumer phải tuân thủ (tham chiếu, không phải DDL)
-- =============================================================================
--
-- Upsert item (CART_ITEM_ADDED / CART_ITEM_UPDATED):
--   INSERT INTO cart_items (...) VALUES (...)
--   ON CONFLICT (item_id) DO UPDATE SET
--       quantity         = EXCLUDED.quantity,
--       product_snapshot = EXCLUDED.product_snapshot,
--       redis_stream_id  = EXCLUDED.redis_stream_id,
--       updated_at       = NOW()
--   WHERE cart_items.redis_stream_id < EXCLUDED.redis_stream_id;
--
--   Lưu ý: unit_price_at_add KHÔNG nằm trong DO UPDATE SET (bất biến).
--
-- Hard delete item (CART_ITEM_DELETED):
--   DELETE FROM cart_items
--   WHERE item_id = $1 AND redis_stream_id < $2;
--
-- Upsert trạng thái giỏ (CART_ITEM_ADDED / CART_ITEM_UPDATED):
--   INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, ...)
--   VALUES ($1, $2, 'ACTIVE', $3, NOW(), NOW())
--   ON CONFLICT (cart_id) DO UPDATE SET
--       status               = 'ACTIVE',
--       last_event_stream_id = EXCLUDED.last_event_stream_id,
--       updated_at           = NOW()
--   WHERE carts.last_event_stream_id < EXCLUDED.last_event_stream_id;
--
-- Đổi trạng thái (CART_CLEARED / CART_CHECKED_OUT):
--   UPDATE carts
--   SET status = $2, last_event_stream_id = $3, updated_at = NOW()
--   WHERE cart_id = $1 AND last_event_stream_id < $3;
--
--   Hai event này KHÔNG xóa cart_items: từng item đã bị xóa trước đó bởi các
--   CART_ITEM_DELETED riêng lẻ trong cùng thao tác batch delete.
--
-- Dọn dữ liệu quá hạn — thứ tự bắt buộc, cùng một transaction:
--   BEGIN;
--     DELETE FROM cart_items WHERE cart_id = ANY($1);
--     DELETE FROM carts      WHERE cart_id = ANY($1);
--   COMMIT;
-- =============================================================================
