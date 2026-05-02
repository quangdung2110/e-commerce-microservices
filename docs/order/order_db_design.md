# Order Service - Database & Architecture Design

**Liên kết nhanh (Related Documents):**
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [CQRS & Elasticsearch](cqrs_elasticsearch_design.md)
*   [Cold Data Archiving](cold_data_archiving_design.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Background Workers & Jobs](order_workers.md)
*   [Events Structure & Kafka Topics](order_events.md)

---

Tài liệu này mô tả thiết kế cơ sở dữ liệu (PostgreSQL) cho Order Service, chịu trách nhiệm quản lý vòng đời đơn hàng, tách kiện hàng (Order Splitting) tối ưu chi phí vận chuyển, và điều phối giao dịch phân tán (Distributed Saga).

---
## 1. Thiết kế Master-Sub Order (Tách Kiện Hàng)

Để giải quyết bài toán định tuyến thông minh, tối ưu chi phí vận chuyển và thời gian giao hàng từ nhiều điểm kho khác nhau, kiến trúc Đơn hàng được chia làm 3 lớp: Bảng `orders` (Master), `order_items` (shipment unit), và `order_item_lines` (chi tiết SKU snapshot).

### 1.1. `orders` (Master Order)
Lưu trữ thông tin tổng quát của giao dịch để khách hàng thanh toán 1 lần duy nhất.
*   `id`: BIGSERIAL PRIMARY KEY
*   `user_id`: BIGINT (Người mua, dùng để Sharding/Partitioning sau này)
*   `total_amount`: DECIMAL (Tổng tiền hàng)
*   `total_shipping_fee`: DECIMAL (Tổng phí ship cộng dồn từ các kiện)
*   `total_discount`: DECIMAL (Tổng giảm giá từ Promotion)
*   `final_amount`: DECIMAL (Tổng tiền khách phải trả)
*   `payment_method`: VARCHAR(50) (VNPAY, MOMO, ZALOPAY) — **denormalized for read-model/UI only**
*   `payment_status`: VARCHAR(50) (PENDING, PAID, FAILED, REFUNDING, REFUNDED, REFUND_FAILED, REFUND_REVERSED) — **denormalized for read-model/UI only**
*   `status`: VARCHAR(50) (CREATED, PENDING_PAYMENT, PAID, PROCESSING, COMPLETED, CANCELLED, RETURN_REQUESTED, REFUNDED)
*   `checkout_token`: VARCHAR(255) UNIQUE NOT NULL (snapshot token do Checkout cấp, dùng route signal theo WorkflowID `order-checkout-{checkout_token}`)
*   `cancel_reason`: VARCHAR(100) NULL (VD: `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PRE_PAYMENT_SAGA_FAILED`)
*   `return_reason`: VARCHAR(100) NULL (summary reason mức master order cho lane return/refund; chi tiết per-item vẫn nằm ở item/shipment metadata)
*   `version`: BIGINT DEFAULT 1 (Optimistic Locking cho FSM)
*   `created_at`, `updated_at`: TIMESTAMPTZ

> **Nguồn sự thật (Source of Truth):** Trạng thái và phương thức thanh toán thuộc quyền sở hữu của Payment Service. Hai cột `payment_method`, `payment_status` tại Order chỉ là bản sao phục vụ query nhanh. Cần có reconciliation job định kỳ để phát hiện và sửa drift dữ liệu giữa Order và Payment.

> **Checkout signal routing contract:** `checkout_token` bắt buộc được persist ngay tại `CreateOrderActivity` để workers có thể lookup theo `order_id` và signal đúng workflow `order-checkout-{checkout_token}`.

> **Customer return one-shot policy:** Guard nghiệp vụ cho `POST /v1/orders/{orderId}/return` được lưu riêng ở bảng `order_customer_return_guard` (mục 3.3.1). Policy này độc lập với `payment_status` và không được reset khi `REFUND_FAILED`/`REFUND_REVERSED`.

### 1.2. `order_items` (Sub-order / Kiện hàng)
Mỗi Order có thể tách thành 1 hoặc nhiều Item dựa trên Tọa độ Kho hàng và quyết định của khách hàng lúc Checkout (Smart Routing).
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT (Foreign Key -> `orders.id`)
*   `store_name_snapshot`: VARCHAR(255) (Tên platform tại thời điểm đặt hàng; dùng cho lịch sử và CQRS text-search)
*   `warehouse_id`: BIGINT (Kho xuất hàng)
*   `shipping_fee`: DECIMAL (Phí vận chuyển riêng của kiện này)
*   `tracking_number`: VARCHAR(100) (Mã vận đơn của đối tác - GHN/NinjaVan)
*   `courier_code`: VARCHAR(50) (Đơn vị vận chuyển)
*   `status`: VARCHAR(50) (PREPARING, PACKAGED, HANDED_OVER, IN_TRANSIT, DELIVERY_FAILED, DELIVERED, RETURNED)
*   `version`: BIGINT DEFAULT 1 (Optimistic Locking cho FSM)
*   `estimated_delivery_date`: DATE (Ngày dự kiến giao hàng)
*   `shipping_address_snapshot`: JSONB NOT NULL — Snapshot địa chỉ giao hàng tại thời điểm tạo đơn từ Checkout session. Format: `{"name": "...", "phone": "...", "address": "...", "province_code": "...", "district_code": "...", "ward_code": "..."}`

> **Lý do snapshot:** Tách biệt hoàn toàn với User Address Service. Khi user thay đổi/xóa địa chỉ sau khi đặt hàng, Shipping Service và CSKH vẫn có thể tra cứu chính xác địa chỉ giao hàng gốc cho từng kiện.
>
> **Source of Truth:** Lấy từ `checkout:session:{user_id}.shipping_address` tại thời điểm Order Service nhận Checkout Submit payload.

> **Snapshot tên platform là bắt buộc:** `store_name_snapshot` không được backfill theo admin profile mới. Trường này là nguồn dữ liệu chuẩn để build `store_name`/`primary_store_name` trong `orders_read` và `order_search_lines`.

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
*   `promotion_type`: VARCHAR(50) (CART_DISCOUNT, SHIPPING_DISCOUNT)
*   `discount_amount`: DECIMAL (Số tiền đã giảm)
*   `platform_funding`: DECIMAL (Phần giảm giá do Platform tài trợ)
*   `admin_funding`: DECIMAL (Phần giảm giá do admin tài trợ)

---
## 2. Quản lý trạng thái phức tạp với State Machine (FSM)

Để tránh tình trạng cập nhật trạng thái chồng chéo và Spaghetti code, Order Service sử dụng mô hình **Finite State Machine (FSM)** kết hợp **Optimistic Locking**:

*   **Định nghĩa Trạng thái (States):**
    *   **Master Order:** `CREATED` -> `PENDING_PAYMENT` -> `PAID` -> `PROCESSING` -> `COMPLETED` (hoặc rẽ nhánh sang `CANCELLED` cho pre-payment cancel, và `RETURN_REQUESTED` -> `REFUNDED` cho return/refund lane).
    *   **Item:** `PREPARING` -> `PACKAGED` -> `HANDED_OVER` -> `IN_TRANSIT` -> `DELIVERED` -> `RETURNED` (nhánh lỗi: `IN_TRANSIT` -> `DELIVERY_FAILED` -> `IN_TRANSIT|RETURNED`).
*   **Optimistic Locking (`version`):**
    Mọi lệnh Update trạng thái bắt buộc kiểm tra `version`. Ví dụ:
    `UPDATE orders SET status = 'PAID', version = version + 1 WHERE id = 123 AND version = X;`
    Nếu request đến sau mang version cũ hơn (do request trước đã đổi version), lệnh update sẽ thất bại, tránh việc FSM bị ngắt luồng sai (Ví dụ: Order đã `PAID` không được phép nhảy về `CANCELLED` vì quá hạn thanh toán chậm).

---
## 3. Infrastructure & Distributed Saga

Order Service là Orchestrator, kết nối với Payment, Inventory, và Promotion. Để đảm bảo tính nhất quán cuối (Eventual Consistency) và hỗ trợ giao dịch phân tán (Saga), bên cạnh Temporal Workflow, chúng ta cần các bảng phục vụ Event-Driven Architecture nội bộ.

### 3.1. `outbox_events` (Transactional Outbox)
Đảm bảo Event được gửi đi một cách tin cậy sau khi CSDL thay đổi theo mô hình **CDC append-only**, đồng bộ chuẩn với [Product](../product/product_db_design.md) và [Promotion](../promotion/promotion_db_design.md).
*   `event_id`: UUID PRIMARY KEY
*   `schema_version`: INT NOT NULL DEFAULT 1
*   `aggregate_type`: VARCHAR(50) NOT NULL (VD: `Order`, `OrderItem`, `ProductSalesMetrics`, `ArchiveBatch`)
*   `aggregate_id`: VARCHAR(255) NOT NULL
*   `event_type`: VARCHAR(100) NOT NULL (VD: `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`)
*   `partition_key`: VARCHAR(255) NOT NULL (VD: `order_id` cho `t.order.events`, `product_id` cho `t.search.metrics.events`, `archive_batch_id` cho `t.order.archive.retry`)
*   `payload`: JSONB NOT NULL (Payload bắt buộc có `schema_version` ở top-level envelope)
*   `created_at`: TIMESTAMPTZ NOT NULL

**Indexes:**
```sql
CREATE INDEX idx_outbox_created_at
    ON outbox_events (created_at DESC);

CREATE INDEX idx_outbox_partition_created
    ON outbox_events (partition_key, created_at DESC);
```

> **Nguyên tắc CDC bắt buộc:** Bảng outbox chỉ append, không có các cột polling (`status`, `retry_count`, `max_retries`, `next_retry_at`, `published_at`). Retry/DLQ được quản trị tại Kafka Connect/Debezium runtime.

### 3.2. `order_request_idempotency` (Sync Request Idempotency)
Chặn submit lặp ở luồng đồng bộ và hỗ trợ replay response an toàn.
*   `idempotency_key`: VARCHAR(255) PRIMARY KEY
*   `action_type`: VARCHAR(80) NOT NULL (VD: `CREATE_ORDER`, `CANCEL_ORDER`, `REQUEST_RETURN`, `REQUEST_ADMIN_REFUND`)
*   `request_hash`: VARCHAR(128) NOT NULL (hash canonical payload để chống reuse key sai body)
*   `resource_id`: VARCHAR(255) NULL (VD: `order_id`, `checkout_token`)
*   `status`: VARCHAR(30) NOT NULL (`IN_PROGRESS`, `COMPLETED`, `FAILED`)
*   `response_code`: INT NULL
*   `response_body`: JSONB NULL
*   `locked_at`: TIMESTAMPTZ NOT NULL
*   `created_at`: TIMESTAMPTZ NOT NULL
*   `expires_at`: TIMESTAMPTZ NOT NULL

> **Phân tách trách nhiệm:** `order_request_idempotency` chỉ xử lý replay cho cùng `Idempotency-Key`. Quy tắc one-shot theo Buyer API (`key` khác nhưng cùng `order_id`) được chặn bởi `order_customer_return_guard`, không chặn bằng bảng này.
>
> **Contract trạng thái async command:** Với API trả `202 Accepted`, record idempotency phải lưu `status = IN_PROGRESS` (không dùng enum `ACCEPTED`). Khi workflow kết thúc mới chuyển `COMPLETED` hoặc `FAILED`.

### 3.3. `order_event_idempotency` (Async Event/Signal Idempotency)
Chặn xử lý lặp cho Kafka consumers và signal handlers.
*   `event_id`: VARCHAR(255) PRIMARY KEY
*   `source_service`: VARCHAR(60) NOT NULL (VD: `payment-service`, `shipping-service`, `order-service`)
*   `order_id`: BIGINT NULL
*   `aggregate_id`: VARCHAR(255) NOT NULL
*   `action_type`: VARCHAR(80) NOT NULL
*   `action_scope_key`: VARCHAR(120) NOT NULL (VD: `FINAL`, `refund_transaction_id`, `shipment_id:sequence`, `archive_batch_id:attempt`)
*   `payload_hash`: VARCHAR(128) NULL
*   `processed_at`: TIMESTAMPTZ NOT NULL

**Action types chuẩn hóa (khởi tạo):**
- `PAYMENT_SUCCESS_SIGNAL`
- `PAYMENT_FAILED_SIGNAL`
- `PAYMENT_EXPIRED_SIGNAL`
- `REFUND_SUCCESS_SIGNAL`
- `REFUND_FAILED_SIGNAL`
- `REFUND_REVERSED_SIGNAL`
- `PAYMENT_SUCCESS_LATE_RECOVERY_START`
- `SHIPMENT_STATUS_APPLY`
- `SHIPMENT_DELIVERED_APPLY`
- `SHIPMENT_RETURNED_APPLY`
- `INVENTORY_RETURN_RECEIVED_ALL_SIGNAL`
- `SHIPMENT_DELAYED_APPLY`
- `ARCHIVE_BATCH_REPLAY`

**Unique guard đề xuất:**
```sql
CREATE UNIQUE INDEX uq_order_event_idempotency_scope
    ON order_event_idempotency (order_id, action_type, action_scope_key)
    WHERE order_id IS NOT NULL;

CREATE UNIQUE INDEX uq_order_event_idempotency_aggregate_scope
    ON order_event_idempotency (aggregate_id, action_type, action_scope_key)
    WHERE order_id IS NULL;
```

**Key source matrix (bắt buộc):**
- Kafka inbound events: dùng nguyên `event_id` từ CloudEvents envelope của producer gốc, không tự sinh lại.
- Temporal/payment/shipping signals: dùng `event_id` gốc của event đã consume; `action_scope_key` phải lấy từ business discriminator ổn định (`payment_transaction_id`, `refund_transaction_id`, `shipment_id:shipment_event_sequence_id`).
- Internal archive replay: `event_id` lấy từ outbox record mới; dedup nghiệp vụ dựa trên `action_scope_key = {archive_batch_id}:{attempt}`.

**`payload_hash` guideline:**
- Bắt buộc hash payload canonical bằng SHA-256 khi event có payload lớn hoặc chứa trường dễ drift do serialize khác thứ tự key.
- Nếu nhận cùng `event_id` nhưng `payload_hash` khác nhau, phải đẩy vào lane cảnh báo contract violation để tránh xử lý mù.

### 3.3.1. `order_customer_return_guard` (One-shot Guard cho Buyer API)
Khóa nghiệp vụ first-write-wins cho `POST /v1/orders/{orderId}/return`. Mỗi order chỉ có 1 bản ghi guard cho submit đầu tiên từ Buyer API.

```sql
CREATE TABLE order_customer_return_guard (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    first_request_id UUID NOT NULL,
    first_idempotency_key VARCHAR(255) NOT NULL,
    first_refund_type VARCHAR(20) NOT NULL
        CHECK (first_refund_type IN ('FULL', 'PARTIAL')),
    first_reason_code VARCHAR(100) NULL,
    first_trigger_source VARCHAR(30) NOT NULL DEFAULT 'API_RETURN'
        CHECK (first_trigger_source = 'API_RETURN'),
    first_request_payload_hash VARCHAR(128) NULL,
    first_requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    policy_version VARCHAR(40) NOT NULL DEFAULT 'CUSTOMER_RETURN_ONCE_V1'
);

CREATE INDEX idx_order_customer_return_guard_user_created
    ON order_customer_return_guard (user_id, first_requested_at DESC);
```

**Quy tắc bắt buộc:**
- Guard này **chỉ áp dụng** cho trigger Buyer API (`REQUEST_RETURN`).
- Trigger vận hành (`REQUEST_ADMIN_REFUND`) không ghi vào bảng này.
- Nếu đã tồn tại `order_id` trong bảng guard, mọi request Buyer API mới phải trả `409 REFUND_CUSTOMER_REQUEST_ALREADY_USED`.
- Request Buyer API chỉ được chấp nhận khi toàn bộ order item đã ở trạng thái `DELIVERED`.
- Không được xóa/reset guard khi refund kết thúc ở `REFUND_FAILED` hoặc `REFUND_REVERSED`.

### 3.3.2. `order_refund_cases` (Single Refund Transaction Case)
Control plane cho policy B: mỗi order chỉ có một refund transaction do Order khởi tạo, với refund scope lock theo request đầu vào và gate Inventory-confirm trước khi gọi Payment refund.

```sql
CREATE TABLE order_refund_cases (
    order_id BIGINT PRIMARY KEY,
    workflow_id VARCHAR(255) NOT NULL,
    trigger_source VARCHAR(40) NOT NULL,
    case_status VARCHAR(30) NOT NULL
        CHECK (case_status IN ('INITIATED', 'WAITING_INVENTORY_RETURN_CONFIRMATION', 'WAITING_PAYMENT_REFUND_RESULT', 'SUCCEEDED', 'FAILED', 'REVERSED')),
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

**Quy tắc bắt buộc:**
- `order_id` chỉ có tối đa 1 bản ghi case đang active.
- Case chỉ được tạo từ `API_RETURN` hoặc `REQUEST_ADMIN_REFUND`; `SHIPMENT_RETURNED` không mở case/workflow refund.
- Sau khi lock scope, workflow phải ở trạng thái `WAITING_INVENTORY_RETURN_CONFIRMATION` cho đến khi nhận event Inventory xác nhận đã nhận đủ item thuộc refund scope.
- Chỉ sau khi có `inventory_return_confirmed_at` mới được tạo `refund_transaction_id` và chuyển `WAITING_PAYMENT_REFUND_RESULT`.
- Khi case đã terminal (`SUCCEEDED|FAILED|REVERSED`), không được mở refund transaction thứ hai cho cùng `order_id`.

**Migration/backfill gợi ý:**
- Backfill từ lịch sử request cũ có `action_type = REQUEST_RETURN` (theo thời điểm sớm nhất mỗi `order_id`) vào `order_customer_return_guard`.
- Dùng `ON CONFLICT (order_id) DO NOTHING` để bảo toàn first-write-wins khi chạy nhiều đợt.

### 3.4. `order_status_history` (Audit Trail FSM)
Ghi log mọi transition trạng thái của Master Order và Item để phục vụ điều tra sự cố, compliance và đối soát.
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT (Index)
*   `order_item_id`: BIGINT NULL (Index, nullable khi transition ở cấp Master Order)
*   `old_status`: VARCHAR(50)
*   `new_status`: VARCHAR(50)
*   `changed_by_type`: VARCHAR(20) (`USER`, `ADMIN`, `SYSTEM`, `TEMPORAL`)
*   `reason`: VARCHAR(255) NULL
*   `metadata`: JSONB NULL (provider response, signal payload, extra context)
*   `created_at`: TIMESTAMPTZ

### 3.5. `cqrs_projection_checkpoints` (Projection Lag & Replay Offset)
Lưu offset/checkpoint theo từng projector lane để giám sát lag, phục vụ replay có kiểm soát và tránh reconsume mù.

*   `projector_name`: VARCHAR(100) PRIMARY KEY (VD: `order-cqrs-summary-projector`, `order-cqrs-line-projector`, `order-cqrs-retry-replayer`)
*   `topic_name`: VARCHAR(120) NOT NULL
*   `partition_id`: INT NOT NULL
*   `last_committed_offset`: BIGINT NOT NULL
*   `last_processed_event_id`: UUID NULL
*   `last_processed_at`: TIMESTAMPTZ NOT NULL
*   `updated_at`: TIMESTAMPTZ NOT NULL

### 3.6. `cqrs_reindex_runs` (Zero-downtime Reindex Control Plane)
Theo dõi lifecycle các lần reindex `orders_read_vN` và `order_search_lines_vN` để rollback an toàn.

*   `id`: UUID PRIMARY KEY
*   `run_name`: VARCHAR(120) NOT NULL
*   `source_index_version`: VARCHAR(30) NOT NULL
*   `target_index_version`: VARCHAR(30) NOT NULL
*   `phase`: VARCHAR(30) NOT NULL (`INIT`, `BACKFILL`, `CATCHUP`, `VALIDATE`, `CUTOVER`, `ROLLED_BACK`, `DONE`, `FAILED`)
*   `watermark_topic`: VARCHAR(120) NOT NULL DEFAULT 't.order.events'
*   `watermark_partition_offsets`: JSONB NOT NULL
*   `validation_summary`: JSONB NULL
*   `started_at`: TIMESTAMPTZ NOT NULL
*   `finished_at`: TIMESTAMPTZ NULL
*   `created_by`: VARCHAR(120) NOT NULL

**Indexes đề xuất:**
```sql
CREATE INDEX idx_cqrs_projection_checkpoints_updated_at
    ON cqrs_projection_checkpoints (updated_at DESC);

CREATE INDEX idx_cqrs_reindex_runs_phase_started
    ON cqrs_reindex_runs (phase, started_at DESC);
```

### 3.7. Archive Control Plane (Cold Data Routing & Replay)

#### `archived_orders_lookup`
Bảng chỉ mục định tuyến đọc lạnh khi API không còn tìm thấy đơn trong DB nóng.

```sql
CREATE TABLE archived_orders_lookup (
    order_id BIGINT PRIMARY KEY,
    archived BOOLEAN NOT NULL DEFAULT true,
    archive_batch_id VARCHAR(100) NOT NULL,
    s3_file_key VARCHAR(512) NOT NULL,
    order_created_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_archived_orders_lookup_batch
    ON archived_orders_lookup (archive_batch_id);

CREATE INDEX idx_archived_orders_lookup_created_at
    ON archived_orders_lookup (order_created_at DESC);
```

#### `archive_batches`
Bảng điều khiển lifecycle của từng lô archive/replay.
*   `archive_batch_id`: VARCHAR(100) PRIMARY KEY
*   `status`: VARCHAR(30) NOT NULL (`PENDING`, `RUNNING`, `FAILED`, `COMPLETED`, `REPLAYING`)
*   `attempt_count`: INT NOT NULL DEFAULT 0
*   `source_window_from`: TIMESTAMPTZ NOT NULL
*   `source_window_to`: TIMESTAMPTZ NOT NULL
*   `failure_stage`: VARCHAR(50) NULL (`SNAPSHOT`, `UPLOAD`, `VERIFY`, `DELETE`)
*   `failure_reason`: TEXT NULL
*   `started_at`: TIMESTAMPTZ NULL
*   `finished_at`: TIMESTAMPTZ NULL
*   `created_at`: TIMESTAMPTZ NOT NULL
*   `updated_at`: TIMESTAMPTZ NOT NULL

#### `archive_batch_items`
Bảng membership của batch để replay theo `batch_id` mà không cần nhét toàn bộ `order_id` vào Kafka event.
*   `id`: BIGSERIAL PRIMARY KEY
*   `archive_batch_id`: VARCHAR(100) NOT NULL (FK ảo tới `archive_batches`)
*   `order_id`: BIGINT NOT NULL
*   `source_order_updated_at`: TIMESTAMPTZ NOT NULL
*   `archive_status`: VARCHAR(20) NOT NULL (`PENDING`, `ARCHIVED`, `DELETED`, `FAILED`)
*   `s3_file_key`: VARCHAR(512) NULL
*   `archived_at`: TIMESTAMPTZ NULL
*   `deleted_at`: TIMESTAMPTZ NULL

**Unique Index (`archive_batch_id`, `order_id`)**: chống duplicate membership trong cùng một lô.

### 3.8. `order_product_metrics` (Aggregate sold metrics theo sản phẩm)

Bảng aggregate nội bộ cho worker `order-metrics-projector`, phục vụ phát `ORDER_PRODUCT_METRICS_UPDATED` theo `product_id`.

```sql
CREATE TABLE order_product_metrics (
    product_id VARCHAR(255) PRIMARY KEY,
    order_count BIGINT NOT NULL DEFAULT 0,
    sold_quantity BIGINT NOT NULL DEFAULT 0,
    event_sequence_id BIGINT NOT NULL DEFAULT 0,
    last_order_completed_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_product_metrics_updated_at
    ON order_product_metrics (updated_at DESC);
```

**Quy tắc cập nhật:**
- Chỉ tăng từ stream `ORDER_COMPLETED` đã qua idempotency guard theo `event_id`.
- `event_sequence_id` phải đơn điệu theo `product_id` để downstream Search guard out-of-order.

---
## 4. Kiến trúc Mở rộng (Scalability & Performance)

Để đảm bảo hệ thống Order Service có thể xử lý lượng tải khổng lồ (Read-Heavy) và lưu trữ lâu dài mà không suy giảm hiệu năng RDBMS, hai kỹ thuật chuyên sâu được áp dụng:

### 4.1. Tìm kiếm và Lọc với CQRS & Elasticsearch
Mọi API phục vụ việc tìm kiếm văn bản (Full-text) hoặc lọc đa chiều (Multiple filters) không được thi hành trên PostgreSQL. Dữ liệu đơn hàng được phi chuẩn hóa và đồng bộ liên tục sang Elasticsearch theo kiến trúc **hybrid 2-index**:

*   `orders_read_vN`: 1 document/order, tối ưu cho lọc chính xác và phân trang ổn định.
*   `order_search_lines_vN`: 1 document/item (gắn `order_item_id`), tối ưu cho text search theo tên sản phẩm, tracking number, store name.

Chiến lược query: filter-first trên `orders_read`; text query chạy trên `order_search_lines` rồi hydrate summary từ `orders_read`.

Contract hiển thị list buyer:
- List luôn trả order-level card (1 card/1 order).
- Card fields (`primary_item_name`, `primary_store_name`, `primary_tracking_number`, `remaining_item_count`) được derive từ snapshot DB + event stream.
- Tab buyer chuẩn hóa thành 6 nhóm (`TO_PAY`, `TO_SHIP`, `TO_RECEIVE`, `COMPLETED`, `CANCELLED`, `RETURN_REFUND`).
- Cửa sổ query mặc định là 90 ngày gần nhất nếu không truyền `from/to`.

📖 *Chi tiết kiến trúc:* Xem file `docs/order/cqrs_elasticsearch_design.md`

### 4.2. Lưu trữ Dữ liệu Lạnh (Cold Data Archiving)
Bảng `orders` được giữ ở mức tinh gọn (Lean) nhờ cơ chế Job chạy nền. Đơn hàng đạt trạng thái `COMPLETED/CANCELLED/REFUNDED` và cũ hơn 6-12 tháng sẽ được tự động đóng gói (Archive) chuyển qua Cold Storage (ví dụ S3 + Tiered Read API) và xóa hẳn khỏi Database Bán Hàng chính.

Định tuyến đọc lạnh sử dụng `archived_orders_lookup` theo mô hình hot-first:
- Bước 1: API đọc hot DB.
- Bước 2: Nếu miss, tra `archived_orders_lookup` theo `order_id`.
- Bước 3: Nếu hit, lấy `s3_file_key` để đọc object lạnh.

Replay batch lỗi dùng `archive_batches` + `archive_batch_items` và event nhẹ chứa `archive_batch_id` (không gửi danh sách `order_id` trong payload Kafka).
📖 *Chi tiết quy trình:* Xem file `docs/order/cold_data_archiving_design.md`
