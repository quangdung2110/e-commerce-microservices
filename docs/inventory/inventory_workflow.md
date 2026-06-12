# Inventory Service - Luồng Dữ Liệu Nội Bộ (Internal Data Flow)

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Smart Routing Engine](smart_routing_engine.md)
* [Observability & SLO](inventory_observability_slo.md)

**Cross-Service References:**
* [Order Temporal Saga](../order/temporal_saga_design.md) — Activities gọi Reserve/Deduct/Release
* [Checkout Workflow](../checkout/checkout_workflow.md) — Preview stock check và submit
* [Shipping Workflow](../shipping/shipping_workflow.md) — Routing Options gọi Shipping tính phí
* [Shipping Events](../shipping/shipping_events.md) — Inventory consume `SHIPMENT_RETURNED` để nhập trả kho

---

Tài liệu này mô tả chi tiết luồng dữ liệu nội bộ của Inventory Service. Mỗi luồng ghi rõ dữ liệu được vận chuyển qua các thành phần như thế nào: `[Redis Lua]`, `[PostgreSQL]`, `[Redis Stream]`, `[Kafka]`, `[CDC]`.

## Quy ước chung

1. `warehouse_id` là bắt buộc cho mọi item mutation (reserve/deduct/release).
2. Multi-item request áp dụng chính sách **all-or-nothing**.
3. Event `INVENTORY_RESERVED/DEDUCTED/RELEASED` không produce trực tiếp từ API vào Kafka; event được append vào Redis Stream trong chính Lua script, sau đó Relay Worker forward sang Kafka.
4. Event `INVENTORY_QUANTITY_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL` được insert vào PostgreSQL Outbox rồi Debezium CDC tự động publish lên Kafka.

---

## Luồng 1: Reserve Inventory (Giữ chỗ tồn kho)

**Ngữ cảnh:** Order Temporal Saga — bước Pre-Payment số 1 (`ReserveInventoryActivity`).

### Luồng dữ liệu

```
[Order/Temporal]
        |  Temporal Activity call
        v
[Inventory API Layer]
        |  Nhận request {order_id, route_plan_id, items[]}
        v
[Contract Guard]
        |  Validate warehouse_id, route_plan_id, all-or-nothing
        v
[Redis Lua Script 1 - Reserve]
        |  KEYS[1] = inventory:physic:{sku_id:warehouse_id}:stock (Hash)
        |  KEYS[2] = inventory:idemp:reserve:{sku_id:warehouse_id}:order:{order_id} (String)
        |  KEYS[3] = inventory:stream:{sku_id:warehouse_id}:events (Stream)
        |  ARGV = {quantity, now_ms, idemp_ttl_sec, event_payload_json}
        |  Lua atomic:
        |    1. SETNX idempotency key -> return 2 (already applied)
        |    2. HGET available >= quantity? -> return 0 (out_of_stock)
        |    3. HINCRBY available -= qty, reserved += qty
        |    4. XADD inventory:stream:{...}:events  event_type=INVENTORY_RESERVED, payload=...
        |    return 1 (applied)
        v
[Redis Hash]
        |  updated: {total, reserved, available, updated_at_ms}
        v
[Redis Stream Outbox]
        |  XADD entry: {event_type, payload}
        v
[Redis Stream Relay Worker]
        |  Consumer Group: inventory-stream-relay
        |  READ stream -> PARSE payload + CloudEvents header
        |  PRODUCE Kafka topic=t.inventory.commands, key=sku_id
        v
[Kafka: t.inventory.commands]
        v
[Kafka Ledger Worker]
        |  Consumer Group: inventory-ledger-writer
        |  poll(100ms) batch
        |  Dedup via inventory_event_idempotency (event_id)
        |  Group by sku_id
        |  Skip SUSPENDED SKU -> park in suspended_events
        |  Bulk INSERT inventory_ledger (reference_type=ORDER_RESERVED)
        |  UPSERT inventory_reservations (status=RESERVED)
        |  UPDATE physical_inventory.reserved_quantity
        |  UPDATE logical_inventory
        |  INSERT inventory_outbox (event_type=INVENTORY_QUANTITY_CHANGED, partition_key=sku_id)
        |  PostgreSQL Transaction COMMIT
        v
[PostgreSQL: inventory_ledger + inventory_reservations + inventory_outbox]
        v
[Debezium CDC Connector]
        |  Read WAL of inventory_outbox
        |  Extract partition_key column as Kafka message key (NOT from JSON)
        |  Produce Kafka topic=t.inventory.events
        v
[Kafka: t.inventory.events]
        v
[Product Service]
        |  Consume INVENTORY_QUANTITY_CHANGED
        |  Update product stock cache
```

### Mã trả về Lua xử lý

| Mã | Ngữ cảnh | Hành động tiếp theo |
|:---:|:---|:---|
| `1` | Apply mới | Tiếp tục; Relay Worker forward event |
| `2` | Idempotent hit | Trả idempotent response; KHÔNG phát thêm event |
| `0` | Out of stock | Rollback các item đã apply trong request, trả lỗi tổng |
| `-1` | Invariant drift | Suspend SKU, phát alert, tạo reconciliation task |

### Rollback strategy

- Nếu gặp `0` (out_of_stock): gọi Release Lua script để rollback các item đã apply trong request hiện tại, trả lỗi nghiệp vụ tổng.
- Nếu gặp `-1`: dừng request ngay, KHÔNG chạy rollback mù quáng; đưa SKU vào `suspended_sku_locks`, phát alert vận hành và tạo task reconciliation.

---

## Luồng 2: Deduct Inventory (Xác nhận bán đứt)

**Ngữ cảnh:** Order Temporal Saga — bước Post-Payment số 1 (`DeductInventoryActivity`).

### Luồng dữ liệu

```
[Order/Temporal]
        |  Temporal Activity call sau payment success
        v
[Inventory API Layer]
        |  Nhận request {order_id, items[{sku_id, quantity, warehouse_id}]}
        v
[Redis Lua Script 3 - Deduct]
        |  KEYS[1] = inventory:physic:{sku_id:warehouse_id}:stock
        |  KEYS[2] = inventory:idemp:deduct:{sku_id:warehouse_id}:order:{order_id}
        |  KEYS[3] = inventory:stream:{sku_id:warehouse_id}:events
        |  Lua atomic:
        |    1. SETNX idempotency key -> return 2 (already applied)
        |    2. INVARIANT CHECK: available == total - reserved? -> return -1 (drift)
        |    3. reserved >= quantity? -> return 0 (insufficient_reserved)
        |    4. new_reserved = reserved - qty
        |       new_total = total - qty
        |       new_available = new_total - new_reserved
        |    5. POST-MUTATE INVARIANT CHECK -> return -1 (drift)
        |    6. HSET stock hash
        |    7. XADD stream event_type=INVENTORY_DEDUCTED
        |    return 1 (applied)
        v
[Redis Hash]
        |  updated: {total, reserved, available, updated_at_ms}
        v
[Redis Stream Outbox]
        v
[Redis Stream Relay Worker]
        v
[Kafka: t.inventory.commands]
        v
[Kafka Ledger Worker]
        |  UPDATE inventory_reservations.status = 'DEDUCTED'
        |  INSERT inventory_ledger (reference_type=ORDER_DEDUCTED)
        |  UPDATE physical_inventory + logical_inventory
        |  INSERT inventory_outbox (INVENTORY_QUANTITY_CHANGED)
        |  PostgreSQL Transaction COMMIT
        v
[PostgreSQL: inventory_ledger + inventory_reservations + inventory_outbox]
        v
[Debezium CDC Connector]
        v
[Kafka: t.inventory.events]
        v
[Product Service]
```

### Mã trả về Lua xử lý

| Mã | Ngữ cảnh | Hành động tiếp theo |
|:---:|:---|:---|
| `1` | Apply mới | Tiếp tục; Relay Worker forward event |
| `2` | Idempotent hit | Trả idempotent response; KHÔNG phát thêm event |
| `0` | Insufficient reserved | Trả lỗi nghiệp vụ (không rollback — deduct chỉ chạy sau payment đã thành công) |
| `-1` | Invariant drift | Chuyển fail-safe lane: suspend SKU + reconciliation; KHÔNG tự tiếp tục deduct |

> Không có DOM re-routing sau deduct. Nếu cần đổi kho phải release + reserve lại theo route plan mới.

---

## Luồng 3: Release Inventory (Giải phóng giữ chỗ)

**Ngữ cảnh:** Order cancelled/timeout hoặc Reservation Expiry Scanner tự động.

### Luồng dữ liệu

```
[Order/Temporal] hoặc [Reservation Expiry Scanner]
        |  Temporal Compensation call / CronJob trigger
        v
[Inventory API Layer]
        |  Nhận request {order_id, items[{sku_id, quantity, warehouse_id}], reason}
        v
[Redis Lua Script 2 - Release]
        |  KEYS[1] = inventory:physic:{sku_id:warehouse_id}:stock
        |  KEYS[2] = inventory:idemp:release:{sku_id:warehouse_id}:order:{order_id}
        |  KEYS[3] = inventory:stream:{sku_id:warehouse_id}:events
        |  Lua atomic:
        |    1. SETNX idempotency key -> return 2 (already released)
        |    2. reserved >= quantity? -> return 0 (release_underflow)
        |    3. reserved -= qty, available += qty
        |    4. XADD stream event_type=INVENTORY_RELEASED
        |    return 1 (applied)
        v
[Redis Hash]
        v
[Redis Stream Outbox]
        v
[Redis Stream Relay Worker]
        v
[Kafka: t.inventory.commands]
        v
[Kafka Ledger Worker]
        |  UPDATE inventory_reservations.status = 'CANCELLED'
        |  INSERT inventory_ledger (reference_type = RESERVATION_EXPIRED hoặc ORDER_CANCELLED tùy reason)
        |  UPDATE physical_inventory + logical_inventory
        |  INSERT inventory_outbox (INVENTORY_QUANTITY_CHANGED)
        v
[Debezium CDC Connector]
        v
[Kafka: t.inventory.events]
```

---

## Luồng 4: Routing Options (Tạo plan fulfillment)

**Ngữ cảnh:** Checkout Preview gọi để lấy plan fulfillment đầy đủ.

### Luồng dữ liệu

```
[Checkout Service]
        |  Request: {items[{sku_id, quantity}], customer_location, service_level}
        v
[Candidate Generation]
        |  Query physical_inventory.available_quantity theo kho
        |  Build feasible warehouse combinations (stock sufficient)
        v
[Shipping Quote Call]
        |  Gọi POST /v1/shipping/calculate-batch để lấy shipping fee + ETA
        v
[Smart Routing Engine]
        |  Scoring: score = w1*shipping_cost + w2*delivery_days + w3*split_penalty + w4*warehouse_priority
        |  Tie-break: lower cost -> faster ETA -> fewer splits -> stable warehouse order
        |  Output: route_plan với version deterministic
        v
[Response]
        |  {
        |    "route_plan_id": "...",
        |    "route_version": 3,
        |    "algorithm_version": "routing-v2.1.0",
        |    "inventory_snapshot_at": "...",
        |    "shipping_rate_version": "...",
        |    "expires_at": "...",
        |    "items": [{sku_id, quantity, warehouse_id, routing_tier}],
        |    "shipping_options": [{provider, service, fee, delivery_days}]
        |  }
```

> Route plan có version, không thay đổi nếu input snapshot giống nhau. Khi checkout submit, nếu plan expired -> reject với `CONTRACT_INVALID_ROUTE_PLAN` và yêu cầu re-preview.

---

## Luồng 5: Admin Restock (Nhập hàng vào kho)

**Ngữ cảnh:** Admin/WMS nhập hàng. Entry point khởi tạo inventory duy nhất.

### Luồng dữ liệu

```
[Admin / WMS]
        |  Request: {warehouse_id, sku_id, product_id, quantity}
        v
[Contract Guard]
        |  Validate warehouse_id, sku_id, product_id
        v
[PostgreSQL Transaction]
        |  BEGIN;
        |  -- Upsert physical_inventory
        |  INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
        |    VALUES (:sku, :product, :wh, :qty, 0, NOW())
        |    ON CONFLICT (sku_id, warehouse_id)
        |    DO UPDATE SET actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
        |                  updated_at = NOW();
        |  -- Upsert logical_inventory
        |  INSERT INTO logical_inventory (sku_id, product_id, total_quantity, reserved_quantity, version, updated_at)
        |    VALUES (:sku, :product, :qty, 0, 1, NOW())
        |    ON CONFLICT (sku_id)
        |    DO UPDATE SET total_quantity = logical_inventory.total_quantity + EXCLUDED.total_quantity,
        |                  version = logical_inventory.version + 1,
        |                  updated_at = NOW();
        |  -- Ledger audit trail
        |  INSERT INTO inventory_ledger (sku_id, warehouse_id, reference_type, quantity_change, balance_after, created_at)
        |    VALUES (:sku, :wh, 'ADMIN_RESTOCK', :qty, :new_balance, NOW());
        |  -- Outbox for CDC
        |  INSERT INTO inventory_outbox (... event_type='INVENTORY_QUANTITY_CHANGED', partition_key=:sku_id ...);
        |  COMMIT;
        v
[Redis Cache Update]
        |  Update inventory:physic:{sku_id:warehouse_id}:stock
        |  Update inventory:logic:{sku_id}:stock
        v
[Debezium CDC Connector]
        |  Read WAL of inventory_outbox
        |  Produce Kafka topic=t.inventory.events, key=sku_id
        v
[Kafka: t.inventory.events]
        v
[Product Service]
        |  Consume INVENTORY_QUANTITY_CHANGED
```

---

## Luồng 6: Return Restock (Nhận hàng hoàn về kho)

**Ngữ cảnh:** Shipping Service phát `SHIPMENT_RETURNED` khi kiện trả về kho.

### Điều kiện tiên quyết

Order đăng ký refund scope trước bằng API nội bộ `POST /v1/inventory/returns/refund-scopes`. Inventory dùng scope này để xác định khi nào đủ điều kiện phát event `INVENTORY_RETURN_RECEIVED_ALL`.

### Luồng dữ liệu

```
[Shipping Service]
        |  Produce Kafka event SHIPMENT_RETURNED
        v
[Kafka: t.shipping.events]
        v
[Kafka Return Consumer]
        |  Payload: {shipment_id, order_id, order_item_id, returned_to_warehouse_id, items[]}
        v
[Idempotency Guard]
        |  Layer 1 (Redis): SETNX inventory:return:{order_id}:{shipment_id}
        |  Layer 2 (DB): inventory_refund_scopes.idempotency_key
        |    (đã gộp từ inventory_event_idempotency)
        v
[PostgreSQL Transaction]
        |  BEGIN;
        |  -- Restock physical + logical per item
        |  UPDATE physical_inventory
        |    SET actual_quantity = actual_quantity + returned_qty,
        |        updated_at = NOW()
        |    WHERE sku_id = :sku AND warehouse_id = :wh;
        |  UPDATE logical_inventory
        |    SET total_quantity = total_quantity + returned_qty,
        |        version = version + 1,
        |        updated_at = NOW()
        |    WHERE sku_id = :sku;
        |  INSERT INTO inventory_ledger
        |    (sku_id, warehouse_id, reference_type, quantity_change, balance_after, created_at)
        |    VALUES (:sku, :wh, 'ORDER_RETURNED_RESTOCK', :qty, :new_balance, NOW());
        |  INSERT INTO inventory_outbox
        |    (... event_type='INVENTORY_QUANTITY_CHANGED', partition_key=:sku_id ...);
        |  -- Refund scope aggregation
        |  UPDATE inventory_refund_scopes
        |    SET received_order_item_ids =
        |      received_order_item_ids || :order_item_id::jsonb,
        |        updated_at = NOW()
        |    WHERE order_id = :order_id AND refund_request_id = :refund_request_id
        |      AND status = 'OPEN';
        |  -- Check if all required items received
        |  IF received_order_item_ids @> required_order_item_ids THEN
        |    UPDATE inventory_refund_scopes SET status = 'COMPLETED', closed_at = NOW();
        |    INSERT INTO inventory_outbox
        |      (... event_type='INVENTORY_RETURN_RECEIVED_ALL', partition_key=:order_id ...);
        |  END IF;
        |  COMMIT;
        v
[PostgreSQL: physical_inventory + logical_inventory + inventory_ledger + inventory_refund_scopes + inventory_outbox]
        v
[Redis Cache Re-sync]
        |  Update inventory:physic:{sku_id:warehouse_id}:stock
        |  Update inventory:logic:{sku_id}:stock
        v
[Debezium CDC Connector]
        |  Read WAL of inventory_outbox
        |  -- INVENTORY_QUANTITY_CHANGED (partition_key=sku_id)
        |  -- INVENTORY_RETURN_RECEIVED_ALL (partition_key=order_id)
        v
[Kafka: t.inventory.events]
        |-- INVENTORY_QUANTITY_CHANGED (change_type=ORDER_RETURNED_RESTOCK)
        v  [Product Service]
        |-- INVENTORY_RETURN_RECEIVED_ALL
           [Order Service]
           |  Trigger Payment refund step
```

### Contract Refund Scope

- **Insert lần đầu:** tạo row mới `inventory_refund_scopes` với `status = OPEN`.
- **Replay cùng tập `required_order_item_ids`:** trả success idempotent.
- **Replay khác tập item cho cùng `(order_id, refund_request_id)`:** trả `409 REFUND_SCOPE_MISMATCH`.

### Nguyên tắc nghiệp vụ

- Nguồn kích hoạt restock duy nhất là `SHIPMENT_RETURNED`. Event `ORDER_REFUNDED` chỉ là trạng thái tài chính.
- `INVENTORY_RETURN_RECEIVED_ALL` là signal nghiệp vụ để Order mở bước gọi Payment refund, không thay thế event tồn kho `INVENTORY_QUANTITY_CHANGED`.

---

## Luồng 7: Poison Pill Handling (Xử lý invariant drift — Redis-based)

```
[Lua Script trả -1]
        |  Invariant breach detected (available != total - reserved sau mutate)
        v
[API Layer]
        |  KHÔNG auto-rollback, KHÔNG retry
        |  SET inventory:suspended:{sku_id:warehouse_id}
        |    = {lock_reason, locked_at, status=SUSPENDED} TTL=24h
        |  XADD inventory:failed:{sku_id:warehouse_id}:events
        |    event_type=..., payload=..., failure_reason=DLQ
        |  Alert vận hành
        v
[Suspended Events Parking]
        |  Event hiện tại và các event đi sau cùng SKU trong batch
        |  được XADD vào inventory:failed:{sku_id:warehouse_id}:events
        |  với failure_reason=SKU_SUSPENDED
        v
[Suspended Events Recovery Worker (CronJob 5 phút)]
        |  SCAN inventory:suspended:* keys
        |  Kiểm tra key đã hết hạn (TTL expired)?
        |  Nếu TTL expired (auto-unlock):
        |    XREAD inventory:failed:{sku_id:warehouse_id}:events
        |    Replay event qua Kafka Ledger Worker
        |    DEL inventory:suspended:{sku_id:warehouse_id}
        |  Nếu cần unlock thủ công:
        |    DEL inventory:suspended:{sku_id:warehouse_id}
        |    THEN replay như trên
        v
[Kafka Ledger Worker]
        |  Consume và xử lý replayed event như bình thường
```

---

## Tóm tắt luồng produce event

| Event | Nguồn tạo | Đích Kafka | Cơ chế |
|:---|:---|:---|:---|
| `INVENTORY_RESERVED` | Lua Script 1 | `t.inventory.commands` | Redis Stream Outbox + Relay Worker |
| `INVENTORY_DEDUCTED` | Lua Script 3 | `t.inventory.commands` | Redis Stream Outbox + Relay Worker |
| `INVENTORY_RELEASED` | Lua Script 2 | `t.inventory.commands` | Redis Stream Outbox + Relay Worker |
| `INVENTORY_QUANTITY_CHANGED` | Ledger Worker | `t.inventory.events` | PostgreSQL Outbox + Debezium CDC |
| `INVENTORY_RETURN_RECEIVED_ALL` | Return Consumer | `t.inventory.events` | PostgreSQL Outbox + Debezium CDC |
| `SHIPMENT_RETURNED` | Shipping Service | `t.shipping.events` | Kafka (consumed by Inventory) |

---

## Ghi chú: Khởi tạo Inventory Records

Inventory records (`logical_inventory`, `physical_inventory`) **không auto-create** khi Product tạo SKU mới.

Entry point khởi tạo:
1. Admin Restock API.
2. WMS Integration API.

Khi restock lần đầu cho SKU, hệ thống upsert tạo record nếu chưa tồn tại.
