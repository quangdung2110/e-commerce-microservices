# Module 02: Database Schema & Migrations

> **Depends on:** M01 (Project Foundation)
> **Depended by:** M05 (Repository Layer), M08 (Outbox & CDC), M10 (Routing Engine), M12 (Admin Services), M14 (Cleanup Job)

---

## 1. Goal

Định nghĩa toàn bộ PostgreSQL schema cho Inventory Service thông qua Flyway migrations:

- 5 bảng: `warehouses`, `physical_inventory`, `inventory_reservations`, `inventory_ledger`, `inventory_outbox`
- Tất cả indexes, constraints, partial unique index cho `INVENTORY_RETURN_RECEIVED_ALL`
- Seed script cho dev environment (admin warehouse mẫu)

> Tham chiếu: `inventory_db_design.md` §1.

---

## 2. Flyway Migration Files

```
src/main/resources/db/migration/
├── V1__init_schema.sql
├── V2__seed_dev_warehouse.sql
└── V3__partial_unique_outbox_return_received.sql   # tách riêng cho rõ ràng
```

> Versioning tuần tự. Mỗi V* là một migration bất khả phân (all-or-nothing).

---

## 3. V1__init_schema.sql

### 3.1 Extension bắt buộc

```sql
-- Bật UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Bật pg_stat_statements cho performance monitoring
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";
```

### 3.2 Bảng `warehouses`

```sql
CREATE TABLE warehouses (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    address         VARCHAR(512),
    city_code       VARCHAR(20),
    district_code   VARCHAR(20),
    ward_code       VARCHAR(20),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE warehouses IS
    'Warehouse master data. Managed by Admin. Used as FK target for physical_inventory and routing.';
COMMENT ON COLUMN warehouses.city_code IS
    'Province-level code (e.g. HCM, HN). Compared with customer_location.city_code for same-province routing rule.';
COMMENT ON COLUMN warehouses.is_active IS
    'Soft-disable. Inactive warehouses are excluded from routing candidates and admin operations.';

CREATE INDEX idx_warehouses_city_active
    ON warehouses (city_code, is_active)
    WHERE is_active = TRUE;
```

> Index `(city_code, is_active)` cho routing candidate query (filter by `city_code` nếu cần refine theo tỉnh).

### 3.3 Bảng `physical_inventory`

```sql
CREATE TABLE physical_inventory (
    id                  BIGSERIAL    PRIMARY KEY,
    sku_id              VARCHAR(64)  NOT NULL,
    product_id          VARCHAR(64)  NOT NULL,
    warehouse_id        BIGINT       NOT NULL,
    actual_quantity     INT          NOT NULL,
    reserved_quantity   INT          NOT NULL DEFAULT 0,
    available_quantity  INT          GENERATED ALWAYS AS (actual_quantity - reserved_quantity) STORED,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_physical_inventory_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
    CONSTRAINT uq_physical_inventory_sku_warehouse
        UNIQUE (sku_id, warehouse_id),
    CONSTRAINT ck_physical_inventory_actual_non_negative
        CHECK (actual_quantity >= 0),
    CONSTRAINT ck_physical_inventory_reserved_non_negative
        CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_physical_inventory_actual_gte_reserved
        CHECK (actual_quantity >= reserved_quantity)
);

COMMENT ON TABLE physical_inventory IS
    'Hot-path commit source. One row per (sku, warehouse). available_quantity is GENERATED — never write directly.';
COMMENT ON COLUMN physical_inventory.product_id IS
    'Denormalized from first restock. Used by ReserveInventoryActivity and INVENTORY_STOCK_CHANGED payload to avoid runtime lookup.';
COMMENT ON COLUMN physical_inventory.available_quantity IS
    'GENERATED ALWAYS AS STORED — derived from actual_quantity - reserved_quantity. Do not INSERT/UPDATE this column.';

-- Index cho aggregate query per-SKU
CREATE INDEX idx_physical_inventory_sku_available
    ON physical_inventory (sku_id, warehouse_id)
    WHERE available_quantity > 0;

-- Index cho warehouse-based lookup (debug, admin)
CREATE INDEX idx_physical_inventory_warehouse
    ON physical_inventory (warehouse_id);
```

> Partial index `WHERE available_quantity > 0` tối ưu cho routing candidate query và Lua-script-driven stock lookup.
>
> `available_quantity` là **GENERATED ALWAYS AS STORED** — JPA phải `@Generated(GenerationTime.ALWAYS)` và `insertable=false, updatable=false` (xem M05).

### 3.4 Bảng `inventory_reservations`

```sql
CREATE TABLE inventory_reservations (
    id                  BIGSERIAL    PRIMARY KEY,
    order_id            BIGINT       NOT NULL,
    order_item_id       BIGINT       NOT NULL,
    sku_id              VARCHAR(64)  NOT NULL,
    product_id          VARCHAR(64)  NOT NULL,
    warehouse_id        BIGINT       NOT NULL,
    quantity            INT          NOT NULL,
    route_plan_id       UUID         NOT NULL,
    status              VARCHAR(50)  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    refund_expected     BOOLEAN      NOT NULL DEFAULT FALSE,
    refund_request_id   VARCHAR(64),
    refund_returned_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_inventory_reservations_quantity_positive
        CHECK (quantity > 0),
    CONSTRAINT ck_inventory_reservations_status_enum
        CHECK (status IN ('RESERVED', 'DEDUCTED', 'CANCELLED'))
);

COMMENT ON TABLE inventory_reservations IS
    'Per-item reservation tracker. Also gates INVENTORY_RETURN_RECEIVED_ALL via refund_expected + refund_returned_at.';
COMMENT ON COLUMN inventory_reservations.expires_at IS
    'Reservation TTL (default 30 min from ReserveInventoryActivity). Order Temporal Saga owns the timer; Inventory does NOT scan expiry.';
COMMENT ON COLUMN inventory_reservations.status IS
    'RESERVED (pre-payment) / DEDUCTED (post-payment) / CANCELLED (compensated or expired).';

CREATE INDEX idx_inventory_reservations_order
    ON inventory_reservations (order_id);

CREATE INDEX idx_inventory_reservations_order_item
    ON inventory_reservations (order_item_id);

-- Index cho debug query expiry (KHÔNG trigger scan)
CREATE INDEX idx_inventory_reservations_status_expiry
    ON inventory_reservations (status, expires_at)
    WHERE status = 'RESERVED';

-- Partial index cho refund completion check
CREATE INDEX idx_inventory_reservations_refund_completion
    ON inventory_reservations (order_id, refund_request_id)
    WHERE refund_expected = TRUE;

-- Partial unique: ngăn 2 reservation cùng (order, sku, warehouse) ở RESERVED
CREATE UNIQUE INDEX uq_inventory_reservations_active
    ON inventory_reservations (order_id, sku_id, warehouse_id)
    WHERE status = 'RESERVED';
```

> **Idempotency:** Hot path do Redis Lua SETNX (30 ngày TTL, xem M06). Cold path do partial UNIQUE `(order_id, sku_id, warehouse_id) WHERE status='RESERVED'` ở trên — reservation là 1 row duy nhất cho cả lifecycle `(RESERVED → DEDUCTED|CANCELLED)`. Ledger cold path dùng `inventory_ledger.event_id` UNIQUE (xem §3.5).

### 3.5 Bảng `inventory_ledger`

```sql
CREATE TABLE inventory_ledger (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            UUID         NOT NULL,
    sku_id              VARCHAR(64)  NOT NULL,
    product_id          VARCHAR(64)  NOT NULL,
    warehouse_id        BIGINT       NOT NULL,
    reference_type      VARCHAR(50)  NOT NULL,
    quantity_change     INT          NOT NULL,
    balance_after       INT          NOT NULL,
    correlation_id      VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_inventory_ledger_event_id
        UNIQUE (event_id),
    CONSTRAINT ck_inventory_ledger_reference_type_enum
        CHECK (reference_type IN (
            'ORDER_RESERVED',
            'ORDER_DEDUCTED',
            'ORDER_CANCELLED',
            'RESERVATION_EXPIRED',
            'ADMIN_RESTOCK',
            'ORDER_RETURNED_RESTOCK'
        ))
);

COMMENT ON TABLE inventory_ledger IS
    'Append-only audit trail. quantity_change tracks actual_quantity (NOT reserved_quantity). Ledger is never UPDATE/DELETE.';
COMMENT ON COLUMN inventory_ledger.quantity_change IS
    'Delta on actual_quantity only. Reserved_quantity bookkeeping lives in inventory_reservations.';
COMMENT ON COLUMN inventory_ledger.balance_after IS
    'actual_quantity at (sku_id, warehouse_id) AFTER the change. NOT available_quantity.';
COMMENT ON COLUMN inventory_ledger.correlation_id IS
    'Trace origin: Temporal Workflow ID for order-driven events; X-Correlation-Id header (or generated UUID) for admin API.';

CREATE INDEX idx_inventory_ledger_sku_created
    ON inventory_ledger (sku_id, created_at DESC);

CREATE INDEX idx_inventory_ledger_warehouse_created
    ON inventory_ledger (warehouse_id, created_at DESC);
```

> ⚠️ Ledger là append-only — application code không bao giờ UPDATE/DELETE. `event_id` UNIQUE vừa là idempotency key vừa là event tracking.

### 3.6 Bảng `inventory_outbox`

```sql
CREATE TABLE inventory_outbox (
    event_id            UUID         PRIMARY KEY,
    event_type          VARCHAR(100) NOT NULL,
    schema_version      INT          NOT NULL DEFAULT 1,
    aggregate_type      VARCHAR(50)  NOT NULL,
    aggregate_id        VARCHAR(255) NOT NULL,
    partition_key       VARCHAR(255) NOT NULL,
    refund_request_id   VARCHAR(64),
    payload             JSONB        NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_inventory_outbox_schema_version_positive
        CHECK (schema_version >= 1),
    CONSTRAINT ck_inventory_outbox_event_type_enum
        CHECK (event_type IN (
            'INVENTORY_STOCK_CHANGED',
            'INVENTORY_RETURN_RECEIVED_ALL',
            'INVENTORY_SHIP_TO_CUSTOMER'
        )),
    CONSTRAINT ck_inventory_outbox_aggregate_type
        CHECK (aggregate_type = 'Inventory')
);

COMMENT ON TABLE inventory_outbox IS
    'Transactional outbox. Inserted in same TX as the underlying DB write. Debezium CDC reads WAL → Kafka.';
COMMENT ON COLUMN inventory_outbox.partition_key IS
    'Kafka message key — extracted by Debezium connector (NOT parsed from JSON). sku_id for STOCK_CHANGED, order_id for RETURN_RECEIVED_ALL and SHIP_TO_CUSTOMER.';
COMMENT ON COLUMN inventory_outbox.refund_request_id IS
    'Only set for INVENTORY_RETURN_RECEIVED_ALL. Stored as column (not in JSONB) so partial unique index works efficiently.';

CREATE INDEX idx_inventory_outbox_created_at
    ON inventory_outbox (created_at);

CREATE INDEX idx_inventory_outbox_event_type_created
    ON inventory_outbox (event_type, created_at);
```

> ⚠️ `partition_key` KHÔNG được để Debezium tự suy từ JSON payload. Connect dùng SMT (Single Message Transform) để trích cột này.

### 3.7 V3: Partial Unique cho INVENTORY_RETURN_RECEIVED_ALL

```sql
-- Đảm bảo mỗi (order_id, refund_request_id) chỉ emit RETURN_RECEIVED_ALL đúng 1 lần.
-- Tránh race condition khi 2 transaction đồng thời cùng thấy pending_count = 0.
CREATE UNIQUE INDEX uq_inventory_outbox_return_received
    ON inventory_outbox (aggregate_id, refund_request_id)
    WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL';
```

> `aggregate_id` = `order_id` cho `INVENTORY_RETURN_RECEIVED_ALL` (xem M07).

---

## 4. V2__seed_dev_warehouse.sql

Chỉ chạy trong profile dev:

```sql
-- Dev seed: 3 warehouses mẫu ở HCM và HN
INSERT INTO warehouses (name, address, city_code, district_code, ward_code, is_active) VALUES
  ('HCM Central Warehouse', '12 Nguyễn Huệ, Q1, TP.HCM', 'HCM', 'Q1', 'Bến Nghé', TRUE),
  ('HCM District 7 Warehouse', '88 Phú Mỹ Hưng, Q7, TP.HCM', 'HCM', 'Q7', 'Tân Phú', TRUE),
  ('HN Long Biên Warehouse', '5 Nguyễn Văn Cừ, Long Biên, Hà Nội', 'HN', 'Long Biên', 'Ngọc Lâm', TRUE);
```

> Migration này không nên chạy trong prod. Dùng Flyway profile hoặc conditional script để gate theo Spring profile.

---

## 5. Index Strategy Summary

| Index | Mục đích | Query pattern |
|:---|:---|:---|
| `uq_physical_inventory_sku_warehouse` (UNIQUE) | Stock lookup + routing aggregate | `WHERE sku_id = ANY(:skus) AND available_quantity > 0` |
| `idx_physical_inventory_sku_available` (partial) | Routing candidate | Same query, partial index nhanh hơn |
| `idx_warehouses_city_active` (partial) | Routing same-province | `WHERE city_code = :cc AND is_active = TRUE` |
| `idx_inventory_reservations_order` | Order saga lookup | `WHERE order_id = :oid` |
| `idx_inventory_reservations_refund_completion` (partial) | Refund check | `WHERE order_id = :oid AND refund_request_id = :rrid AND refund_expected = TRUE` |
| `uq_inventory_reservations_active` (partial UNIQUE) | Ngăn duplicate RESERVED | Conditional insert |
| `uq_inventory_ledger_event_id` (UNIQUE) | Idempotent ledger write | `WHERE event_id = :eid` |
| `idx_inventory_ledger_sku_created` | SKU history | `WHERE sku_id = :sid ORDER BY created_at DESC` |
| `uq_inventory_outbox_return_received` (partial UNIQUE) | Chống duplicate return signal | Conditional insert |
| `idx_inventory_outbox_created_at` | Cleanup job | `WHERE created_at < :watermark` |

---

## 6. Constraints cheatsheet

| Bảng | Constraint | Logic |
|:---|:---|:---|
| `physical_inventory` | `CHECK (actual_quantity >= 0)` | Stock không âm |
| `physical_inventory` | `CHECK (reserved_quantity >= 0)` | Reserved không âm |
| `physical_inventory` | `CHECK (actual_quantity >= reserved_quantity)` | Invariant 1: stock vật lý ≥ đã giữ |
| `inventory_reservations` | `CHECK (status IN ('RESERVED','DEDUCTED','CANCELLED'))` | Enum |
| `inventory_reservations` | Partial UNIQUE `(order_id, sku_id, warehouse_id) WHERE status='RESERVED'` | Chống 2 RESERVED trùng cho cùng SKU-kho-đơn |
| `inventory_ledger` | `UNIQUE (event_id)` | Idempotent append |
| `inventory_ledger` | `CHECK (reference_type IN (...))` | Enum, chống typo |
| `inventory_outbox` | `CHECK (event_type IN (...))` | Enum, chống typo |
| `inventory_outbox` | Partial UNIQUE `(aggregate_id, refund_request_id) WHERE event_type='INVENTORY_RETURN_RECEIVED_ALL'` | Idempotent refund signal |

---

## 7. PostgreSQL Settings (khuyến nghị production)

Trong `postgresql.conf` hoặc Helm values:

```ini
# WAL settings cho Debezium CDC
wal_level = logical
max_wal_senders = 4
max_replication_slots = 4
wal_keep_size = '1GB'

# Performance
shared_buffers = '4GB'
effective_cache_size = '12GB'
work_mem = '64MB'
maintenance_work_mem = '512MB'
random_page_cost = 1.1  # SSD
effective_io_concurrency = 200

# Connection pool (HikariCP max=50)
max_connections = 200
```

> `wal_level = logical` bắt buộc cho Debezium.

---

## 8. Verification

| Check | Cách verify |
|:---|:---|
| Flyway apply thành công | `./mvnw spring-boot:run` → log `Successfully applied X migrations` |
| Schema khớp design | `psql -c "\d+" inventory_service` (mỗi bảng) — so sánh với `inventory_db_design.md` |
| Constraints active | Insert row vi phạm CHECK → bị reject |
| Generated column | `\d physical_inventory` → `available_quantity` marked `GENERATED ALWAYS AS STORED` |
| Partial indexes | `\di+` → thấy các index `WHERE` clause |
| Seed data | Dev profile: `SELECT COUNT(*) FROM warehouses` → 3 |

---

## 9. Files Created

```
src/main/resources/db/migration/
├── V1__init_schema.sql
├── V2__seed_dev_warehouse.sql
└── V3__partial_unique_outbox_return_received.sql
```

> Không tạo JPA entity trong module này — chỉ SQL. JPA mapping ở M05.