# Inventory Service - Định Nghĩa Các Sự Kiện & Kafka Topics

**Liên kết nhanh (Related Documents):**
*   [Database & Cache Schema](inventory_db_design.md)
*   [Redis Caching & Lua Scripts](inventory_redis_design.md)
*   [Hybrid Routing Strategy](hybrid_routing_strategy.md)
*   [Smart Routing Engine](smart_routing_engine.md)
*   [Workflows (API & Data Flow)](inventory_workflow.md)
*   [Background Workers & Jobs](inventory_workers.md)

**Cross-Service References:**
*   [Product Events - Consumed](../product/product_events.md#43-inventory_quantity_changed) — Product consume `INVENTORY_QUANTITY_CHANGED`
*   [Order Workflow](../order/order_workflow.md) — Order Temporal Activities gọi Reserve/Deduct/Release

---

Inventory Service produce các events liên quan đến biến động tồn kho.

**Cơ chế Produce theo loại Event:**

| Event | Produce Method | Lý do |
|:---|:---|:---|
| `INVENTORY_RESERVED` | **Direct Produce** (sau Redis Lua Script) | Data change atomic trong Redis, không cần DB transaction |
| `INVENTORY_DEDUCTED` | **Direct Produce** (sau Redis Lua Script) | Tương tự RESERVED |
| `INVENTORY_RELEASED` | **Direct Produce** (sau Redis Lua Script) | Tương tự RESERVED |
| `INVENTORY_QUANTITY_CHANGED` | **Outbox Pattern** (PostgreSQL) | Cần đảm bảo consistency với Ledger write (DB transaction) |
| `INVENTORY_OUT_OF_STOCK` | **Direct Produce** (sau Redis check) | Lightweight event, không cần DB transaction |

> **Direct Produce:** Service gọi `Kafka.Produce` ngay sau khi Redis Lua Script trả success. Nếu Kafka fail → retry với backoff. Idempotency đảm bảo bởi `SETNX` trên Redis (xem [inventory_redis_design.md](inventory_redis_design.md)).
>
> **Outbox Pattern:** Event ghi vào bảng `outbox_events` cùng Transaction PostgreSQL với Ledger write. Outbox Polling Worker forward lên Kafka. Đảm bảo consistency giữa DB state và event — nếu transaction fail, event cũng không được publish.

---

## 1. Bản Đồ Kafka Topics

| Topic Name | Partition Key | Consumers | Mô tả |
|:---|:---|:---|:---|
| `t.inventory.events` | `sku_id` | Product Service, Analytics, Search Service (indirect) | Biến động tồn kho |

## 1.1. Topics Consume

| Topic Name | Partition Key | Producer | Event Types | Mô tả |
|:---|:---|:---|:---|:---|
| `t.shipping.events` | `order_id` | Shipping Service | `SHIPMENT_RETURNED` | Nhập trả kho khi kiện hàng hoàn về warehouse |

> **Partition Key = `sku_id`:** Đảm bảo ordering cho cùng 1 SKU → Product Service update đúng thứ tự.

## 1.1 Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents:

*   `event_id`: UUID của sự kiện.
*   `event_type`: Loại sự kiện (VD: `INVENTORY_RESERVED`, `INVENTORY_QUANTITY_CHANGED`).
*   `aggregate_type`: Loại Aggregate Root (Cố định: `Inventory`).
*   `aggregate_id`: ID của entity chủ thể (Cố định: `sku_id`).
*   `correlation_id`: Temporal Workflow ID hoặc upstream request ID.
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (Cố định: `inventory-service`).
*   `payload`: JSON Object chứa nội dung chi tiết của biến động dữ liệu.

---

## 2. Events Phát Đi (Produced Events)

### 2.1. INVENTORY_RESERVED

**Ngữ cảnh:** Temporal Activity `ReserveInventoryActivity` thành công → Redis Lua Script Reserve trả `1` → Backend produce event.
**Consumer:** Internal Ledger Worker (ghi `inventory_ledger`), Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "INVENTORY_RESERVED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "inventory-service",
  "payload": {
    "sku_id": "sku-001",
    "order_id": 10001,
    "parcel_id": 2001,
    "warehouse_id": null,
    "quantity": 2,
    "routing_tier": "FAST_LANE",
    "available_after": 798,
    "reserved_at": "2026-02-28T10:05:00Z"
  }
}
```
> `routing_tier`: `FAST_LANE` (Hot SKU, logical inventory, `warehouse_id = null`) hoặc `SMART_ROUTE` (normal, physical warehouse, `warehouse_id != null`). Xem [Hybrid Routing](hybrid_routing_strategy.md).

### 2.2. INVENTORY_DEDUCTED

**Ngữ cảnh:** Temporal Activity `DeductInventoryActivity` sau Payment Success → Redis Lua Script Deduct → Trừ vĩnh viễn khỏi reserved.
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
  "payload": {
    "sku_id": "sku-001",
    "order_id": 10001,
    "warehouse_id": 5,
    "quantity": 2,
    "deducted_at": "2026-02-28T10:06:00Z"
  }
}
```

### 2.3. INVENTORY_RELEASED

**Ngữ cảnh:** Order cancelled/timeout → Temporal Compensation `RollbackInventoryActivity` → Redis Lua Script Release → Nhả reserved về available.
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
  "payload": {
    "sku_id": "sku-001",
    "order_id": 10001,
    "warehouse_id": 5,
    "quantity": 2,
    "reason": "ORDER_CANCELLED",
    "released_at": "2026-02-28T10:20:00Z"
  }
}
```
> `reason`: `ORDER_CANCELLED`, `PAYMENT_TIMEOUT`, `ORDER_REFUNDED`.

### 2.4. INVENTORY_QUANTITY_CHANGED

**Ngữ cảnh:** Sau khi Ledger Worker ghi `inventory_ledger` + update `logical_inventory` → Produce event cho Product Service cập nhật `display_quantity` và `is_in_stock`.
**Consumer chính:** **Product Service** — [Inventory Sync Worker](../product/product_workers.md#5-inventory-sync-worker) update `display_quantity`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "INVENTORY_QUANTITY_CHANGED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T10:05:01Z",
  "source": "inventory-service",
  "payload": {
    "sku_id": "sku-001",
    "available_quantity": 798,
    "previous_available_quantity": 800,
    "change_type": "ORDER_RESERVED",
    "timestamp": "2026-02-28T10:05:01Z"
  }
}
```
> **Field `change_type`:** `ORDER_RESERVED`, `ORDER_DEDUCTED`, `ORDER_CANCELLED`, `VENDOR_RESTOCK`, `ORDER_RETURNED_RESTOCK`, `ADMIN_ADJUSTMENT`.

### 2.5. INVENTORY_OUT_OF_STOCK

**Ngữ cảnh:** `available_quantity` = 0 cho 1 SKU. Circuit Breaker trigger.
**Consumer:** Product Service (mark `is_in_stock = false`), Frontend (disable nút Mua).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "INVENTORY_OUT_OF_STOCK",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "sys-xyz",
  "timestamp": "2026-02-28T12:00:00Z",
  "source": "inventory-service",
  "payload": {
    "sku_id": "sku-001",
    "out_of_stock_at": "2026-02-28T12:00:00Z"
  }
}
```

---

## 3. Events Tiêu Thụ (Consumed Events)

Inventory Service **không consume Kafka events từ bên ngoài cho luồng Reserve/Deduct/Release**. Các thao tác này được gọi qua **gRPC/REST API trực tiếp** từ Order Service (Temporal Activities).

Ngoài 3 luồng trên, Inventory có consume event ngoài cho bài toán nhập trả kho:
- Consume `SHIPMENT_RETURNED` từ `t.shipping.events` để restock khi kiện hàng trả về warehouse.

Inventory cũng consume event nội bộ qua Kafka cho **Ledger Worker** (ghi sổ cái).

---

## 4. Schema Versioning

*   Backward compatible: chỉ thêm fields mới.
*   `event_id` unique cho idempotency.
*   Product Service dùng `change_type` + `sku_id` để discriminate logic xử lý.
