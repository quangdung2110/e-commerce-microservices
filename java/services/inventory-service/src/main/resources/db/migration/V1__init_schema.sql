-- =============================================
-- V1__init_schema.sql
-- Initial database schema for inventory-service
-- 5 tables: warehouses, physical_inventory, inventory_reservations,
--           inventory_ledger, inventory_outbox
-- =============================================

-- =========================
-- Extensions
-- =========================

-- Bật UUID generation (pgcrypto cung cấp gen_random_uuid()).
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Bật pg_stat_statements cho performance monitoring.
-- Lưu ý: extension này yêu cầu shared_preload_libraries có 'pg_stat_statements'
-- trong postgresql.conf của môi trường production.
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";


-- =========================
-- Table: warehouses
-- =========================
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


-- =========================
-- Table: physical_inventory
-- =========================
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

-- Partial index tối ưu cho routing candidate và Lua-script-driven stock lookup.
CREATE INDEX idx_physical_inventory_sku_available
    ON physical_inventory (sku_id, warehouse_id)
    WHERE available_quantity > 0;

-- Index cho warehouse-based lookup (debug, admin).
CREATE INDEX idx_physical_inventory_warehouse
    ON physical_inventory (warehouse_id);


-- =========================
-- Table: inventory_reservations
-- =========================
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

    CONSTRAINT fk_inventory_reservations_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
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

-- Index cho debug query expiry (KHÔNG trigger scan).
CREATE INDEX idx_inventory_reservations_status_expiry
    ON inventory_reservations (status, expires_at)
    WHERE status = 'RESERVED';

-- Partial index cho refund completion check.
CREATE INDEX idx_inventory_reservations_refund_completion
    ON inventory_reservations (order_id, refund_request_id)
    WHERE refund_expected = TRUE;

-- Partial unique: ngăn 2 reservation cùng (order, sku, warehouse) ở RESERVED.
CREATE UNIQUE INDEX uq_inventory_reservations_active
    ON inventory_reservations (order_id, sku_id, warehouse_id)
    WHERE status = 'RESERVED';


-- =========================
-- Table: inventory_ledger
-- =========================
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

    CONSTRAINT fk_inventory_ledger_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
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


-- =========================
-- Table: inventory_outbox
-- =========================
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
