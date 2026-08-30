# Order Service - Database & Architecture Design

> **Phase 22 (Simplify Idempotency Schema):** Bỏ toàn bộ 2 bảng `order_request_idempotency` + `order_event_idempotency`. One-shot guard cho Buyer return giờ là cột `orders.buyer_return_locked_at` (CAS row-level lock); idempotency sync API và Kafka consumer dedup dùng **Redis SETNX** (TTL 7 ngày cho API, 30 ngày cho consumer) thay cho durable DB layer. Xem [CHANGELOG Phase 22](../_contracts/CHANGELOG.md#phase-22--simplify-idempotency-schema-order) và [Idempotency Guidelines](../_contracts/IDEMPOTENCY_GUIDELINES.md).

**Liên kết nhanh (Related Documents):**
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [Search/Filter Design (PostgreSQL direct)](order_search_design.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Background Workers & Jobs](order_workers.md)
*   [Events Structure & Kafka Topics](order_events.md)

---

Tài liệu này mô tả thiết kế cơ sở dữ liệu (PostgreSQL) cho Order Service, chịu trách nhiệm quản lý vòng đời đơn hàng, tách kiện theo kho hàng, và điều phối giao dịch phân tán (Distributed Saga).

---
## 1. Thiết kế Master-Sub Order (Tách Kiện Hàng)

Để giải quyết bài toán định tuyến kho hàng thông minh (multi-warehouse), kiến trúc Đơn hàng được chia làm 3 lớp: Bảng `orders` (Master), `order_items` (delivery unit theo kho), và `order_item_lines` (chi tiết SKU snapshot).

### 1.1. `orders` (Master Order)
Lưu trữ thông tin tổng quát của giao dịch để khách hàng thanh toán 1 lần duy nhất.
*   `id`: BIGSERIAL PRIMARY KEY
*   `user_id`: UUID (Người mua, thống nhất canonical user_id toàn hệ thống)
*   `total_amount`: DECIMAL (Tổng tiền hàng)
*   `total_discount`: DECIMAL (Tổng giảm giá từ Promotion)
*   `final_amount`: DECIMAL (Tổng tiền khách phải trả = `total_amount - total_discount`)
*   `payment_status`: VARCHAR(50) (PENDING, PAID, FAILED, REFUNDING, REFUNDED, REFUND_FAILED) — **denormalized for read-model/UI only**. Cả `REFUND_FAILED` từ Payment Service (webhook từ chối) lẫn `REFUND_REVERSED` (provider đảo chiều sau khi success) đều map về cùng giá trị `REFUND_FAILED` ở Order; lý do gốc giữ trong `ORDER_REFUND_FAILED` event payload (`error_code`, `error_message`).
*   `status`: VARCHAR(50) (CREATED, PENDING_PAYMENT, PAID, PROCESSING, COMPLETED, CANCELLED, RETURN_REQUESTED, REFUNDED)
*   `checkout_token`: VARCHAR(255) UNIQUE NOT NULL (snapshot token do Checkout cấp, dùng route signal theo WorkflowID `order-checkout-{checkout_token}`)
*   `cancel_reason`: TEXT NULL (lý do cancel dạng free text; canonical set do workflow layer commit, ví dụ: `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PRE_PAYMENT_SAGA_FAILED`, `PAYMENT_FAILED`. Không dùng DB CHECK constraint — workflow là owner duy nhất của transition.)
*   `return_reason`: VARCHAR(100) NULL (summary reason mức master order cho lane return/refund; chi tiết per-item vẫn nằm ở item/delivery metadata)
*   `buyer_return_locked_at`: TIMESTAMPTZ NULL (Phase 22) — **one-shot guard** cho Buyer return API (`POST /v1/orders/{orderId}/return`). Cơ chế: API layer chạy `UPDATE orders SET buyer_return_locked_at = NOW() WHERE id = :order_id AND buyer_return_locked_at IS NULL RETURNING id`. Nếu `RETURNING` không trả row → guard đã được sử dùng → trả `409 REFUND_CUSTOMER_REQUEST_ALREADY_USED`. Guard vẫn giữ nguyên semantics cũ: không bị reset khi refund fail/reversed, chỉ áp cho trigger `API_RETURN`, không áp cho `ADMIN_FORCE_REFUND_REQUESTED`.
*   `last_event_sequence_id`: BIGINT NOT NULL DEFAULT 0 (counter đơn điệu per `order_id`, tăng mỗi khi insert `outbox_events` thuộc order đó. Thay thế 3 sequence_id cũ `payment_event_sequence_id` / `refund_event_sequence_id` / `event_sequence_id` ở order scope — xem §3.0.)
*   `created_at`, `updated_at`: TIMESTAMPTZ

> **Lý do snapshot tên platform là bắt buộc:** Trường này vẫn được giữ để đảm bảo tính nhất quán trong lịch sử đơn hàng.

### 1.2. `order_items` (Sub-order / Kiện hàng)
Mỗi Order có thể tách thành 1 hoặc nhiều Item dựa trên Tọa độ Kho hàng và quyết định của khách hàng lúc Checkout (Smart Routing).
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT (Foreign Key -> `orders.id`)
*   `warehouse_id`: BIGINT (Kho xuất hàng)
*   `status`: VARCHAR(50) (PROCESSING, DELIVERED, RETURNED)
*   `delivery_address_snapshot`: JSONB NOT NULL — Snapshot địa chỉ giao hàng tại thời điểm tạo đơn từ Checkout session. Format: `{"recipient_name": "...", "recipient_phone": "...", "address_line": "...", "province_code": "...", "district_code": "...", "ward_code": "..."}`. Ward được giữ để snapshot phản ánh đầy đủ address đã chọn, không phụ thuộc việc User sau này sửa/xóa address hoặc đổi địa giới.


### 1.3. `order_item_lines` (Order Snapshot / Chi tiết Sản phẩm)
Lưu snapshot tĩnh của thông tin sản phẩm tại thời điểm mua, không phụ thuộc vào Product Service trong tương lai. Map với từng `order_items` cụ thể.
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_item_id`: BIGINT (Foreign Key -> `order_items.id`)
*   `sku_id`: VARCHAR(255)
*   `product_id`: VARCHAR(255)
*   `quantity`: INT
*   `unit_price`: DECIMAL (Đơn giá lúc mua)
*   `snapshot_name`: VARCHAR(255) (Tên sản phẩm lúc mua)
*   `snapshot_image_url`: VARCHAR(255) (Hình ảnh lúc mua)
*   `snapshot_attributes`: JSONB (Ví dụ: `{"color": "Red", "size": "XL"}`)

### 1.4. `order_promotions` (Chi tiết Áp dụng Khuyến mãi)
Lưu dấu vết các voucher/deal đã dùng cho đơn hàng này để đối soát: nhả reservation ở pre-payment cancel và hoàn budget ở refund lane (không suy diễn rollback trạng thái ví từ đây).
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT
*   `promotion_id`: BIGINT
*   `discount_amount`: DECIMAL (Số tiền đã giảm — PLATFORM allocation duy nhất trong hệ thống)


---

---
## 2. Infrastructure & Distributed Saga

Order Service là Orchestrator, kết nối với Payment, Inventory, và Promotion. Để đảm bảo tính nhất quán cuối (Eventual Consistency) và hỗ trợ giao dịch phân tán (Saga), bên cạnh Temporal Workflow, chúng ta cần các bảng phục vụ Event-Driven Architecture nội bộ.

### 2.0. Sequence ID Consolidation (Thay thế `*_event_sequence_id` riêng biệt)

> **Scope quan trọng:** mục này nói về **counter Order tự phát ra (own produced events)** — KHÔNG liên quan đến counter có trong event Order **consume** từ Payment Service.

**Quyết định gộp (chỉ cho Order's own produced events):**
- Counter `orders.last_event_sequence_id` (BIGINT, default 0) thay thế 2 counter cũ mà trước đây Order tự phát trong `ORDER_*` events (per-order). Mỗi lần insert `outbox_events` cho 1 order → tăng `orders.last_event_sequence_id += 1` trong cùng transaction và ghi vào `payload.last_event_sequence_id`.

> **Phase 21:** Counter `event_sequence_id` trong `order_product_metrics` (per `product_id`) đã bị **loại bỏ hoàn toàn** cùng với bảng aggregate và event `ORDER_PRODUCT_METRICS_UPDATED` (Search Service không còn cần `product_sold_count`). Order Service giờ chỉ duy trì 1 counter duy nhất `last_event_sequence_id` (per `order_id`) cho mọi Order's own produced events.

**Lý do gộp order-scope counter:**
- Một counter đơn điệu per order đủ để consumer downstream (Notification, Search, Analytics) guard out-of-order mà không cần tách payment/refund.
- Temporal workflow đã đảm bảo ordering trong workflow context; counter chỉ cần phục vụ consumer bên ngoài.
- Bỏ 2 field `payment_event_sequence_id`/`refund_event_sequence_id` khỏi **payload do Order tự phát** (vì Order không phát counter đó nữa) + bỏ 1 field `refund_event_sequence_id` khỏi bảng `order_refund_cases` — giảm schema + payload size.

**Counter trong event Order CONSUME từ service khác (KHÔNG đổi):**
- Event `PAYMENT_*` từ Payment Service vẫn có `payment_event_sequence_id`; event `REFUND_*` vẫn có `refund_event_sequence_id` (do Payment Service tự quản, Order không can thiệp).
- Order **không** dùng 2 field này làm guard. Order guard cho consumed event dựa vào **Redis SETNX** (`payment:signal:{order_id}:{txn_id}:{event_type}`, TTL 30 ngày) kết hợp với FSM row-level lock (`UPDATE ... WHERE status='OLD_STATUS'`) + CloudEvents `event_id` envelope.

### 2.1. `outbox_events` (Transactional Outbox)
Đảm bảo Event được gửi đi một cách tin cậy sau khi CSDL thay đổi theo mô hình **CDC append-only**, đồng bộ chuẩn với [Product](../product/product_db_design.md) và [Promotion](../promotion/promotion_db_design.md).
*   `event_id`: UUID PRIMARY KEY
*   `schema_version`: INT NOT NULL DEFAULT 1
*   `aggregate_type`: VARCHAR(50) NOT NULL (VD: `Order`, `OrderItem`)
*   `aggregate_id`: VARCHAR(255) NOT NULL
*   `event_type`: VARCHAR(100) NOT NULL (VD: `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`)
*   `partition_key`: VARCHAR(255) NOT NULL (VD: `order_id` cho `t.order.events`)
*   `payload`: JSONB NOT NULL (Payload bắt buộc có `schema_version` ở top-level envelope)
*   `created_at`: TIMESTAMPTZ NOT NULL

**Indexes:**
```sql
CREATE INDEX idx_outbox_created_at
    ON outbox_events (created_at DESC);
```

## 3. Refund Case Control Plane

### 3.1. `order_refund_cases` (Single Refund Transaction Case)
Control plane cho policy B: mỗi order chỉ có một refund transaction do Order khởi tạo, với refund scope lock theo request đầu vào và gate Inventory-confirm trước khi gọi Payment refund.

```sql
CREATE TABLE order_refund_cases (
    order_id BIGINT PRIMARY KEY,
    workflow_id VARCHAR(255) NOT NULL,
    trigger_source VARCHAR(40) NOT NULL,
    case_status VARCHAR(30) NOT NULL
        CHECK (case_status IN ('INITIATED', 'WAITING_INVENTORY_RETURN_CONFIRMATION', 'WAITING_PAYMENT_REFUND_RESULT', 'SUCCEEDED', 'FAILED')),
    refund_type_locked VARCHAR(20) NULL
        CHECK (refund_type_locked IN ('FULL', 'PARTIAL')),
    requested_order_item_ids_locked JSONB NOT NULL DEFAULT '[]'::jsonb,
    refund_amount_locked DECIMAL(18,2) NULL,
    refund_transaction_id VARCHAR(120) NULL UNIQUE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    scope_locked_at TIMESTAMPTZ NULL,
    inventory_return_confirmed_at TIMESTAMPTZ NULL,
    resolved_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_refund_cases_status_updated
    ON order_refund_cases (case_status, updated_at DESC);
```

> **Lưu ý:** Trước đây có terminal state `REVERSED` (do payment provider đảo chiều refund). Nay đã gộp vào `FAILED` vì xử lý nghiệp vụ giống nhau (giữ case ở terminal fail + escalate cho Ops).

**Quy tắc bắt buộc:**
- `order_id` chỉ có tối đa 1 bản ghi case đang active.
- Case chỉ được tạo từ `API_RETURN` hoặc `REQUEST_ADMIN_REFUND`.
- Sau khi lock scope, workflow phải ở trạng thái `WAITING_INVENTORY_RETURN_CONFIRMATION` cho đến khi nhận event Inventory xác nhận đã nhận đủ item thuộc refund scope.
- Chỉ sau khi có `inventory_return_confirmed_at` mới được tạo `refund_transaction_id` và chuyển `WAITING_PAYMENT_REFUND_RESULT`.
- Khi case đã terminal (`SUCCEEDED|FAILED`), không được mở refund transaction thứ hai cho cùng `order_id`.
- `refund_event_sequence_id` (cũ) được lấy từ `orders.last_event_sequence_id` tại thời điểm tạo case; không lưu trong bảng này.

**Migration/backfill gợi ý (chạy TRƯỚC khi DROP `order_request_idempotency` tại Phase 22):**
- Backfill `orders.buyer_return_locked_at` từ lịch sử request cũ có `action_type = REQUEST_RETURN_GUARD`:
  ```sql
  UPDATE orders o
  SET buyer_return_locked_at = ori.locked_at
  FROM order_request_idempotency ori
  WHERE ori.resource_id = o.id::varchar
    AND ori.action_type = 'REQUEST_RETURN_GUARD'
    AND ori.status = 'COMPLETED'
    AND o.buyer_return_locked_at IS NULL;
  ```
- Sau khi backfill xong, DROP TABLE `order_request_idempotency` + `order_event_idempotency` (xem [CHANGELOG Phase 22](../_contracts/CHANGELOG.md#phase-22--simplify-idempotency-schema-order) cho script migration đầy đủ).


---

## 4. Kiến trúc Mở rộng (Scalability & Performance)

Để đảm bảo hệ thống Order Service có thể xử lý lượng tải khổng lồ (Read-Heavy) và lưu trữ lâu dài mà không suy giảm hiệu năng RDBMS, hai kỹ thuật chuyên sâu được áp dụng:

### 4.1. Tìm kiếm và Lọc trực tiếp trên PostgreSQL

**Chiến lược query:**

- **Class A — Filter đa chiều**: query trực tiếp `orders` với B-tree composite theo `(user_id, created_at DESC, id DESC)` cho buyer scope, `(created_at DESC, id DESC)` cho admin scope. Kết hợp partial index theo `status/payment_status` và `(final_amount, created_at, id)` cho range filter.
- **Class B — Text search**: dùng `pg_trgm` + `unaccent` GIN index trên `order_item_lines.snapshot_name`. Substring accent-insensitive qua `lower(unaccent(...)) LIKE '%' || lower(unaccent(:q)) || '%'`. Chỉ search theo tên sản phẩm.
- **Card fields**: derive runtime qua subquery (`MIN(snapshot_name)`, `MIN(snapshot_image_url)`, `COUNT(order_items)`). Không cache. `primary_store_name` hardcode constant `'PLATFORM'` (xem [Search/Filter Design §3.1](order_search_design.md#31-sql-chính)).
- **Buyer tab**: derive tại view `v_orders_buyer_tab` theo bảng precedence y hệt [Order FSM §4](order_state_machine.md#4-buyer-tab-precedence-platformee-style).
- **Pagination**: keyset theo `(created_at, order_id)`.

**Extensions & Indexes cần bật thêm:**

```sql
-- Extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Buyer scope (keyset pagination)
CREATE INDEX idx_orders_buyer_created_id
    ON orders (user_id, created_at DESC, id DESC);

-- Admin scope (full, không filter user_id)
CREATE INDEX idx_orders_created_id
    ON orders (created_at DESC, id DESC);

-- Filter theo status + payment
CREATE INDEX idx_orders_status_payment_created
    ON orders (status, payment_status, created_at DESC, id DESC);

-- Filter theo final_amount (range)
CREATE INDEX idx_orders_final_amount_created
    ON orders (final_amount, created_at DESC, id DESC)
    WHERE final_amount IS NOT NULL;

-- Text search (substring + accent-insensitive trên tên sản phẩm)
CREATE INDEX idx_oil_snapshot_name_search
    ON order_item_lines USING gin (lower(unaccent(snapshot_name)) gin_trgm_ops);
```

**View dẫn xuất buyer_tab:**

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

📖 *Chi tiết kiến trúc & SQL đầy đủ:* Xem file `docs/order/order_search_design.md`
