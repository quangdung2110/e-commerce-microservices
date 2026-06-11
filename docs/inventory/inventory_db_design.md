# Inventory Service - Database & Cache Schema Design

Tài liệu này mô tả thiết kế PostgreSQL và các ràng buộc dữ liệu lõi cho Inventory Service sau khi chuẩn hóa contract liên service.

---

## 1. PostgreSQL Schema (Dữ liệu lõi)

### 1.1. `warehouses`

Lưu trữ thông tin kho hàng do Admin quản lý.

* `id`: BIGSERIAL PRIMARY KEY
* `name`: VARCHAR(255)
* `address`, `latitude`, `longitude`
* `city_code`, `district_code`, `ward_code`
* `is_active`: BOOLEAN DEFAULT true
* `created_at`, `updated_at`: TIMESTAMPTZ

### 1.2. `sku_product_mapping`

Bảng mapping local để Inventory phát `product_id` chính xác trong `INVENTORY_QUANTITY_CHANGED` mà không phụ thuộc call runtime sang Product.

* `sku_id`: VARCHAR(64) PRIMARY KEY
* `product_id`: VARCHAR(64) NOT NULL
* `source_version`: BIGINT NOT NULL
* `updated_at`: TIMESTAMPTZ NOT NULL

**Ownership:** cập nhật bởi Product Mapping Sync Worker (consume `t.product.events`) và chỉ dùng để enrich event/outbox của Inventory.

### 1.3. `physical_inventory`

Nguồn commit nóng theo kho.

* `id`: BIGSERIAL PRIMARY KEY
* `sku_id`: VARCHAR(64) NOT NULL
* `product_id`: VARCHAR(64) NOT NULL
* `warehouse_id`: BIGINT NOT NULL
* `actual_quantity`: INT NOT NULL
* `reserved_quantity`: INT NOT NULL DEFAULT 0
* `available_quantity`: INT GENERATED ALWAYS AS (actual_quantity - reserved_quantity) STORED
* `updated_at`: TIMESTAMPTZ NOT NULL

**Constraints:**
* `UNIQUE (sku_id, warehouse_id)`
* `CHECK (actual_quantity >= 0)`
* `CHECK (reserved_quantity >= 0)`
* `CHECK (actual_quantity >= reserved_quantity)`

### 1.4. `logical_inventory`

Aggregate read model theo SKU.

* `id`: BIGSERIAL PRIMARY KEY
* `sku_id`: VARCHAR(64) UNIQUE NOT NULL
* `product_id`: VARCHAR(64) NOT NULL
* `total_quantity`: INT NOT NULL
* `reserved_quantity`: INT NOT NULL
* `available_quantity`: INT GENERATED ALWAYS AS (total_quantity - reserved_quantity) STORED
* `version`: BIGINT NOT NULL
* `updated_at`: TIMESTAMPTZ NOT NULL

**Constraints:**
* `CHECK (total_quantity >= 0)`
* `CHECK (reserved_quantity >= 0)`
* `CHECK (total_quantity >= reserved_quantity)`

### 1.5. `inventory_reservations`

Theo dõi reservation theo item + warehouse.

* `id`: BIGSERIAL PRIMARY KEY
* `order_id`: BIGINT NOT NULL
* `order_item_id`: BIGINT NULL
* `sku_id`: VARCHAR(64) NOT NULL
* `product_id`: VARCHAR(64) NOT NULL
* `warehouse_id`: BIGINT NOT NULL
* `quantity`: INT NOT NULL
* `route_plan_id`: UUID NOT NULL
* `route_version`: INT NOT NULL
* `status`: VARCHAR(50) NOT NULL (`RESERVED`, `DEDUCTED`, `CANCELLED`)
* `idempotency_key`: VARCHAR(255) NOT NULL
* `expires_at`: TIMESTAMPTZ NOT NULL
* `created_at`: TIMESTAMPTZ NOT NULL
* `updated_at`: TIMESTAMPTZ NOT NULL

**Indexes / constraints:**
* `INDEX (order_id)`
* `INDEX (order_item_id)`
* `INDEX (status, expires_at)`
* `UNIQUE (idempotency_key)`
* `UNIQUE (order_id, sku_id, warehouse_id, status) WHERE status = 'RESERVED'`

### 1.6. `inventory_ledger`

Append-only audit trail.

* `id`: BIGSERIAL PRIMARY KEY
* `event_id`: UUID NOT NULL
* `sku_id`: VARCHAR(64) NOT NULL
* `product_id`: VARCHAR(64) NOT NULL
* `warehouse_id`: BIGINT NOT NULL
* `reference_type`: VARCHAR(50) NOT NULL
* `quantity_change`: INT NOT NULL
* `balance_after`: INT NOT NULL
* `correlation_id`: VARCHAR(128) NOT NULL
* `created_at`: TIMESTAMPTZ NOT NULL

`reference_type` hợp lệ:
* `ORDER_RESERVED`
* `ORDER_DEDUCTED`
* `ORDER_CANCELLED`
* `RESERVATION_EXPIRED`
* `ADMIN_RESTOCK`
* `ORDER_RETURNED_RESTOCK`
* `ADMIN_ADJUSTMENT`

**Indexes / constraints:**
* `UNIQUE (event_id)`
* `INDEX (sku_id, created_at)`
* `INDEX (warehouse_id, created_at)`

### 1.7. `suspended_sku_locks`

Khóa SKU bền vững khi gặp poison pill.

* `sku_id`: VARCHAR(64) PRIMARY KEY
* `lock_reason`: TEXT NOT NULL
* `status`: VARCHAR(20) NOT NULL (`SUSPENDED`, `UNLOCKED`)
* `locked_at`: TIMESTAMPTZ NOT NULL
* `unlocked_at`: TIMESTAMPTZ NULL

### 1.8. `suspended_events`

Parking lot cho event đi sau SKU đang bị suspend.

* `id`: BIGSERIAL PRIMARY KEY
* `sku_id`: VARCHAR(64) NOT NULL
* `event_id`: UUID NOT NULL
* `event_payload`: JSONB NOT NULL
* `sequence_order`: BIGINT NOT NULL
* `status`: VARCHAR(20) NOT NULL (`PARKED`, `RECOVERED`, `DEAD`)
* `attempt_count`: INT NOT NULL DEFAULT 0
* `last_error`: TEXT NULL
* `parked_at`: TIMESTAMPTZ NOT NULL
* `recovered_at`: TIMESTAMPTZ NULL

**Indexes:**
* `INDEX (status, parked_at)`
* `INDEX (sku_id, status)`

### 1.9. `dlq_events`

Danh sách event lỗi cần xử lý thủ công hoặc replay.

* `id`: BIGSERIAL PRIMARY KEY
* `event_id`: UUID NOT NULL
* `sku_id`: VARCHAR(64) NOT NULL
* `event_type`: VARCHAR(64) NOT NULL
* `event_payload`: JSONB NOT NULL
* `error_message`: TEXT NOT NULL
* `status`: VARCHAR(20) NOT NULL (`UNRESOLVED`, `RESOLVED`, `ESCALATED`)
* `created_at`: TIMESTAMPTZ NOT NULL
* `resolved_at`: TIMESTAMPTZ NULL
* `resolved_by`: VARCHAR(128) NULL

**Indexes:**
* `UNIQUE (event_id)`
* `INDEX (sku_id, status)`

### 1.10. `inventory_event_idempotency`

Đảm bảo xử lý Idempotent vững chắc theo nguyên tắc Kép (Redis SETNX chặn ngoài, Database Unique chặn trong). Mọi Event xử lý từ nguồn ngoài như Kafka / Temporal đều ghi vào đây.

* `event_id`: VARCHAR(255) PRIMARY KEY (ID của message/event gốc nhận từ Kafka hoặc `request_id` từ Temporal)
* `order_id`: BIGINT INDEX (Để dễ dàng đối soát luồng trả kho theo Order)
* `action_type`: VARCHAR(50) NOT NULL (VD: `PROCESS_SHIPMENT_RETURN`)
* `action_scope_key`: VARCHAR(100) NOT NULL (Khoá phạm vi xử lý, VD: `refund_request_id` hoặc trạng thái hoàn tiền cụ thể để giới hạn một lượt process)
* `payload_hash`: VARCHAR(128) NULL (Checksum đối chiếu payload tránh thao tác replay bị lệch logic)
* `processed_at`: TIMESTAMPTZ NOT NULL
* **Unique Constraint (`order_id`, `action_type`, `action_scope_key`)**: Ràng buộc này đảm bảo một Request Scope (`refund_request_id`) chỉ được thực thi đúng 1 lần cho cùng 1 đơn hàng (order_id) với cùng 1 mục đích (action_type).

### 1.10.1. `inventory_refund_scopes`

Bảng theo dõi scope hoàn hàng theo từng `refund_request_id`, dùng để gate signal `INVENTORY_RETURN_RECEIVED_ALL` về Order.

* `id`: BIGSERIAL PRIMARY KEY
* `order_id`: BIGINT NOT NULL
* `refund_request_id`: VARCHAR(64) NOT NULL
* `required_order_item_ids`: JSONB NOT NULL
* `received_order_item_ids`: JSONB NOT NULL DEFAULT '[]'::jsonb
* `status`: VARCHAR(32) NOT NULL (`OPEN`, `COMPLETED`, `MANUAL_OVERRIDE`)
* `created_at`: TIMESTAMPTZ NOT NULL
* `updated_at`: TIMESTAMPTZ NOT NULL
* `closed_at`: TIMESTAMPTZ NULL

**Indexes / constraints:**
* `UNIQUE (order_id, refund_request_id)`
* `INDEX (status, updated_at)`
* `CHECK (jsonb_typeof(required_order_item_ids) = 'array')`
* `CHECK (jsonb_typeof(received_order_item_ids) = 'array')`

> Event `INVENTORY_RETURN_RECEIVED_ALL` chỉ được insert outbox đúng 1 lần khi scope chuyển `OPEN -> COMPLETED` (hoặc `MANUAL_OVERRIDE`).

### 1.11. `inventory_outbox`

Transactional Outbox pattern dùng chung với Debezium CDC Connector để phát event `INVENTORY_QUANTITY_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL` (Tuyệt đối không dùng Worker Polling).

* `event_id`: UUID PRIMARY KEY (Định danh duy nhất sự kiện)
* `schema_version`: INT DEFAULT 1 (Quản lý version schema top-level)
* `aggregate_type`: VARCHAR(50) NOT NULL (VD: `Inventory`)
* `aggregate_id`: VARCHAR(255) NOT NULL (ID đối tượng tác động, VD: `sku_id` hoặc `order_id`)
* `event_type`: VARCHAR(100) NOT NULL (Loại sự kiện)
* `partition_key`: VARCHAR(255) NOT NULL (Topic Kafka partition key. Không được để CDC tự suy từ JSON)
* `payload`: JSONB NOT NULL (Nội dung Event gốc)
* `created_at`: TIMESTAMPTZ NOT NULL

> [!IMPORTANT]
> **Append-Only cho Debezium CDC:** Bảng outbox đã loại bỏ hoàn toàn các cột phục vụ polling (`status`, `retry_count`, `next_retry_at`, v.v...). Tiến trình Retry, Dead Letter Queue được uỷ quyền và điều phối toàn bộ tại Kafka Connect runtime. Trấn áp hoàn toàn Dual-Write Problem mà không tốn CPU của DB.

---

## 2. Redis Caching Schema

Chi tiết key, script và cross-slot constraints nằm ở [inventory_redis_design.md](inventory_redis_design.md).

---

## 3. Kiến trúc Event Streaming

### 3.1. Luồng `INVENTORY_RESERVED/DEDUCTED/RELEASED`

1. Lua script mutate Redis stock hash.
2. Lua script `XADD` event vào Redis Stream Outbox cùng slot.
3. Redis Stream Relay Worker forward sang Kafka `t.inventory.events`.
4. Kafka Ledger Worker ghi `inventory_ledger` + update `inventory_reservations`.

### 3.2. Luồng `INVENTORY_QUANTITY_CHANGED`

1. Ledger Worker update DB `inventory_ledger` và `logical_inventory` trong PostgreSQL transaction.
2. Cùng transaction insert vào bảng append-only `inventory_outbox`.
3. Nhờ cơ chế CDC (Debezium), Kafka Connect đọc Write-Ahead Log (WAL) và sinh Kafka Message không độ trễ, hoàn toàn Offload được sức tải của PostgreSQL.

---

## 4. Batching Ledger & Poison Handling

### 4.1. Ledger batching

1. Worker `poll(100ms)`.
2. Group by `sku_id`.
3. Skip SKU đang `SUSPENDED` và park event vào `suspended_events`.
4. Fetch mốc balance để tính tuyến tính trong RAM.
5. Bulk insert ledger + update reservation status.

### 4.2. Poison pill strategy

1. Batch fail -> chia nhỏ bằng binary split để xác định event lỗi nhanh hơn one-by-one toàn bộ batch.
2. Event lỗi vào `dlq_events`, SKU bị lock ở `suspended_sku_locks`.
3. Event đi sau cùng SKU vào `suspended_events`.
4. Recovery Worker replay khi DLQ đã `RESOLVED`.

---

## 5. Retention và Vận hành

1. `suspended_events` giữ nóng 7 ngày, sau đó archive.
2. `dlq_events` giữ tối thiểu 30 ngày.
3. Bảng `inventory_outbox` áp dụng Time-Window Cleanup Cronjob: Background xóa rác an toàn theo Timestamp cộng Watermark offset của Debezium.
4. Chỉ số vận hành và alert nằm ở [inventory_observability_slo.md](inventory_observability_slo.md).