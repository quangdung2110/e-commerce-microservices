# Order Service - Search/Filter Design (PostgreSQL, Phase 15)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Workers](order_workers.md)
*   [Events](order_events.md)

---

Tài liệu này mô tả Search/Filter API của Order Service (`GET /v1/orders/search`) sau khi **loại bỏ hoàn toàn kiến trúc CQRS read-model Elasticsearch** (Phase 15). Mọi truy vấn được phục vụ trực tiếp từ PostgreSQL bằng các index/partial index/view được thiết kế riêng.

> **Nguyên tắc cốt lõi (Phase 15):**
> 1. PostgreSQL vừa là source of truth vừa là read path.
> 2. Không còn projector nào ghi vào read-model. Không còn Kafka topic retry/DLQ cho CQRS.
> 3. Text search dùng `pg_trgm` + `unaccent` substring match — chấp nhận giảm chất lượng so với ES (không có tokenization, scoring, fuzzy, highlight, autocomplete).
> 4. Text search chỉ chạy trên `order_item_lines.snapshot_name`.

---

## 1. Mục tiêu & Query Classes

### 1.1. Hai loại truy vấn

| Query Class | Mục tiêu | Nguồn dữ liệu |
|---|---|---|
| A — Multi-dimensional Filter | Lọc chính xác lịch sử đơn theo buyer/admin + trạng thái + khoảng thời gian + khoảng giá trị | Trực tiếp `orders` + subquery card fields |
| B — Text Search | Tìm theo `snapshot_product_name` và kết hợp filter class A | `order_item_lines` (substring) → collapse về `orders` |

### 1.2. Product contract kiểu platformee

- Đơn vị hiển thị ở list luôn là **order-level card** (1 card/1 order).
- Buyer tabs chuẩn hóa 6 nhóm: `TO_PAY`, `TO_SHIP`, `TO_RECEIVE`, `COMPLETED`, `CANCELLED`, `RETURN_REFUND`.
- Default buyer window = `90 ngày` gần nhất nếu client không truyền `from/to` (performance bound cho `idx_orders_buyer_created_id`; không còn ràng buộc bởi cold cutoff từ Phase 16).
- `q` (text search) chỉ chạy trên `snapshot_product_name`. Cú pháp: substring accent-insensitive.

---

## 2. PostgreSQL Extensions & Schema bổ sung

### 2.1. Extensions bắt buộc

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
```

### 2.2. Indexes mới (migration `00X_order_search_indexes.up.sql`)

```sql
-- Buyer scope: keyset pagination theo (user_id, created_at, id)
CREATE INDEX idx_orders_buyer_created_id
    ON orders (user_id, created_at DESC, id DESC);

-- Admin scope: full scope theo tenant, không filter user_id
CREATE INDEX idx_orders_created_id
    ON orders (created_at DESC, id DESC);

-- Filter theo status + payment
CREATE INDEX idx_orders_status_payment_created
    ON orders (status, payment_status, created_at DESC, id DESC);

-- Filter theo final_amount (range)
CREATE INDEX idx_orders_final_amount_created
    ON orders (final_amount, created_at DESC, id DESC)
    WHERE final_amount IS NOT NULL;

-- Text search: substring + accent-insensitive trên tên sản phẩm
CREATE INDEX idx_oil_snapshot_name_search
    ON order_item_lines USING gin (lower(unaccent(snapshot_name)) gin_trgm_ops);
```

### 2.3. View `v_orders_buyer_tab`

```sql
CREATE OR REPLACE VIEW v_orders_buyer_tab AS
SELECT
    o.id              AS order_id,
    o.user_id,
    o.status          AS order_status,
    o.payment_status,
    o.created_at,
    o.final_amount,
    CASE
        WHEN o.status = 'PENDING_PAYMENT'                                              THEN 'TO_PAY'
        WHEN o.status IN ('PAID', 'PROCESSING')
             AND NOT EXISTS (SELECT 1 FROM order_items oi
                             WHERE oi.order_id = o.id AND oi.status = 'DELIVERED')    THEN 'TO_SHIP'
        WHEN o.status IN ('PAID', 'PROCESSING')
             AND EXISTS (SELECT 1 FROM order_items oi
                         WHERE oi.order_id = o.id AND oi.status = 'DELIVERED')        THEN 'TO_RECEIVE'
        WHEN o.status = 'COMPLETED'                                                    THEN 'COMPLETED'
        WHEN o.status = 'CANCELLED'                                                    THEN 'CANCELLED'
        ELSE 'RETURN_REFUND'
    END AS buyer_tab
FROM orders o;
```

Tab precedence bám sát [Order FSM §4](order_state_machine.md#4-buyer-tab-precedence-platformee-style). Các trạng thái không match rule 1–5 (`RETURN_REQUESTED`, `REFUNDED` hoặc `payment_status in (REFUNDING, REFUNDED, REFUND_FAILED)`) rơi vào rule 6 fallback `RETURN_REFUND`. Lưu ý: `payment_status` chỉ còn 5 giá trị (đã gộp `REFUND_REVERSED` về `REFUND_FAILED` tại Order; lý do gốc giữ trong event payload).

---

## 3. Query Class A — Multi-dimensional Filter

### 3.1. SQL chính

```sql
WITH filtered AS (
    SELECT o.*
    FROM orders o
    WHERE (:scope = 'admin' OR o.user_id = :buyer_id)
      AND o.created_at >= :from_ts
      AND o.created_at <  :to_ts
      AND (:tab IS NULL OR EXISTS (
            SELECT 1 FROM v_orders_buyer_tab v
            WHERE v.order_id = o.id AND v.buyer_tab = :tab))
      AND (cardinality(:order_statuses) = 0 OR o.status = ANY(:order_statuses))
      AND (cardinality(:payment_statuses) = 0 OR o.payment_status = ANY(:payment_statuses))
      AND (:min_amount IS NULL OR o.final_amount >= :min_amount)
      AND (:max_amount IS NULL OR o.final_amount <= :max_amount)
      AND (:cursor_created_at IS NULL
           OR (o.created_at, o.id) < (:cursor_created_at, :cursor_order_id))
    ORDER BY o.created_at DESC, o.id DESC
    LIMIT :limit + 1
)
SELECT
    f.id                AS order_id,
    f.user_id,
    f.status            AS order_status,
    f.payment_status,
    f.created_at,
    f.paid_at,
    f.completed_at,
    f.cancelled_at,
    f.refunded_at,
    f.final_amount,
    f.total_amount,
    f.total_discount,
    v.buyer_tab,
    (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = f.id)                                         AS item_count,
    ((SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = f.id) - 1)                                  AS remaining_item_count,
    'PLATFORM'                                                                                              AS primary_store_name,
    (SELECT MIN(oil.snapshot_name) FROM order_item_lines oil
        JOIN order_items oi2 ON oi2.id = oil.order_item_id
        WHERE oi2.order_id = f.id)                                                                       AS primary_item_name,
    (SELECT MIN(oil.snapshot_image_url) FROM order_item_lines oil
        JOIN order_items oi2 ON oi2.id = oil.order_item_id
        WHERE oi2.order_id = f.id)                                                                       AS primary_item_image_url,
    CASE v.buyer_tab
        WHEN 'TO_PAY'        THEN 'PENDING_PAYMENT'
        WHEN 'TO_SHIP'       THEN 'PROCESSING'
        WHEN 'TO_RECEIVE'    THEN 'DELIVERED'
        WHEN 'COMPLETED'     THEN 'COMPLETED'
        WHEN 'CANCELLED'     THEN 'CANCELLED'
        ELSE                      'RETURN_REFUND'
    END AS ui_primary_status,
    CASE v.buyer_tab
        WHEN 'TO_PAY'        THEN 10
        WHEN 'TO_SHIP'       THEN 20
        WHEN 'TO_RECEIVE'    THEN 30
        WHEN 'RETURN_REFUND' THEN 40
        WHEN 'COMPLETED'     THEN 50
        WHEN 'CANCELLED'     THEN 60
        ELSE                      99
    END AS ui_status_rank
FROM filtered f
JOIN v_orders_buyer_tab v ON v.order_id = f.id;
```

### 3.2. Total count (chạy riêng)

```sql
SELECT count(*)
FROM orders o
WHERE (:scope = 'admin' OR o.user_id = :buyer_id)
  AND o.created_at >= :from_ts AND o.created_at < :to_ts
  AND (:tab IS NULL OR EXISTS (
        SELECT 1 FROM v_orders_buyer_tab v
        WHERE v.order_id = o.id AND v.buyer_tab = :tab))
  AND (cardinality(:order_statuses) = 0 OR o.status = ANY(:order_statuses))
  AND (cardinality(:payment_statuses) = 0 OR o.payment_status = ANY(:payment_statuses))
  AND (:min_amount IS NULL OR o.final_amount >= :min_amount)
  AND (:max_amount IS NULL OR o.final_amount <= :max_amount);
```

### 3.3. Visibility scope

- `:scope = 'buyer'` → bắt buộc có `:buyer_id` (UUID), filter `o.user_id = :buyer_id`. Index `idx_orders_buyer_created_id`.
- `:scope = 'admin'` → bỏ filter `user_id`, dùng `idx_orders_created_id`.

---

## 4. Query Class B — Text Search (chỉ trên `snapshot_product_name`)

### 4.1. SQL chính

```sql
WITH matched AS (
    SELECT DISTINCT o.id AS order_id
    FROM orders o
    JOIN order_items oi       ON oi.order_id = o.id
    JOIN order_item_lines oil ON oil.order_item_id = oi.id
    WHERE (:scope = 'admin' OR o.user_id = :buyer_id)
      AND o.created_at >= :from_ts AND o.created_at < :to_ts
      AND lower(unaccent(oil.snapshot_name))
          LIKE '%' || lower(unaccent(:q)) || '%'
),
filtered AS (
    SELECT m.order_id
    FROM matched m
    JOIN orders o ON o.id = m.order_id
    WHERE (cardinality(:order_statuses) = 0 OR o.status = ANY(:order_statuses))
      AND (cardinality(:payment_statuses) = 0 OR o.payment_status = ANY(:payment_statuses))
      AND (:min_amount IS NULL OR o.final_amount >= :min_amount)
      AND (:max_amount IS NULL OR o.final_amount <= :max_amount)
      AND (:tab IS NULL OR EXISTS (SELECT 1 FROM v_orders_buyer_tab v
                                   WHERE v.order_id = o.id AND v.buyer_tab = :tab))
      AND (:cursor_created_at IS NULL
           OR (o.created_at, o.id) < (:cursor_created_at, :cursor_order_id))
    ORDER BY o.created_at DESC, o.id DESC
    LIMIT :limit + 1
)
SELECT
    f.order_id,
    o.user_id,
    o.status          AS order_status,
    o.payment_status,
    o.created_at,
    o.paid_at,
    o.completed_at,
    o.cancelled_at,
    o.refunded_at,
    o.final_amount,
    o.total_amount,
    o.total_discount,
    v.buyer_tab,
    (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = f.order_id)                                AS item_count,
    ((SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = f.order_id) - 1)                         AS remaining_item_count,
    'PLATFORM'                                                                                              AS primary_store_name,
    (SELECT MIN(oil.snapshot_name) FROM order_item_lines oil
        JOIN order_items oi2 ON oi2.id = oil.order_item_id
        WHERE oi2.order_id = f.order_id)                                                                 AS primary_item_name,
    (SELECT MIN(oil.snapshot_image_url) FROM order_item_lines oil
        JOIN order_items oi2 ON oi2.id = oil.order_item_id
        WHERE oi2.order_id = f.order_id)                                                                 AS primary_item_image_url,
    CASE v.buyer_tab
        WHEN 'TO_PAY'        THEN 'PENDING_PAYMENT'
        WHEN 'TO_SHIP'       THEN 'PROCESSING'
        WHEN 'TO_RECEIVE'    THEN 'DELIVERED'
        WHEN 'COMPLETED'     THEN 'COMPLETED'
        WHEN 'CANCELLED'     THEN 'CANCELLED'
        ELSE                      'RETURN_REFUND'
    END AS ui_primary_status,
    CASE v.buyer_tab
        WHEN 'TO_PAY'        THEN 10
        WHEN 'TO_SHIP'       THEN 20
        WHEN 'TO_RECEIVE'    THEN 30
        WHEN 'RETURN_REFUND' THEN 40
        WHEN 'COMPLETED'     THEN 50
        WHEN 'CANCELLED'     THEN 60
        ELSE                      99
    END AS ui_status_rank
FROM filtered f
JOIN orders o ON o.id = f.order_id
JOIN v_orders_buyer_tab v ON v.order_id = f.order_id
ORDER BY o.created_at DESC, o.id DESC;
```

### 4.2. Total count (chạy riêng)

```sql
SELECT count(DISTINCT m.order_id)
FROM (
    SELECT DISTINCT o.id AS order_id
    FROM orders o
    JOIN order_items oi       ON oi.order_id = o.id
    JOIN order_item_lines oil ON oil.order_item_id = oi.id
    WHERE (:scope = 'admin' OR o.user_id = :buyer_id)
      AND o.created_at >= :from_ts AND o.created_at < :to_ts
      AND lower(unaccent(oil.snapshot_name))
          LIKE '%' || lower(unaccent(:q)) || '%'
) m
JOIN orders o ON o.id = m.order_id
WHERE (cardinality(:order_statuses) = 0 OR o.status = ANY(:order_statuses))
  AND (cardinality(:payment_statuses) = 0 OR o.payment_status = ANY(:payment_statuses))
  AND (:min_amount IS NULL OR o.final_amount >= :min_amount)
  AND (:max_amount IS NULL OR o.final_amount <= :max_amount)
  AND (:tab IS NULL OR EXISTS (SELECT 1 FROM v_orders_buyer_tab v
                               WHERE v.order_id = o.id AND v.buyer_tab = :tab));
```

### 4.3. Trade-off FTS

- Substring accent-insensitive: "ao thun" khớp "Áo thun", "dien thoai" khớp "Điện thoại".
- **Không có** tokenization → "áo thun" KHÔNG khớp "áo" (chỉ khớp substring nguyên dòng).
- **Không có** fuzzy / Levenshtein → "cottn" KHÔNG khớp "cotton".
- **Không có** autocomplete prefix.
- **Không có** highlight / relevance ranking. Order kết quả xác định theo `created_at DESC, id DESC`.

---

## 5. Pagination (keyset cursor)

- Cursor base64 encode `(created_at_iso, order_id)`.
- Client gửi `cursor` trong request; backend decode → bind vào `:cursor_created_at`, `:cursor_order_id`.
- Query áp `WHERE (created_at, id) < (cursor_created_at, cursor_order_id) ORDER BY created_at DESC, id DESC LIMIT :limit + 1`.
- Lấy `limit + 1` để detect next page: nếu trả về `limit + 1` rows, build `next_cursor` từ row cuối; nếu ≤ `limit`, trả `next_cursor = null`.

---

## 6. Response Contract

```json
{
  "total_orders": 42,
  "items": [
    {
      "order_id": 10001,
      "buyer_tab": "TO_RECEIVE",
      "ui_primary_status": "DELIVERED",
      "ui_status_rank": 30,
      "primary_item_name": "Ao thun nam cotton premium",
      "primary_item_image_url": "https://cdn.../item-001.jpg",
      "primary_store_name": "PLATFORM",
      "item_count": 2,
      "remaining_item_count": 1,
      "created_at": "2026-02-28T10:00:00Z",
      "final_amount": 950000
    }
  ],
  "next_cursor": "base64(2026-02-28T10:00:00Z:10001)"
}
```

> **Bỏ hẳn** so với Phase 14: `matched_lines_count`, `matched_context[]`, `match_reason`, `matched_lines`.

---

## 7. Performance Notes

- Hot query 90 ngày của buyer trung bình cỡ vài chục đến vài trăm orders → `idx_orders_buyer_created_id` đủ phục vụ.
- Subquery card fields (`MIN(snapshot_name)`, ...) là N+1 style nhưng mỗi subquery chỉ scan theo `order_item_id` (đã có PK) → không tốn index mới.
- View `v_orders_buyer_tab` được evaluate inline mỗi query. Nếu benchmark chậm, có thể chuyển sang materialized view + refresh job (xem §3.3 về visibility scope — view chỉ cần compute khi filter có `:tab`).
- Text search GIN trigram index chiếm ~3-5x kích thước cột gốc; cần monitor disk.