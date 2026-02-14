# Group 4: Fulfillment & Financial Services

> **Services**: Inventory Service, Shipping Service, Payment Service, Refund Service
> **Pattern**: Event Sourcing + CQRS + Saga (Temporal)
> **Database**: PostgreSQL

---

## Table of Contents
1. [Inventory Service](#1-inventory-service)
2. [Shipping Service](#2-shipping-service)
3. [Payment Service](#3-payment-service)
4. [Refund Service](#4-refund-service)
5. [Saga Workflows](#5-saga-workflows)
6. [Kafka Topics & Redis Caching](#6-kafka-topics--redis-caching)

---

## 1. Inventory Service

### 1.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `virtual_stock` concept | HIGH | Add for multi-warehouse aggregation |
| 2 | No `safety_stock` threshold per SKU | HIGH | Add `inventory_settings` table |
| 3 | Missing reservation expiry | HIGH | Add `expires_at` to reservations |
| 4 | No warehouse priority for fulfillment | MEDIUM | Add `fulfillment_priority` |
| 5 | Missing `low_stock_threshold` per SKU | MEDIUM | Add to settings |
| 6 | No backorder support | MEDIUM | Add `allow_backorder`, `backorder_limit` |
| 7 | Missing `reserved_by_type` | MEDIUM | Track reservation source (ORDER, CART, HOLD) |
| 8 | No multi-location inventory transfer | MEDIUM | Add `inventory_transfers` table |
| 9 | Missing `cost_price` per location | LOW | Add for FIFO/LIFO accounting |
| 10 | No batch/lot tracking | LOW | Add `lot_number`, `expiry_date` for perishables |

### 1.2 Database Schema

#### Table: `warehouses`
```sql
CREATE TABLE warehouses (
    warehouse_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Basic info
    code                VARCHAR(20) NOT NULL UNIQUE,
    name                VARCHAR(200) NOT NULL,
    
    -- Type
    warehouse_type      VARCHAR(20) NOT NULL CHECK (
        warehouse_type IN ('OWNED', 'SELLER', 'THIRD_PARTY', 'DROPSHIP')
    ),
    
    -- Owner (for seller warehouses)
    seller_id           UUID,
    
    -- Location
    address_line1       VARCHAR(500) NOT NULL,
    address_line2       VARCHAR(500),
    district            VARCHAR(100),
    city                VARCHAR(100) NOT NULL,
    province            VARCHAR(100) NOT NULL,
    postal_code         VARCHAR(20),
    country_code        VARCHAR(2) NOT NULL DEFAULT 'VN',
    
    -- Coordinates (for shipping calculation)
    latitude            DECIMAL(10, 8),
    longitude           DECIMAL(11, 8),
    
    -- Contact
    contact_name        VARCHAR(200),
    contact_phone       VARCHAR(20),
    contact_email       VARCHAR(255),
    
    -- Operations
    fulfillment_priority INTEGER NOT NULL DEFAULT 0,  -- Higher = preferred
    operating_hours     JSONB,  -- {"mon": {"open": "08:00", "close": "18:00"}, ...}
    cutoff_time         TIME,  -- Orders before this time ship same day
    
    -- Capabilities
    supports_cod        BOOLEAN NOT NULL DEFAULT true,
    supports_returns    BOOLEAN NOT NULL DEFAULT true,
    supports_perishable BOOLEAN NOT NULL DEFAULT false,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_warehouses_seller ON warehouses(seller_id) WHERE seller_id IS NOT NULL;
CREATE INDEX idx_warehouses_active ON warehouses(is_active, fulfillment_priority DESC);
CREATE INDEX idx_warehouses_location ON warehouses USING gist (
    ll_to_earth(latitude, longitude)
) WHERE latitude IS NOT NULL;
```

#### Table: `inventory_stocks`
```sql
CREATE TABLE inventory_stocks (
    stock_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Location
    warehouse_id        UUID NOT NULL REFERENCES warehouses(warehouse_id),
    
    -- Product
    sku_id              UUID NOT NULL,
    product_id          UUID NOT NULL,
    seller_id           UUID NOT NULL,
    
    -- Quantities
    quantity_on_hand    INTEGER NOT NULL DEFAULT 0,
    quantity_reserved   INTEGER NOT NULL DEFAULT 0,
    quantity_available  INTEGER GENERATED ALWAYS AS (quantity_on_hand - quantity_reserved) STORED,
    quantity_incoming   INTEGER NOT NULL DEFAULT 0,  -- From pending transfers/POs
    
    -- Virtual stock (aggregated)
    virtual_stock       INTEGER NOT NULL DEFAULT 0,  -- Sum across all warehouses
    
    -- Lot tracking (optional)
    lot_number          VARCHAR(50),
    expiry_date         DATE,
    manufacture_date    DATE,
    
    -- Cost
    cost_price          DECIMAL(15, 2),
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'DISCONTINUED', 'DAMAGED', 'QUARANTINE')
    ),
    
    -- Location within warehouse
    bin_location        VARCHAR(50),  -- e.g., "A1-02-03"
    
    -- Timestamps
    last_counted_at     TIMESTAMPTZ,
    last_movement_at    TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_stock_location UNIQUE (warehouse_id, sku_id, COALESCE(lot_number, ''))
);

CREATE INDEX idx_stocks_sku ON inventory_stocks(sku_id);
CREATE INDEX idx_stocks_product ON inventory_stocks(product_id);
CREATE INDEX idx_stocks_warehouse ON inventory_stocks(warehouse_id);
CREATE INDEX idx_stocks_seller ON inventory_stocks(seller_id);
CREATE INDEX idx_stocks_low ON inventory_stocks(quantity_available) 
    WHERE status = 'ACTIVE';
CREATE INDEX idx_stocks_expiry ON inventory_stocks(expiry_date) 
    WHERE expiry_date IS NOT NULL;
```

#### Table: `inventory_settings`
```sql
-- Per-SKU settings (can vary by warehouse)
CREATE TABLE inventory_settings (
    setting_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Scope
    sku_id              UUID NOT NULL,
    warehouse_id        UUID,  -- NULL = all warehouses
    
    -- Thresholds
    safety_stock        INTEGER NOT NULL DEFAULT 0,
    low_stock_threshold INTEGER NOT NULL DEFAULT 10,
    reorder_point       INTEGER NOT NULL DEFAULT 20,
    reorder_quantity    INTEGER NOT NULL DEFAULT 50,
    max_stock           INTEGER,
    
    -- Backorder
    allow_backorder     BOOLEAN NOT NULL DEFAULT false,
    backorder_limit     INTEGER,
    
    -- Lead time
    lead_time_days      INTEGER NOT NULL DEFAULT 7,
    
    -- Alerts
    alert_enabled       BOOLEAN NOT NULL DEFAULT true,
    alert_email         VARCHAR(255),
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_sku_warehouse_setting UNIQUE (sku_id, COALESCE(warehouse_id, '00000000-0000-0000-0000-000000000000'::UUID))
);

CREATE INDEX idx_inv_settings_sku ON inventory_settings(sku_id);
```

#### Table: `inventory_reservations`
```sql
CREATE TABLE inventory_reservations (
    reservation_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Location
    stock_id            UUID NOT NULL REFERENCES inventory_stocks(stock_id),
    warehouse_id        UUID NOT NULL,
    
    -- Product
    sku_id              UUID NOT NULL,
    
    -- Reservation details
    quantity            INTEGER NOT NULL CHECK (quantity > 0),
    
    -- Source
    reserved_by_type    VARCHAR(20) NOT NULL CHECK (
        reserved_by_type IN ('ORDER', 'CART', 'HOLD', 'TRANSFER')
    ),
    reserved_by_id      UUID NOT NULL,  -- order_id, cart_id, etc.
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'FULFILLED')
    ),
    
    -- Expiry
    expires_at          TIMESTAMPTZ NOT NULL,
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at        TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,
    fulfilled_at        TIMESTAMPTZ,
    
    -- Audit
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_reservations_stock ON inventory_reservations(stock_id);
CREATE INDEX idx_reservations_order ON inventory_reservations(reserved_by_id) 
    WHERE reserved_by_type = 'ORDER';
CREATE INDEX idx_reservations_expires ON inventory_reservations(expires_at) 
    WHERE status = 'PENDING';
CREATE INDEX idx_reservations_status ON inventory_reservations(status);
```

#### Table: `inventory_transfers`
```sql
-- Inter-warehouse transfers
CREATE TABLE inventory_transfers (
    transfer_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Transfer reference
    transfer_number     VARCHAR(30) NOT NULL UNIQUE,
    
    -- Locations
    source_warehouse_id UUID NOT NULL REFERENCES warehouses(warehouse_id),
    dest_warehouse_id   UUID NOT NULL REFERENCES warehouses(warehouse_id),
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (
        status IN ('DRAFT', 'PENDING', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')
    ),
    
    -- Shipping
    shipped_at          TIMESTAMPTZ,
    received_at         TIMESTAMPTZ,
    tracking_number     VARCHAR(100),
    
    -- Notes
    notes               TEXT,
    
    -- Audit
    created_by          UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT chk_different_warehouses CHECK (source_warehouse_id != dest_warehouse_id)
);

CREATE INDEX idx_transfers_source ON inventory_transfers(source_warehouse_id);
CREATE INDEX idx_transfers_dest ON inventory_transfers(dest_warehouse_id);
CREATE INDEX idx_transfers_status ON inventory_transfers(status);
```

#### Table: `inventory_transfer_items`
```sql
CREATE TABLE inventory_transfer_items (
    item_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id         UUID NOT NULL REFERENCES inventory_transfers(transfer_id),
    
    -- Product
    sku_id              UUID NOT NULL,
    
    -- Quantities
    quantity_requested  INTEGER NOT NULL,
    quantity_shipped    INTEGER,
    quantity_received   INTEGER,
    
    -- Lot tracking
    lot_number          VARCHAR(50),
    
    -- Notes
    notes               TEXT
);

CREATE INDEX idx_transfer_items_transfer ON inventory_transfer_items(transfer_id);
```

#### Table: `inventory_logs`
```sql
-- Audit trail for all inventory movements
CREATE TABLE inventory_logs (
    log_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Stock reference
    stock_id            UUID NOT NULL REFERENCES inventory_stocks(stock_id),
    sku_id              UUID NOT NULL,
    warehouse_id        UUID NOT NULL,
    
    -- Movement type
    movement_type       VARCHAR(30) NOT NULL CHECK (
        movement_type IN (
            'RECEIVE',      -- Stock received
            'SHIP',         -- Shipped to customer
            'RETURN',       -- Customer return
            'TRANSFER_OUT', -- Transfer to another warehouse
            'TRANSFER_IN',  -- Transfer from another warehouse
            'ADJUSTMENT',   -- Manual adjustment
            'DAMAGE',       -- Damaged goods
            'RESERVE',      -- Reserved for order
            'RELEASE',      -- Release reservation
            'COUNT'         -- Physical count adjustment
        )
    ),
    
    -- Quantities
    quantity_change     INTEGER NOT NULL,  -- Can be negative
    quantity_before     INTEGER NOT NULL,
    quantity_after      INTEGER NOT NULL,
    
    -- Reference
    reference_type      VARCHAR(30),  -- ORDER, TRANSFER, ADJUSTMENT, etc.
    reference_id        UUID,
    
    -- Cost tracking
    unit_cost           DECIMAL(15, 2),
    
    -- Context
    reason              TEXT,
    performed_by        UUID,
    
    -- Timestamp
    logged_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inv_logs_stock ON inventory_logs(stock_id, logged_at DESC);
CREATE INDEX idx_inv_logs_sku ON inventory_logs(sku_id, logged_at DESC);
CREATE INDEX idx_inv_logs_ref ON inventory_logs(reference_type, reference_id);
CREATE INDEX idx_inv_logs_time ON inventory_logs(logged_at DESC);
```

### 1.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class InventoryEvent extends BaseEvent {
    protected String warehouseId;
    protected String skuId;
}

@DomainEvent(aggregateType = "Inventory")
public class StockReceivedEvent extends InventoryEvent {
    private int quantity;
    private String referenceType;  // PO, RETURN, TRANSFER
    private String referenceId;
    private BigDecimal unitCost;
    private String lotNumber;
}

@DomainEvent(aggregateType = "Inventory")
public class StockReservedEvent extends InventoryEvent {
    private String reservationId;
    private int quantity;
    private String reservedByType;
    private String reservedById;
    private Instant expiresAt;
}

@DomainEvent(aggregateType = "Inventory")
public class ReservationConfirmedEvent extends InventoryEvent {
    private String reservationId;
}

@DomainEvent(aggregateType = "Inventory")
public class ReservationReleasedEvent extends InventoryEvent {
    private String reservationId;
    private int quantity;
    private String reason;
}

@DomainEvent(aggregateType = "Inventory")
public class StockShippedEvent extends InventoryEvent {
    private String orderId;
    private int quantity;
    private String trackingNumber;
}

@DomainEvent(aggregateType = "Inventory")
public class StockAdjustedEvent extends InventoryEvent {
    private int quantityChange;
    private String reason;
    private String adjustedBy;
}

@DomainEvent(aggregateType = "Inventory")
public class LowStockAlertEvent extends InventoryEvent {
    private int currentQuantity;
    private int threshold;
}
```

---

## 2. Shipping Service

### 2.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `shipping_methods` table | HIGH | Add for carrier/method options |
| 2 | No `estimated_delivery` calculation | HIGH | Add date range fields |
| 3 | Missing `shipment_items` tracking | MEDIUM | Track items per shipment |
| 4 | No `shipping_zones` for rate calculation | MEDIUM | Add zones and rates |
| 5 | Missing `label_url` for shipping label | MEDIUM | Add label storage |
| 6 | No multi-package support | MEDIUM | Add `packages` array |
| 7 | Missing `insurance` fields | MEDIUM | Add insurance options |
| 8 | No `return_shipment_id` link | MEDIUM | Link return shipments |
| 9 | Missing webhook delivery status | LOW | Add `webhook_delivery_logs` |
| 10 | No `proof_of_delivery` storage | LOW | Add POD fields |

### 2.2 Database Schema

#### Table: `shipping_methods`
```sql
CREATE TABLE shipping_methods (
    method_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Carrier info
    carrier_code        VARCHAR(50) NOT NULL,  -- ghn, ghtk, viettel_post, etc.
    carrier_name        VARCHAR(200) NOT NULL,
    
    -- Method info
    method_code         VARCHAR(50) NOT NULL,
    method_name         VARCHAR(200) NOT NULL,
    description         TEXT,
    
    -- Delivery time
    min_delivery_days   INTEGER NOT NULL,
    max_delivery_days   INTEGER NOT NULL,
    
    -- Pricing
    base_rate           DECIMAL(15, 2) NOT NULL,
    rate_per_kg         DECIMAL(15, 2),
    rate_per_km         DECIMAL(15, 2),
    
    -- Limits
    max_weight_kg       DECIMAL(10, 2),
    max_length_cm       DECIMAL(10, 2),
    max_width_cm        DECIMAL(10, 2),
    max_height_cm       DECIMAL(10, 2),
    max_value           DECIMAL(15, 2),
    
    -- Capabilities
    supports_cod        BOOLEAN NOT NULL DEFAULT true,
    supports_insurance  BOOLEAN NOT NULL DEFAULT true,
    supports_tracking   BOOLEAN NOT NULL DEFAULT true,
    supports_pickup     BOOLEAN NOT NULL DEFAULT true,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_carrier_method UNIQUE (carrier_code, method_code)
);

CREATE INDEX idx_shipping_methods_carrier ON shipping_methods(carrier_code);
CREATE INDEX idx_shipping_methods_active ON shipping_methods(is_active);
```

#### Table: `shipping_zones`
```sql
CREATE TABLE shipping_zones (
    zone_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Zone info
    zone_code           VARCHAR(20) NOT NULL UNIQUE,
    zone_name           VARCHAR(200) NOT NULL,
    
    -- Geographic definition
    provinces           VARCHAR(100)[],  -- List of province codes
    districts           VARCHAR(100)[],  -- List of district codes
    postal_codes        VARCHAR(20)[],   -- List of postal codes
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_zones_provinces ON shipping_zones USING gin(provinces);
```

#### Table: `shipping_rates`
```sql
CREATE TABLE shipping_rates (
    rate_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Method & Zone
    method_id           UUID NOT NULL REFERENCES shipping_methods(method_id),
    from_zone_id        UUID NOT NULL REFERENCES shipping_zones(zone_id),
    to_zone_id          UUID NOT NULL REFERENCES shipping_zones(zone_id),
    
    -- Weight-based pricing
    min_weight_kg       DECIMAL(10, 2) NOT NULL DEFAULT 0,
    max_weight_kg       DECIMAL(10, 2) NOT NULL,
    
    -- Pricing
    base_rate           DECIMAL(15, 2) NOT NULL,
    additional_rate     DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- Per additional kg
    
    -- Validity
    valid_from          DATE NOT NULL,
    valid_until         DATE,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    CONSTRAINT uq_shipping_rate UNIQUE (method_id, from_zone_id, to_zone_id, min_weight_kg, valid_from)
);

CREATE INDEX idx_rates_method_zones ON shipping_rates(method_id, from_zone_id, to_zone_id);
```

#### Table: `shipments`
```sql
CREATE TABLE shipments (
    shipment_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Reference
    shipment_number     VARCHAR(30) NOT NULL UNIQUE,
    order_id            UUID NOT NULL,
    sub_order_id        UUID NOT NULL,
    
    -- Type
    shipment_type       VARCHAR(20) NOT NULL DEFAULT 'FORWARD' CHECK (
        shipment_type IN ('FORWARD', 'RETURN', 'EXCHANGE')
    ),
    return_shipment_id  UUID REFERENCES shipments(shipment_id),  -- Link for returns
    
    -- Carrier
    shipping_method_id  UUID NOT NULL REFERENCES shipping_methods(method_id),
    tracking_number     VARCHAR(100),
    carrier_tracking_url TEXT,
    
    -- Origin
    origin_warehouse_id UUID NOT NULL,
    pickup_address      JSONB NOT NULL,
    
    -- Destination
    delivery_address    JSONB NOT NULL,
    /*
    {
        "recipient_name": "Nguyễn Văn A",
        "phone": "0901234567",
        "address_line1": "123 Đường ABC",
        "district": "Quận 1",
        "city": "TP.HCM",
        "postal_code": "700000",
        "coordinates": {"lat": 10.762622, "lng": 106.660172}
    }
    */
    
    -- Status
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',           -- Waiting for pickup
            'LABEL_CREATED',     -- Label generated
            'PICKED_UP',         -- Collected from warehouse
            'IN_TRANSIT',        -- On the way
            'OUT_FOR_DELIVERY',  -- With delivery person
            'DELIVERED',         -- Successfully delivered
            'FAILED_ATTEMPT',    -- Delivery attempt failed
            'RETURNED_TO_SENDER',-- Returned
            'CANCELLED'          -- Cancelled
        )
    ),
    
    -- Delivery estimates
    estimated_pickup_at     TIMESTAMPTZ,
    estimated_delivery_from TIMESTAMPTZ,
    estimated_delivery_to   TIMESTAMPTZ,
    
    -- Actual timestamps
    label_created_at    TIMESTAMPTZ,
    picked_up_at        TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    
    -- Delivery details
    delivery_attempts   INTEGER NOT NULL DEFAULT 0,
    signed_by           VARCHAR(200),
    proof_of_delivery   JSONB,  -- {"image_url": "...", "signature_url": "..."}
    
    -- Packages
    packages            JSONB NOT NULL DEFAULT '[]',
    /*
    [
        {
            "package_number": "PKG-001",
            "weight_kg": 1.5,
            "length_cm": 30,
            "width_cm": 20,
            "height_cm": 10,
            "items": ["item-1", "item-2"]
        }
    ]
    */
    
    -- Weight & dimensions (total)
    total_weight_kg     DECIMAL(10, 3) NOT NULL,
    
    -- Pricing
    shipping_cost       DECIMAL(15, 2) NOT NULL,
    insurance_cost      DECIMAL(15, 2) NOT NULL DEFAULT 0,
    cod_amount          DECIMAL(15, 2) NOT NULL DEFAULT 0,
    total_cost          DECIMAL(15, 2) NOT NULL,
    
    -- Insurance
    is_insured          BOOLEAN NOT NULL DEFAULT false,
    insured_value       DECIMAL(15, 2),
    
    -- COD
    is_cod              BOOLEAN NOT NULL DEFAULT false,
    cod_collected       BOOLEAN NOT NULL DEFAULT false,
    cod_collected_at    TIMESTAMPTZ,
    
    -- Label
    label_url           TEXT,
    label_format        VARCHAR(10),  -- PDF, ZPL, PNG
    
    -- Notes
    special_instructions TEXT,
    internal_notes      TEXT,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_shipments_order ON shipments(order_id);
CREATE INDEX idx_shipments_suborder ON shipments(sub_order_id);
CREATE INDEX idx_shipments_tracking ON shipments(tracking_number) WHERE tracking_number IS NOT NULL;
CREATE INDEX idx_shipments_status ON shipments(status, created_at DESC);
CREATE INDEX idx_shipments_carrier ON shipments(shipping_method_id);
```

#### Table: `shipment_items`
```sql
CREATE TABLE shipment_items (
    shipment_item_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         UUID NOT NULL REFERENCES shipments(shipment_id),
    
    -- Item reference
    order_item_id       UUID NOT NULL,
    sku_id              UUID NOT NULL,
    
    -- Quantity
    quantity            INTEGER NOT NULL,
    
    -- Package assignment
    package_number      VARCHAR(50)
);

CREATE INDEX idx_shipment_items_shipment ON shipment_items(shipment_id);
CREATE INDEX idx_shipment_items_order_item ON shipment_items(order_item_id);
```

#### Table: `shipping_tracking`
```sql
CREATE TABLE shipping_tracking (
    tracking_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id         UUID NOT NULL REFERENCES shipments(shipment_id),
    
    -- Tracking info
    status_code         VARCHAR(50) NOT NULL,
    status_description  VARCHAR(500) NOT NULL,
    
    -- Location
    location            VARCHAR(500),
    location_code       VARCHAR(50),
    
    -- Timestamp from carrier
    event_at            TIMESTAMPTZ NOT NULL,
    
    -- Raw data from carrier
    carrier_raw_data    JSONB,
    
    -- When we received this update
    received_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tracking_shipment ON shipping_tracking(shipment_id, event_at DESC);
```

#### Table: `webhook_delivery_logs`
```sql
CREATE TABLE webhook_delivery_logs (
    log_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Webhook info
    webhook_type        VARCHAR(50) NOT NULL,  -- tracking_update, delivery_confirmed, etc.
    carrier_code        VARCHAR(50) NOT NULL,
    
    -- Request
    request_url         TEXT,
    request_headers     JSONB,
    request_body        JSONB,
    
    -- Response
    response_status     INTEGER,
    response_body       JSONB,
    
    -- Processing
    processed           BOOLEAN NOT NULL DEFAULT false,
    process_error       TEXT,
    
    -- Timing
    received_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ
);

CREATE INDEX idx_webhook_logs_type ON webhook_delivery_logs(webhook_type, received_at DESC);
CREATE INDEX idx_webhook_logs_unprocessed ON webhook_delivery_logs(processed) WHERE processed = false;
```

### 2.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class ShipmentEvent extends BaseEvent {
    protected String shipmentId;
    protected String orderId;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentCreatedEvent extends ShipmentEvent {
    private String shipmentNumber;
    private String shippingMethodId;
    private String warehouseId;
    private AddressDto deliveryAddress;
    private List<ShipmentItemDto> items;
    private BigDecimal shippingCost;
}

@DomainEvent(aggregateType = "Shipment")
public class ShippingLabelCreatedEvent extends ShipmentEvent {
    private String trackingNumber;
    private String labelUrl;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentPickedUpEvent extends ShipmentEvent {
    private Instant pickedUpAt;
    private String pickedUpBy;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentInTransitEvent extends ShipmentEvent {
    private String currentLocation;
    private String nextHub;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentOutForDeliveryEvent extends ShipmentEvent {
    private String deliveryPerson;
    private String deliveryPersonPhone;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentDeliveredEvent extends ShipmentEvent {
    private Instant deliveredAt;
    private String signedBy;
    private String proofOfDeliveryUrl;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentDeliveryFailedEvent extends ShipmentEvent {
    private int attemptNumber;
    private String failureReason;
    private Instant nextAttemptScheduled;
}

@DomainEvent(aggregateType = "Shipment")
public class ShipmentReturnedEvent extends ShipmentEvent {
    private String returnReason;
    private String returnWarehouseId;
}
```

---

## 3. Payment Service

### 3.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `platform_wallet` for P2P | HIGH | Add platform and seller wallets |
| 2 | No `settlement` process | HIGH | Add settlement tables |
| 3 | Missing `commission_rules` | HIGH | Add commission calculation |
| 4 | No `refund` separate from payment | HIGH | Split to Refund Service |
| 5 | Missing `payment_attempts` tracking | MEDIUM | Add for retry logic |
| 6 | No `idempotency_key` | MEDIUM | Prevent duplicate payments |
| 7 | Missing `split_payments` | MEDIUM | Support multiple payment methods |
| 8 | No `escrow` tracking | MEDIUM | Add escrow fields |
| 9 | Missing `currency_conversion` | LOW | Add for multi-currency |
| 10 | No `payout_schedule` | LOW | Add seller payout scheduling |
| 11 | Missing `bank_account` verification | MEDIUM | Add verification status |

### 3.2 Database Schema

#### Table: `payment_methods`
```sql
CREATE TABLE payment_methods (
    method_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    
    -- Type
    method_type         VARCHAR(30) NOT NULL CHECK (
        method_type IN ('CREDIT_CARD', 'DEBIT_CARD', 'BANK_TRANSFER', 'E_WALLET', 'COD')
    ),
    
    -- Provider
    provider_code       VARCHAR(50) NOT NULL,  -- visa, mastercard, momo, vnpay, etc.
    
    -- Card details (encrypted reference)
    card_token          VARCHAR(500),  -- Tokenized by payment gateway
    card_last_four      VARCHAR(4),
    card_brand          VARCHAR(20),
    card_exp_month      SMALLINT,
    card_exp_year       SMALLINT,
    
    -- Bank details
    bank_code           VARCHAR(20),
    bank_account_number_masked VARCHAR(20),
    
    -- E-wallet
    wallet_id           VARCHAR(100),
    
    -- Display
    display_name        VARCHAR(200),
    
    -- Default
    is_default          BOOLEAN NOT NULL DEFAULT false,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    verified_at         TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_methods_user ON payment_methods(user_id, is_active);
CREATE UNIQUE INDEX idx_payment_methods_default ON payment_methods(user_id) 
    WHERE is_default = true AND is_active = true;
```

#### Table: `payments`
```sql
CREATE TABLE payments (
    payment_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Reference
    order_id            UUID NOT NULL,
    user_id             UUID NOT NULL,
    
    -- Idempotency
    idempotency_key     VARCHAR(100) NOT NULL UNIQUE,
    
    -- Amount
    amount              DECIMAL(15, 2) NOT NULL,
    currency_code       VARCHAR(3) NOT NULL DEFAULT 'VND',
    
    -- Payment method
    payment_method_id   UUID REFERENCES payment_methods(method_id),
    payment_method_type VARCHAR(30) NOT NULL,
    
    -- Gateway
    gateway_code        VARCHAR(50) NOT NULL,  -- stripe, vnpay, momo, etc.
    gateway_transaction_id VARCHAR(200),
    gateway_response    JSONB,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',      -- Awaiting payment
            'PROCESSING',   -- Being processed
            'AUTHORIZED',   -- Authorized but not captured
            'CAPTURED',     -- Successfully captured
            'FAILED',       -- Failed
            'CANCELLED',    -- Cancelled by user
            'REFUNDED',     -- Fully refunded
            'PARTIALLY_REFUNDED'  -- Partially refunded
        )
    ),
    
    -- Escrow
    is_escrowed         BOOLEAN NOT NULL DEFAULT true,
    escrow_released_at  TIMESTAMPTZ,
    
    -- Refund tracking
    refunded_amount     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    
    -- Timestamps
    authorized_at       TIMESTAMPTZ,
    captured_at         TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    
    -- Error info
    failure_code        VARCHAR(50),
    failure_message     TEXT,
    
    -- Metadata
    metadata            JSONB,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_user ON payments(user_id, created_at DESC);
CREATE INDEX idx_payments_gateway ON payments(gateway_code, gateway_transaction_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_escrow ON payments(is_escrowed, escrow_released_at) 
    WHERE is_escrowed = true AND escrow_released_at IS NULL;
```

#### Table: `payment_attempts`
```sql
CREATE TABLE payment_attempts (
    attempt_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id          UUID NOT NULL REFERENCES payments(payment_id),
    
    -- Attempt number
    attempt_number      SMALLINT NOT NULL,
    
    -- Gateway response
    gateway_request_id  VARCHAR(200),
    gateway_response    JSONB,
    
    -- Result
    status              VARCHAR(20) NOT NULL,
    error_code          VARCHAR(50),
    error_message       TEXT,
    
    -- Timestamps
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_payment_attempt UNIQUE (payment_id, attempt_number)
);

CREATE INDEX idx_payment_attempts_payment ON payment_attempts(payment_id);
```

#### Table: `platform_wallets`
```sql
-- Platform's master wallet for collecting payments
CREATE TABLE platform_wallets (
    wallet_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Wallet type
    wallet_type         VARCHAR(20) NOT NULL CHECK (
        wallet_type IN ('ESCROW', 'COMMISSION', 'OPERATING')
    ),
    
    -- Balance
    balance             DECIMAL(15, 2) NOT NULL DEFAULT 0,
    pending_balance     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    currency_code       VARCHAR(3) NOT NULL DEFAULT 'VND',
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_platform_wallet_type UNIQUE (wallet_type, currency_code)
);
```

#### Table: `seller_wallets`
```sql
CREATE TABLE seller_wallets (
    wallet_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id           UUID NOT NULL UNIQUE,
    
    -- Balance
    available_balance   DECIMAL(15, 2) NOT NULL DEFAULT 0,
    pending_balance     DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- From orders not yet settled
    reserved_balance    DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- Held for disputes
    total_earned        DECIMAL(15, 2) NOT NULL DEFAULT 0,
    total_withdrawn     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    
    -- Currency
    currency_code       VARCHAR(3) NOT NULL DEFAULT 'VND',
    
    -- Settings
    auto_withdraw       BOOLEAN NOT NULL DEFAULT false,
    auto_withdraw_threshold DECIMAL(15, 2),
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')
    ),
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_seller_wallets_seller ON seller_wallets(seller_id);
```

#### Table: `wallet_transactions`
```sql
CREATE TABLE wallet_transactions (
    transaction_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Wallet (platform or seller)
    wallet_type         VARCHAR(20) NOT NULL CHECK (wallet_type IN ('PLATFORM', 'SELLER')),
    wallet_id           UUID NOT NULL,
    
    -- Transaction type
    transaction_type    VARCHAR(30) NOT NULL CHECK (
        transaction_type IN (
            'PAYMENT_RECEIVED',   -- From customer payment
            'SETTLEMENT',         -- Settled to seller
            'COMMISSION_DEDUCTED',-- Platform commission
            'REFUND',             -- Refund to customer
            'WITHDRAWAL',         -- Seller withdrawal
            'ADJUSTMENT',         -- Manual adjustment
            'CHARGEBACK',         -- Disputed charge
            'TRANSFER_IN',        -- Transfer between wallets
            'TRANSFER_OUT'
        )
    ),
    
    -- Amount
    amount              DECIMAL(15, 2) NOT NULL,
    balance_before      DECIMAL(15, 2) NOT NULL,
    balance_after       DECIMAL(15, 2) NOT NULL,
    
    -- Reference
    reference_type      VARCHAR(30),  -- ORDER, REFUND, WITHDRAWAL, etc.
    reference_id        UUID,
    
    -- Description
    description         TEXT,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (
        status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')
    ),
    
    -- Timestamp
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wallet_txns_wallet ON wallet_transactions(wallet_type, wallet_id, created_at DESC);
CREATE INDEX idx_wallet_txns_ref ON wallet_transactions(reference_type, reference_id);
```

#### Table: `commission_rules`
```sql
CREATE TABLE commission_rules (
    rule_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Scope
    category_id         UUID,  -- NULL = all categories
    seller_id           UUID,  -- NULL = all sellers
    
    -- Commission
    commission_type     VARCHAR(20) NOT NULL CHECK (
        commission_type IN ('PERCENTAGE', 'FIXED', 'TIERED')
    ),
    commission_rate     DECIMAL(5, 4),  -- e.g., 0.05 = 5%
    commission_fixed    DECIMAL(15, 2),
    
    -- Tiered rates (JSON)
    tiered_rates        JSONB,
    /*
    [
        {"min_amount": 0, "max_amount": 1000000, "rate": 0.05},
        {"min_amount": 1000000, "max_amount": 5000000, "rate": 0.04},
        {"min_amount": 5000000, "max_amount": null, "rate": 0.03}
    ]
    */
    
    -- Priority (higher = more specific)
    priority            INTEGER NOT NULL DEFAULT 0,
    
    -- Validity
    valid_from          DATE NOT NULL,
    valid_until         DATE,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_commission_rules_category ON commission_rules(category_id);
CREATE INDEX idx_commission_rules_seller ON commission_rules(seller_id);
CREATE INDEX idx_commission_rules_active ON commission_rules(is_active, valid_from, valid_until);
```

#### Table: `settlements`
```sql
-- Batch settlement to sellers
CREATE TABLE settlements (
    settlement_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Reference
    settlement_number   VARCHAR(30) NOT NULL UNIQUE,
    seller_id           UUID NOT NULL,
    
    -- Period
    period_start        DATE NOT NULL,
    period_end          DATE NOT NULL,
    
    -- Amounts
    gross_amount        DECIMAL(15, 2) NOT NULL,  -- Total order value
    commission_amount   DECIMAL(15, 2) NOT NULL,
    shipping_subsidy    DECIMAL(15, 2) NOT NULL DEFAULT 0,
    adjustments         DECIMAL(15, 2) NOT NULL DEFAULT 0,
    refunds_deducted    DECIMAL(15, 2) NOT NULL DEFAULT 0,
    net_amount          DECIMAL(15, 2) NOT NULL,  -- Final amount to seller
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    
    -- Payout
    payout_method       VARCHAR(30),  -- BANK_TRANSFER, E_WALLET
    bank_account_id     UUID REFERENCES seller_bank_accounts(account_id),
    payout_reference    VARCHAR(200),
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    
    -- Notes
    notes               TEXT
);

CREATE INDEX idx_settlements_seller ON settlements(seller_id, created_at DESC);
CREATE INDEX idx_settlements_status ON settlements(status);
CREATE INDEX idx_settlements_period ON settlements(period_start, period_end);
```

#### Table: `settlement_items`
```sql
CREATE TABLE settlement_items (
    item_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id       UUID NOT NULL REFERENCES settlements(settlement_id),
    
    -- Order reference
    order_id            UUID NOT NULL,
    sub_order_id        UUID NOT NULL,
    
    -- Amounts
    order_amount        DECIMAL(15, 2) NOT NULL,
    commission_amount   DECIMAL(15, 2) NOT NULL,
    shipping_subsidy    DECIMAL(15, 2) NOT NULL DEFAULT 0,
    net_amount          DECIMAL(15, 2) NOT NULL,
    
    -- Order completion date
    completed_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_settlement_items_settlement ON settlement_items(settlement_id);
CREATE INDEX idx_settlement_items_order ON settlement_items(order_id);
```

#### Table: `seller_bank_accounts`
```sql
CREATE TABLE seller_bank_accounts (
    account_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id           UUID NOT NULL,
    
    -- Bank info
    bank_code           VARCHAR(20) NOT NULL,
    bank_name           VARCHAR(200) NOT NULL,
    branch_name         VARCHAR(200),
    
    -- Account info
    account_number      VARCHAR(50) NOT NULL,  -- Encrypted
    account_holder_name VARCHAR(200) NOT NULL,
    
    -- Verification
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        verification_status IN ('PENDING', 'VERIFIED', 'FAILED')
    ),
    verified_at         TIMESTAMPTZ,
    verification_notes  TEXT,
    
    -- Default
    is_default          BOOLEAN NOT NULL DEFAULT false,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_seller_bank_accounts_seller ON seller_bank_accounts(seller_id);
CREATE UNIQUE INDEX idx_seller_bank_default ON seller_bank_accounts(seller_id) 
    WHERE is_default = true AND is_active = true;
```

#### Table: `withdrawals`
```sql
CREATE TABLE withdrawals (
    withdrawal_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Seller
    seller_id           UUID NOT NULL,
    wallet_id           UUID NOT NULL REFERENCES seller_wallets(wallet_id),
    bank_account_id     UUID NOT NULL REFERENCES seller_bank_accounts(account_id),
    
    -- Amount
    amount              DECIMAL(15, 2) NOT NULL,
    fee                 DECIMAL(15, 2) NOT NULL DEFAULT 0,
    net_amount          DECIMAL(15, 2) NOT NULL,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    
    -- Bank transfer
    bank_reference      VARCHAR(200),
    
    -- Timestamps
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    
    -- Notes
    failure_reason      TEXT
);

CREATE INDEX idx_withdrawals_seller ON withdrawals(seller_id, requested_at DESC);
CREATE INDEX idx_withdrawals_status ON withdrawals(status);
```

### 3.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class PaymentEvent extends BaseEvent {
    protected String paymentId;
    protected String orderId;
    protected String userId;
}

@DomainEvent(aggregateType = "Payment")
public class PaymentInitiatedEvent extends PaymentEvent {
    private BigDecimal amount;
    private String currency;
    private String paymentMethodType;
    private String gatewayCode;
}

@DomainEvent(aggregateType = "Payment")
public class PaymentAuthorizedEvent extends PaymentEvent {
    private String gatewayTransactionId;
    private BigDecimal authorizedAmount;
}

@DomainEvent(aggregateType = "Payment")
public class PaymentCapturedEvent extends PaymentEvent {
    private BigDecimal capturedAmount;
    private Instant capturedAt;
}

@DomainEvent(aggregateType = "Payment")
public class PaymentFailedEvent extends PaymentEvent {
    private String failureCode;
    private String failureMessage;
    private int attemptNumber;
}

@DomainEvent(aggregateType = "Payment")
public class EscrowReleasedEvent extends PaymentEvent {
    private String sellerId;
    private BigDecimal amount;
    private BigDecimal commission;
    private BigDecimal netAmount;
}

// Wallet events
@DomainEvent(aggregateType = "SellerWallet")
public class WalletCreditedEvent extends BaseEvent {
    private String walletId;
    private String sellerId;
    private BigDecimal amount;
    private String referenceType;
    private String referenceId;
    private BigDecimal newBalance;
}

@DomainEvent(aggregateType = "SellerWallet")
public class WalletDebitedEvent extends BaseEvent {
    private String walletId;
    private String sellerId;
    private BigDecimal amount;
    private String referenceType;
    private String referenceId;
    private BigDecimal newBalance;
}

@DomainEvent(aggregateType = "Settlement")
public class SettlementCreatedEvent extends BaseEvent {
    private String settlementId;
    private String sellerId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal grossAmount;
    private BigDecimal netAmount;
    private List<String> orderIds;
}

@DomainEvent(aggregateType = "Settlement")
public class SettlementCompletedEvent extends BaseEvent {
    private String settlementId;
    private String payoutReference;
    private Instant completedAt;
}
```

---

## 4. Refund Service

### 4.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Refund mixed with Payment | HIGH | Create separate Refund Service |
| 2 | No `refund_items` tracking | HIGH | Track per-item refund |
| 3 | Missing `refund_evidence` | HIGH | Add evidence for disputes |
| 4 | No partial refund support | HIGH | Add quantity/amount fields per item |
| 5 | Missing return shipping tracking | MEDIUM | Link to return shipment |
| 6 | No seller dispute flow | MEDIUM | Add dispute fields |
| 7 | Missing refund reason codes | MEDIUM | Standardize reasons |
| 8 | No automatic refund rules | MEDIUM | Add refund policies |
| 9 | Missing refund deadline | LOW | Add deadline based on policy |
| 10 | No refund to different method | LOW | Support alternate refund destination |

### 4.2 Database Schema

#### Table: `refund_requests`
```sql
CREATE TABLE refund_requests (
    refund_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Reference
    refund_number       VARCHAR(30) NOT NULL UNIQUE,
    order_id            UUID NOT NULL,
    sub_order_id        UUID,  -- NULL if full order refund
    payment_id          UUID NOT NULL,
    
    -- Requester
    user_id             UUID NOT NULL,
    
    -- Type
    refund_type         VARCHAR(20) NOT NULL CHECK (
        refund_type IN ('FULL', 'PARTIAL', 'ITEM')
    ),
    
    -- Reason
    reason_code         VARCHAR(50) NOT NULL CHECK (
        reason_code IN (
            'ITEM_NOT_RECEIVED',
            'ITEM_DAMAGED',
            'ITEM_DEFECTIVE',
            'WRONG_ITEM',
            'ITEM_NOT_AS_DESCRIBED',
            'CHANGED_MIND',
            'DUPLICATE_ORDER',
            'SELLER_CANCELLED',
            'DELIVERY_DELAYED',
            'OTHER'
        )
    ),
    reason_details      TEXT,
    
    -- Amount
    requested_amount    DECIMAL(15, 2) NOT NULL,
    approved_amount     DECIMAL(15, 2),
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',          -- Awaiting review
            'UNDER_REVIEW',     -- Being reviewed
            'APPROVED',         -- Approved, pending processing
            'PROCESSING',       -- Refund being processed
            'COMPLETED',        -- Refund completed
            'REJECTED',         -- Rejected
            'CANCELLED'         -- Cancelled by user
        )
    ),
    
    -- Review
    reviewed_by         UUID,
    reviewed_at         TIMESTAMPTZ,
    review_notes        TEXT,
    rejection_reason    TEXT,
    
    -- Dispute (if seller disagrees)
    is_disputed         BOOLEAN NOT NULL DEFAULT false,
    dispute_opened_at   TIMESTAMPTZ,
    dispute_resolved_at TIMESTAMPTZ,
    dispute_resolution  VARCHAR(50),
    
    -- Return required
    requires_return     BOOLEAN NOT NULL DEFAULT false,
    return_shipment_id  UUID,
    return_received_at  TIMESTAMPTZ,
    
    -- Refund destination
    refund_to           VARCHAR(30) NOT NULL DEFAULT 'ORIGINAL' CHECK (
        refund_to IN ('ORIGINAL', 'WALLET', 'BANK_ACCOUNT')
    ),
    refund_destination_details JSONB,
    
    -- Deadline
    deadline_at         TIMESTAMPTZ,
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    
    -- Version for optimistic locking
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_refunds_order ON refund_requests(order_id);
CREATE INDEX idx_refunds_user ON refund_requests(user_id, created_at DESC);
CREATE INDEX idx_refunds_status ON refund_requests(status);
CREATE INDEX idx_refunds_payment ON refund_requests(payment_id);
CREATE INDEX idx_refunds_disputed ON refund_requests(is_disputed, dispute_opened_at) 
    WHERE is_disputed = true;
```

#### Table: `refund_items`
```sql
CREATE TABLE refund_items (
    refund_item_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id           UUID NOT NULL REFERENCES refund_requests(refund_id),
    
    -- Item reference
    order_item_id       UUID NOT NULL,
    sku_id              UUID NOT NULL,
    
    -- Quantity
    quantity_requested  INTEGER NOT NULL,
    quantity_approved   INTEGER,
    
    -- Amount
    unit_price          DECIMAL(15, 2) NOT NULL,
    refund_amount       DECIMAL(15, 2) NOT NULL,
    
    -- Reason (can differ from main)
    reason_code         VARCHAR(50),
    reason_details      TEXT,
    
    -- Return tracking
    return_condition    VARCHAR(20),  -- UNOPENED, OPENED, DAMAGED
    condition_notes     TEXT
);

CREATE INDEX idx_refund_items_refund ON refund_items(refund_id);
CREATE INDEX idx_refund_items_order_item ON refund_items(order_item_id);
```

#### Table: `refund_evidence`
```sql
CREATE TABLE refund_evidence (
    evidence_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id           UUID NOT NULL REFERENCES refund_requests(refund_id),
    
    -- Submitter
    submitted_by        UUID NOT NULL,
    submitter_type      VARCHAR(20) NOT NULL CHECK (
        submitter_type IN ('CUSTOMER', 'SELLER', 'ADMIN')
    ),
    
    -- Evidence type
    evidence_type       VARCHAR(30) NOT NULL CHECK (
        evidence_type IN ('PHOTO', 'VIDEO', 'DOCUMENT', 'SCREENSHOT', 'CHAT_LOG', 'OTHER')
    ),
    
    -- Storage
    file_url            TEXT NOT NULL,
    file_name           VARCHAR(500),
    file_size_bytes     INTEGER,
    mime_type           VARCHAR(100),
    
    -- Description
    description         TEXT,
    
    -- Timestamp
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refund_evidence_refund ON refund_evidence(refund_id);
```

#### Table: `refund_status_history`
```sql
CREATE TABLE refund_status_history (
    history_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id           UUID NOT NULL REFERENCES refund_requests(refund_id),
    
    -- Status change
    from_status         VARCHAR(20),
    to_status           VARCHAR(20) NOT NULL,
    
    -- Context
    changed_by          UUID,
    changed_by_type     VARCHAR(20) NOT NULL,  -- SYSTEM, USER, SELLER, ADMIN
    reason              TEXT,
    
    -- Timestamp
    changed_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refund_history_refund ON refund_status_history(refund_id, changed_at DESC);
```

### 4.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class RefundEvent extends BaseEvent {
    protected String refundId;
    protected String orderId;
    protected String userId;
}

@DomainEvent(aggregateType = "Refund")
public class RefundRequestedEvent extends RefundEvent {
    private String refundNumber;
    private String refundType;
    private String reasonCode;
    private BigDecimal requestedAmount;
    private List<RefundItemDto> items;
}

@DomainEvent(aggregateType = "Refund")
public class RefundApprovedEvent extends RefundEvent {
    private BigDecimal approvedAmount;
    private String approvedBy;
    private boolean requiresReturn;
}

@DomainEvent(aggregateType = "Refund")
public class RefundRejectedEvent extends RefundEvent {
    private String rejectedBy;
    private String rejectionReason;
}

@DomainEvent(aggregateType = "Refund")
public class RefundDisputeOpenedEvent extends RefundEvent {
    private String sellerId;
    private String disputeReason;
}

@DomainEvent(aggregateType = "Refund")
public class RefundDisputeResolvedEvent extends RefundEvent {
    private String resolution;  // CUSTOMER_FAVOR, SELLER_FAVOR, SPLIT
    private BigDecimal finalAmount;
    private String resolvedBy;
}

@DomainEvent(aggregateType = "Refund")
public class RefundProcessedEvent extends RefundEvent {
    private String paymentId;
    private BigDecimal refundedAmount;
    private String refundMethod;
    private String transactionReference;
}

@DomainEvent(aggregateType = "Refund")
public class RefundCompletedEvent extends RefundEvent {
    private BigDecimal totalRefunded;
    private Instant completedAt;
}
```

---

## 5. Saga Workflows

### 5.1 Order Processing Saga

```
┌────────────────────────────────────────────────────────────────────────┐
│                     Order Processing Saga (Temporal)                   │
└────────────────────────────────────────────────────────────────────────┘

                              Order Service
                                   │
                                   │ ProcessOrderCommand
                                   ▼
                    ┌──────────────────────────────┐
                    │     OrderProcessingSaga      │
                    └──────────────────────────────┘
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         │                         │                         │
         ▼                         ▼                         ▼
   ┌───────────┐            ┌───────────┐            ┌───────────┐
   │ Inventory │            │  Payment  │            │ Shipping  │
   │  Reserve  │            │  Process  │            │  Create   │
   └─────┬─────┘            └─────┬─────┘            └─────┬─────┘
         │                        │                        │
         │ ReserveStock()         │ ProcessPayment()       │ CreateShipment()
         │                        │                        │
         ▼                        ▼                        ▼
   Success/Fail            Success/Fail              Success/Fail
         │                        │                        │
         └────────────────────────┼────────────────────────┘
                                  │
                          ┌───────┴───────┐
                          │               │
                     All Success     Any Failed
                          │               │
                          ▼               ▼
                    ┌──────────┐   ┌────────────────┐
                    │ Complete │   │  Compensation  │
                    │  Order   │   │     Flow       │
                    └──────────┘   └────────────────┘
                                          │
                           ┌──────────────┼──────────────┐
                           │              │              │
                           ▼              ▼              ▼
                    Release Stock   Refund Payment   Cancel Shipment
```

### 5.2 Refund Saga

```
┌────────────────────────────────────────────────────────────────────────┐
│                         Refund Saga (Temporal)                         │
└────────────────────────────────────────────────────────────────────────┘

                             Refund Service
                                   │
                                   │ ProcessRefundCommand
                                   ▼
                    ┌──────────────────────────────┐
                    │       RefundSaga             │
                    └──────────────────────────────┘
                                   │
                                   │
         ┌─────────────────────────┴─────────────────────────┐
         │                                                   │
    Requires Return?                                   No Return
         │                                                   │
         ▼                                                   │
   ┌───────────┐                                             │
   │ Shipping  │                                             │
   │  Return   │─── Wait for return received ───┐            │
   └───────────┘                                │            │
                                                ▼            │
                                         ┌───────────┐       │
                                         │ Inventory │       │
                                         │  Restock  │       │
                                         └─────┬─────┘       │
                                               │             │
         ┌─────────────────────────────────────┴─────────────┘
         │
         ▼
   ┌───────────┐
   │  Payment  │
   │  Refund   │
   └─────┬─────┘
         │
         ▼
   ┌───────────┐
   │  Wallet   │
   │  Debit    │─── Deduct from seller wallet
   └─────┬─────┘
         │
         ▼
   ┌───────────┐
   │ Complete  │
   │  Refund   │
   └───────────┘
```

### 5.3 Settlement Saga

```
┌────────────────────────────────────────────────────────────────────────┐
│                       Settlement Saga (Scheduled)                      │
└────────────────────────────────────────────────────────────────────────┘

                          Payment Service
                                 │
                    ┌────────────┴────────────┐
                    │    Daily Scheduled      │
                    │    Settlement Job       │
                    └────────────┬────────────┘
                                 │
          For each seller with pending settlements:
                                 │
         ┌───────────────────────┴───────────────────────┐
         │                                               │
         ▼                                               ▼
   ┌──────────────┐                              ┌──────────────┐
   │ Query Orders │                              │ Calculate    │
   │ (Delivered   │                              │ Commission   │
   │  7+ days ago)│                              │              │
   └──────┬───────┘                              └──────┬───────┘
          │                                             │
          └────────────────────┬────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Create Settlement   │
                    │ Record              │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Release Escrow      │
                    │ (Platform Wallet)   │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Deduct Commission   │
                    │                     │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Credit Seller       │
                    │ Wallet              │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Process Payout      │
                    │ (if auto-withdraw)  │
                    └─────────────────────┘
```

---

## 6. Kafka Topics & Redis Caching

### 6.1 Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `inventory-events` | Inventory Service | Product, Order, Analytics | InventoryEvent |
| `inventory-commands` | Order Service | Inventory Service | ReserveStockCommand |
| `shipping-events` | Shipping Service | Order, Analytics, Notification | ShipmentEvent |
| `shipping-tracking` | Carrier Webhook | Shipping Service | TrackingUpdate |
| `payment-events` | Payment Service | Order, Notification, Analytics | PaymentEvent |
| `settlement-events` | Payment Service | Notification, Analytics | SettlementEvent |
| `refund-events` | Refund Service | Payment, Inventory, Notification | RefundEvent |

### 6.2 Redis Caching

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `inventory:{sku_id}:{warehouse_id}` | 1 min | Stock availability |
| `inventory:{sku_id}:virtual` | 1 min | Aggregated stock |
| `reservation:{reservation_id}` | Until expires | Active reservations |
| `shipment:tracking:{tracking_number}` | 5 min | Tracking status cache |
| `payment:idempotency:{key}` | 24 hours | Duplicate prevention |
| `wallet:balance:{seller_id}` | 30 sec | Wallet balance cache |
| `settlement:pending:{seller_id}` | 5 min | Pending settlement amount |

### 6.3 Redis Data Structures

```
# Inventory availability (String)
SET inventory:sku-123:wh-1 '{"available": 50, "reserved": 10}'
EXPIRE inventory:sku-123:wh-1 60

# Virtual stock (String)
SET inventory:sku-123:virtual '{"total_available": 150, "warehouses": [...]}'
EXPIRE inventory:sku-123:virtual 60

# Reservation (Hash with expiry)
HSET reservation:res-abc
    "sku_id" "sku-123"
    "warehouse_id" "wh-1"
    "quantity" "5"
    "order_id" "order-xyz"
    "expires_at" "2025-02-01T12:30:00Z"
EXPIREAT reservation:res-abc 1738416600  # Unix timestamp of expires_at

# Payment idempotency (String)
SETNX payment:idempotency:order-xyz-attempt-1 '{"payment_id": "pay-123", "status": "processing"}'
EXPIRE payment:idempotency:order-xyz-attempt-1 86400

# Seller wallet balance (String with frequent updates)
SET wallet:balance:seller-1 '{"available": 5000000, "pending": 2000000}'
EXPIRE wallet:balance:seller-1 30

# Tracking cache (String)
SET shipment:tracking:VN123456789 '{"status": "IN_TRANSIT", "location": "HCM Hub", "updated_at": "..."}'
EXPIRE shipment:tracking:VN123456789 300
```

---

## Summary

### Tables Created

| Service | Tables | Purpose |
|---------|--------|---------|
| **Inventory** | warehouses, inventory_stocks, inventory_settings, inventory_reservations, inventory_transfers, inventory_transfer_items, inventory_logs | Stock management |
| **Shipping** | shipping_methods, shipping_zones, shipping_rates, shipments, shipment_items, shipping_tracking, webhook_delivery_logs | Delivery tracking |
| **Payment** | payment_methods, payments, payment_attempts, platform_wallets, seller_wallets, wallet_transactions, commission_rules, settlements, settlement_items, seller_bank_accounts, withdrawals | Financial processing |
| **Refund** | refund_requests, refund_items, refund_evidence, refund_status_history | Return & refund |

### Key Design Decisions

1. **Virtual inventory** aggregates stock across warehouses for display
2. **Reservation expiry** prevents abandoned carts from blocking stock
3. **Escrow model** holds payment until order completion (7-day default)
4. **Commission rules** support category, seller, and tiered rates
5. **Refund as separate service** due to complex orchestration needs
6. **Saga pattern** coordinates cross-service operations with compensation
