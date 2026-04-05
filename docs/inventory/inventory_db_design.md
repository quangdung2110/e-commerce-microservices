# Inventory Service - Database & Cache Schema Design

Tài liệu này mô tả thiết kế PostgreSQL và các ràng buộc dữ liệu lõi cho Inventory Service sau khi chuẩn hóa contract liên service.

---

## 1. PostgreSQL Schema (Dữ liệu lõi)

### 1.1. `warehouses`

* `id`: BIGSERIAL PRIMARY KEY
* `owner_type`: VARCHAR(50) (`PLATFORM`, `SELLER`)
* `owner_id`: BIGINT
* `name`: VARCHAR(255)
* `address`, `latitude`, `longitude`
* `city_code`, `district_code`, `ward_code`

### 1.2. `sku_product_mapping`

Bảng mapping local để Inventory phát `product_id` chính xác trong `INVENTORY_QUANTITY_CHANGED` mà không phụ thuộc call runtime sang Product.

* `sku_id`: VARCHAR(64) PRIMARY KEY
* `product_id`: VARCHAR(64) NOT NULL
* `source_version`: BIGINT NOT NULL
* `updated_at`: TIMESTAMPTZ NOT NULL

### 1.3. `physical_inventory`

Nguồn commit nóng theo kho.

* `id`: BIGSERIAL PRIMARY KEY
* `sku_id`: VARCHAR(64) NOT NULL
* `product_id`: VARCHAR(64) NOT NULL
* `warehouse_id`: BIGINT NOT NULL
* `owner_id`: BIGINT NOT NULL
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
* `owner_id`: BIGINT NOT NULL
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
* `parcel_id`: BIGINT NULL
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
* `INDEX (parcel_id)`
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
* `VENDOR_RESTOCK`
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

### 1.10. `inventory_idempotency`

Dedup xử lý ở worker pipeline.

* `event_id`: UUID PRIMARY KEY
* `action_type`: VARCHAR(50) NOT NULL
* `processed_at`: TIMESTAMPTZ NOT NULL

### 1.11. `outbox_events`

Transactional outbox cho event phụ thuộc DB transaction (`INVENTORY_QUANTITY_CHANGED`).

* `id`: UUID PRIMARY KEY
* `aggregate_type`: VARCHAR(100) NOT NULL
* `aggregate_id`: VARCHAR(255) NOT NULL
* `event_type`: VARCHAR(100) NOT NULL
* `payload`: JSONB NOT NULL
* `status`: VARCHAR(20) NOT NULL DEFAULT 'PENDING' (`PENDING`, `PUBLISHED`, `FAILED`, `DEAD`)
* `retry_count`: INT NOT NULL DEFAULT 0
* `max_retries`: INT NOT NULL DEFAULT 5
* `next_retry_at`: TIMESTAMPTZ NULL
* `published_at`: TIMESTAMPTZ NULL
* `error_message`: TEXT NULL
* `created_at`: TIMESTAMPTZ NOT NULL

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

1. Ledger Worker update DB trong transaction.
2. Cùng transaction insert `outbox_events`.
3. Outbox Polling Worker publish sang Kafka.

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

## 5. Retention và vận hành

1. `suspended_events` giữ nóng 7 ngày, sau đó archive.
2. `dlq_events` giữ tối thiểu 30 ngày.
3. `outbox_events` trạng thái `PUBLISHED` được dọn theo batch định kỳ.
4. Chỉ số vận hành và alert nằm ở [inventory_observability_slo.md](inventory_observability_slo.md).