# Inventory Service - Định Nghĩa Các Sự Kiện & Kafka Topics

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Hybrid Routing Strategy](hybrid_routing_strategy.md)
* [Smart Routing Engine](smart_routing_engine.md)
* [Workflows (API & Data Flow)](inventory_workflow.md)
* [Background Workers & Jobs](inventory_workers.md)

**Cross-Service References:**
* [Product Events - Consumed](../product/product_events.md#44-inventory_quantity_changed) — Product consume `INVENTORY_QUANTITY_CHANGED`
* [Shipping Events](../shipping/shipping_events.md#34-shipment_returned) — Inventory consume `SHIPMENT_RETURNED`

---

Inventory Service produce các events liên quan đến biến động tồn kho và consume event trả hàng từ Shipping.

## 1. Cơ chế Produce theo loại Event

| Event | Produce Method | Lý do |
|:---|:---|:---|
| `INVENTORY_RESERVED` | **Redis Stream Outbox + Relay Worker** | Atomic với Redis mutation trong Lua script |
| `INVENTORY_DEDUCTED` | **Redis Stream Outbox + Relay Worker** | Atomic với Redis mutation trong Lua script |
| `INVENTORY_RELEASED` | **Redis Stream Outbox + Relay Worker** | Atomic với Redis mutation trong Lua script |
| `INVENTORY_QUANTITY_CHANGED` | **PostgreSQL Outbox Pattern** | Đồng bộ chặt với Ledger write trong DB transaction |

> **Quy tắc chống duplicate:** Lua script trả mã `2` khi idempotent hit. Backend và Relay Worker không được phát thêm event mới cho idempotent hit.

---

## 2. Bản Đồ Kafka Topics

### 2.1 Topics Produce

| Topic Name | Partition Key | Consumers | Mô tả |
|:---|:---|:---|:---|
| `t.inventory.commands` | `sku_id` | Inventory Ledger Worker (internal), Analytics | Mutation events nội bộ từ đường nóng reserve/deduct/release |
| `t.inventory.events` | `sku_id` | Product Service, Analytics | Public inventory projection events (`INVENTORY_QUANTITY_CHANGED`) |

### 2.2 Topics Consume

| Topic Name | Partition Key | Producer | Event Types | Mô tả |
|:---|:---|:---|:---|:---|
| `t.shipping.events` | `order_id` | Shipping Service | `SHIPMENT_RETURNED` | Nhập trả kho khi kiện hàng hoàn về warehouse |

> Partition key `sku_id` đảm bảo ordering cho cùng SKU ở downstream consumer.

---

## 3. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents:

* `event_id`: UUID của sự kiện.
* `event_type`: Loại sự kiện.
* `aggregate_type`: Cố định `Inventory`.
* `aggregate_id`: Cố định `sku_id`.
* `correlation_id`: Temporal Workflow ID hoặc upstream request ID.
* `timestamp`: ISO 8601 UTC.
* `source`: Cố định `inventory-service`.
* `schema_version`: Phiên bản schema payload (khuyến nghị, hiện tại `1`).
* `payload`: JSON Object chi tiết.

---

## 4. Events Phát Đi (Produced Events)

### 4.1. INVENTORY_RESERVED

**Ngữ cảnh:** `ReserveInventoryActivity` thành công và Lua script trả `1`.
**Consumer:** Internal Ledger Worker, Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "INVENTORY_RESERVED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "sku_id": "sku-001",
    "product_id": "prod-uuid-001",
    "order_id": 10001,
    "parcel_id": null,
    "warehouse_id": 5,
    "quantity": 2,
    "routing_tier": "FAST_LANE",
    "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
    "route_version": 3,
    "available_after": 798,
    "reserved_at": "2026-02-28T10:05:00Z"
  }
}
```

### 4.2. INVENTORY_DEDUCTED

**Ngữ cảnh:** `DeductInventoryActivity` thành công và Lua script trả `1`.
**Consumer:** Internal Ledger Worker.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "INVENTORY_DEDUCTED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:06:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "sku_id": "sku-001",
    "product_id": "prod-uuid-001",
    "order_id": 10001,
    "warehouse_id": 5,
    "quantity": 2,
    "deducted_at": "2026-02-28T10:06:00Z"
  }
}
```

### 4.3. INVENTORY_RELEASED

**Ngữ cảnh:** `RollbackInventoryActivity` hoặc Reservation Expiry Scanner.
**Consumer:** Internal Ledger Worker.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "INVENTORY_RELEASED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:20:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "sku_id": "sku-001",
    "product_id": "prod-uuid-001",
    "order_id": 10001,
    "warehouse_id": 5,
    "quantity": 2,
    "reason": "ORDER_CANCELLED",
    "released_at": "2026-02-28T10:20:00Z"
  }
}
```

`reason` hợp lệ: `ORDER_CANCELLED`, `PAYMENT_TIMEOUT`, `RESERVATION_EXPIRED`.

### 4.4. INVENTORY_QUANTITY_CHANGED

**Ngữ cảnh:** Sau khi Ledger Worker ghi `inventory_ledger` + update inventory tables -> insert `outbox_events` -> Outbox Worker publish Kafka.
**Consumer chính:** Product Service.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "INVENTORY_QUANTITY_CHANGED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T10:05:01Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-001",
    "available_quantity": 798,
    "previous_available_quantity": 800,
    "change_type": "ORDER_RESERVED",
    "event_timestamp": 1709114701000,
    "timestamp_iso": "2026-02-28T10:05:01Z"
  }
}
```

`change_type` hợp lệ: `ORDER_RESERVED`, `ORDER_DEDUCTED`, `ORDER_CANCELLED`, `VENDOR_RESTOCK`, `ORDER_RETURNED_RESTOCK`, `ADMIN_ADJUSTMENT`, `RESERVATION_EXPIRED`.

**Contract note:** Producer bắt buộc gửi `product_id` + `sku_id`. Consumer Product vẫn validate cặp khóa để chặn drift.

---

## 5. Events Tiêu Thụ (Consumed Events)

Inventory Service không consume event ngoài cho luồng Reserve/Deduct/Release (gọi qua API trực tiếp từ Order Temporal Activities).

Inventory consume thêm event logistics:

### 5.1. SHIPMENT_RETURNED

```json
{
  "event_type": "SHIPMENT_RETURNED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "parcel_id": 2001,
    "returned_to_warehouse_id": 5,
    "return_reason": "CUSTOMER_RETURN",
    "items": [
      {
        "sku_id": "sku-001",
        "product_id": "prod-uuid-001",
        "quantity": 2
      }
    ],
    "returned_at": "2026-03-04T10:00:00Z"
  }
}
```

`shipment_id` và `items[]` là bắt buộc để idempotency + restock chính xác.

### 5.2. Internal command events

Inventory Ledger Worker consume từ `t.inventory.commands` và chỉ xử lý:

* `INVENTORY_RESERVED`
* `INVENTORY_DEDUCTED`
* `INVENTORY_RELEASED`

Ledger Worker không xử lý `INVENTORY_QUANTITY_CHANGED`.

---

## 6. Schema Versioning

1. Backward compatible: chỉ thêm fields mới.
2. `event_id` unique cho idempotency.
3. Không đổi nghĩa field hiện có nếu chưa có migration plan.