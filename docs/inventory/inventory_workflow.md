# Inventory Service - Luồng Dữ Liệu Nội Bộ (Internal Data Flow)

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Smart Routing Engine](smart_routing_engine.md)

**Cross-Service References:**
* [Order Temporal Saga](../order/temporal_saga_design.md) — Activities gọi Reserve/Deduct/Release
* [Checkout Workflow](../checkout/checkout_workflow.md) — Preview stock check và submit
* [Shipping Events](../shipping/shipping_events.md) — Inventory consume `SHIPMENT_RETURNED` để nhập trả kho

---

Tài liệu này mô tả chi tiết luồng dữ liệu nội bộ của Inventory Service. Mỗi luồng ghi rõ dữ liệu được vận chuyển qua các thành phần như thế nào: `[Redis Lua]`, `[PostgreSQL]`, `[Redis Stream]`, `[Kafka]`, `[CDC]`.

## Quy ước chung

1. `warehouse_id` là bắt buộc cho mọi item mutation (reserve/deduct/release).
2. Multi-item request áp dụng chính sách **all-or-nothing**.
3. Event `INVENTORY_STOCK_MUTATION` được append vào Redis Stream trong chính Lua script, sau đó Relay Worker forward sang Kafka `t.inventory.events`.
4. Event `INVENTORY_STOCK_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL` được insert vào PostgreSQL Outbox rồi Debezium CDC tự động publish lên cùng topic `t.inventory.events`.

---

## Luồng 1: Reserve Inventory (Giữ chỗ tồn kho)

**Ngữ cảnh:** Order Temporal Saga — bước Pre-Payment (`ReserveInventoryActivity`).

### Luồng dữ liệu

```
[Order/Temporal]
        |  Temporal Activity call
        v
[Inventory API Layer]
        |  Nhận request {order_id, route_plan_id, items[{sku_id, product_id, quantity, warehouse_id}]}
        v
[Contract Guard]
        |  Validate warehouse_id tồn tại & active, route_plan_id đúng format
        v
[Redis Lua Script 1 - Reserve]
        |  KEYS[1] = inventory:physic:{sku:wh}:stock (Hash)
        |  KEYS[2] = inventory:idemp:reserve:{sku:wh}:order:{order_id} (String)
        |  KEYS[3] = inventory:stream:{sku:wh}:events (Stream)
        |  KEYS[4] = inventory:suspended:{sku:wh} (Hash, optional)
        |  ARGV = {quantity, now_ms, idemp_ttl_sec, event_payload_json}
        |  Lua atomic:
        |    1. EXISTS suspended? -> return {0, 'SKU_SUSPENDED'}
        |    2. SETNX idempotency key -> return {2, ''} (idempotent hit)
        |    3. HGET available >= quantity? -> return {0, 'OUT_OF_STOCK'}
        |    4. HINCRBY available -= qty, reserved += qty
        |    5. XADD stream event_type=INVENTORY_STOCK_MUTATION, change_type=ORDER_RESERVED, payload=...
        |    return {1, ''} (applied)
        v
[Redis Hash]
        |  updated: {total, reserved, available, updated_at_ms}
        v
[Redis Stream Outbox]
        |  XADD entry: {event_type, change_type, payload}
        v
[Redis Stream Relay Worker]
        |  Consumer Group: inventory-stream-relay
        |  READ stream -> PARSE payload + CloudEvents header
        |  PRODUCE Kafka topic=t.inventory.events, key=sku_id
        v
[Kafka: t.inventory.events]
        v
[Kafka Ledger Worker]
        |  Filter event_type=INVENTORY_STOCK_MUTATION only
        |  Dedup via inventory_ledger.event_id UNIQUE
        |  Group by sku_id
        |  Skip event khi Redis suspended flag tồn tại (giữ cho invariant safety)
        |  Bulk INSERT inventory_ledger (reference_type=ORDER_RESERVED)
        |  UPSERT inventory_reservations (status=RESERVED)
        |  UPDATE physical_inventory.reserved_quantity
        |  INSERT inventory_outbox (event_type=INVENTORY_STOCK_CHANGED, partition_key=sku_id)
        |    với payload SUM(available_quantity) từ physical_inventory
        |  PostgreSQL Transaction COMMIT
        v
[PostgreSQL: inventory_ledger + inventory_reservations + inventory_outbox]
        v
[Debezium CDC Connector]
        |  Read WAL of inventory_outbox
        |  Produce Kafka topic=t.inventory.events (cùng topic)
        v
[Product Service]
        |  Consume INVENTORY_STOCK_CHANGED
        |  Update product stock cache
```

### Mã trả về Lua xử lý

| Mã | Lý do | Hành động tiếp theo |
|:---:|:---|:---|
| `{1, ''}` | Apply mới | Tiếp tục; Relay Worker forward event |
| `{2, ''}` | Idempotent hit | Trả idempotent response; KHÔNG phát thêm event |
| `{0, 'OUT_OF_STOCK'}` | Hết hàng | Rollback các item đã apply trong request, trả lỗi tổng |
| `{0, 'SKU_SUSPENDED'}` | SKU đang bị khoá | Trả lỗi nghiệp vụ, KHÔNG rollback (vì không có item nào apply) |
| `{-1, 'INVARIANT_DRIFT'}` | Invariant drift | Suspend SKU, phát alert, tạo task reconciliation manual |

### Rollback strategy

- Nếu gặp `OUT_OF_STOCK`: gọi Release Lua script để rollback các item đã apply trong request hiện tại, trả lỗi nghiệp vụ tổng.
- Nếu gặp `INVARIANT_DRIFT`: dừng request ngay, KHÔNG chạy rollback mù quáng; API Layer đặt `inventory:suspended:*` rồi trả lỗi.

---

## Luồng 2: Deduct Inventory (Xác nhận bán đứt)

**Ngữ cảnh:** Order Temporal Saga — bước Post-Payment (`DeductInventoryActivity`).

### Luồng dữ liệu

```
[Order/Temporal]
        |  Temporal Activity call sau payment success
        v
[Inventory API Layer]
        |  Nhận request {order_id, items[{sku_id, product_id, quantity, warehouse_id}]}
        v
[Redis Lua Script 3 - Deduct]
        |  KEYS[1] = inventory:physic:{sku:wh}:stock
        |  KEYS[2] = inventory:idemp:deduct:{sku:wh}:order:{order_id}
        |  KEYS[3] = inventory:stream:{sku:wh}:events
        |  KEYS[4] = inventory:suspended:{sku:wh}
        |  Lua atomic:
        |    1. EXISTS suspended? -> return {0, 'SKU_SUSPENDED'}
        |    2. SETNX idempotency key -> return {2, ''}
        |    3. INVARIANT CHECK: available == total - reserved? -> return {-1, 'INVARIANT_DRIFT'}
        |    4. reserved >= quantity? -> return {0, 'INSUFFICIENT_RESERVED'}
        |    5. new_reserved = reserved - qty
        |       new_total = total - qty
        |       new_available = new_total - new_reserved
        |    6. POST-MUTATE INVARIANT CHECK -> return {-1, 'INVARIANT_DRIFT'}
        |    7. HSET stock hash
        |    8. XADD stream event_type=INVENTORY_STOCK_MUTATION, change_type=ORDER_DEDUCTED
        |    return {1, ''} (applied)
        v
[Redis Hash]
        v
[Redis Stream Outbox]
        v
[Redis Stream Relay Worker]
        v
[Kafka: t.inventory.events]
        v
[Kafka Ledger Worker]
        |  UPDATE inventory_reservations.status = 'DEDUCTED'
        |  INSERT inventory_ledger (reference_type=ORDER_DEDUCTED)
        |  UPDATE physical_inventory.reserved_quantity, actual_quantity
        |  INSERT inventory_outbox (INVENTORY_STOCK_CHANGED, change_type=ORDER_DEDUCTED)
        |  PostgreSQL Transaction COMMIT
        v
[Debezium CDC Connector]
        v
[Kafka: t.inventory.events]
        v
[Product Service]
```

### Mã trả về Lua xử lý

| Mã | Lý do | Hành động tiếp theo |
|:---:|:---|:---|
| `{1, ''}` | Apply mới | Tiếp tục; Relay Worker forward event |
| `{2, ''}` | Idempotent hit | Trả idempotent response; KHÔNG phát thêm event |
| `{0, 'INSUFFICIENT_RESERVED'}` | Reservation không đủ | Trả lỗi nghiệp vụ (không rollback — deduct chỉ chạy sau payment đã thành công) |
| `{0, 'SKU_SUSPENDED'}` | SKU đang bị khoá | Trả lỗi nghiệp vụ |
| `{-1, 'INVARIANT_DRIFT'}` | Invariant drift | Suspend SKU + alert; KHÔNG tự tiếp tục deduct |

> Không có DOM re-routing sau deduct. Nếu cần đổi kho phải release + reserve lại theo route plan mới.

---

## Luồng 3: Release Inventory (Giải phóng giữ chỗ)

**Ngữ cảnh:** Order Temporal compensation (khi checkout fail, payment timeout, hoặc order bị cancel trước payment).

### Luồng dữ liệu

```
[Order/Temporal]
        |  Temporal Compensation call (RollbackInventoryActivity)
        v
[Inventory API Layer]
        |  Nhận request {order_id, items[{sku_id, product_id, quantity, warehouse_id}], change_type}
        |  change_type: ORDER_CANCELLED | RESERVATION_EXPIRED
        v
[Redis Lua Script 2 - Release]
        |  KEYS[1] = inventory:physic:{sku:wh}:stock
        |  KEYS[2] = inventory:idemp:release:{sku:wh}:order:{order_id}
        |  KEYS[3] = inventory:stream:{sku:wh}:events
        |  KEYS[4] = inventory:suspended:{sku:wh}
        |  ARGV[5] = change_type
        |  Lua atomic:
        |    1. EXISTS suspended? -> return {0, 'SKU_SUSPENDED'}
        |    2. SETNX idempotency key -> return {2, ''}
        |    3. reserved >= quantity? -> return {0, 'RELEASE_UNDERFLOW'}
        |    4. reserved -= qty, available += qty
        |    5. XADD stream event_type=INVENTORY_STOCK_MUTATION, change_type=<ARGV[5]>
        |    return {1, ''} (applied)
        v
[Redis Hash]
        v
[Redis Stream Outbox]
        v
[Redis Stream Relay Worker]
        v
[Kafka: t.inventory.events]
        v
[Kafka Ledger Worker]
        |  UPDATE inventory_reservations.status = 'CANCELLED'
        |  INSERT inventory_ledger (reference_type theo change_type)
        |  UPDATE physical_inventory.reserved_quantity
        |  INSERT inventory_outbox (INVENTORY_STOCK_CHANGED, change_type=change_type)
        v
[Debezium CDC Connector]
        v
[Kafka: t.inventory.events]
        v
[Product Service]
```


---

## Luồng 4: Routing Options (Tạo plan fulfillment)

**Ngữ cảnh:** Checkout Preview gọi để lấy plan fulfillment đầy đủ.

### Luồng dữ liệu

```
[Checkout Service]
        |  Request: {items[{sku_id, quantity}], customer_location, service_type}
        v
[Candidate Generation]
        |  SELECT sku_id, product_id, warehouse_id, available_quantity
        |  FROM physical_inventory
        |  WHERE sku_id = ANY(:sku_ids) AND available_quantity > 0
        |  Build feasible warehouse combinations (stock sufficient cho mỗi item)
        v
[Shipping Quote Call]
        |  Gọi POST /v1/shipping/calculate-batch để lấy shipping fee + ETA
        |  (Input: from_warehouse_id, to_province/district, items_count, service_type — không có weight/dimensions/COD)
        v
[Smart Routing Engine]
        |  Score = shipping_cost + delivery_days * cost_per_day
        |  Tie-break: warehouse_id ASC (deterministic)
        |  Output: route_plan với concrete warehouse_id cho mỗi item + product_id denormalized
        v
[Cache Route Plan]
        |  SET route_plan:{route_plan_id} = JSON, TTL = expires_at
        v
[Response]
        |  {
        |    "route_plan_id": "...",
        |    "expires_at": "...",
        |    "rate_card_version": "...",
        |    "items": [{sku_id, product_id, quantity, warehouse_id}],
        |    "shipping_options": [{warehouse_id, options: [{provider, service_type, fee, delivery_days}]}]
        |  }
```

> Route plan có TTL 20 phút, không cần version determinism. Khi checkout submit, nếu plan không có trong cache (đã expire) → reject `CONTRACT_INVALID_ROUTE_PLAN` và yêu cầu re-preview. Chi tiết tại [Smart Routing Engine](smart_routing_engine.md).

---

## Luồng 5: Admin Restock (Nhập hàng vào kho)

**Ngữ cảnh:** Admin/WMS nhập hàng. Entry point khởi tạo inventory duy nhất.

### Luồng dữ liệu

```
[Admin / WMS]
        |  Request: {warehouse_id, sku_id, product_id, quantity}
        v
[Contract Guard]
        |  Validate warehouse_id tồn tại & active
        |  Validate quantity > 0
        v
[PostgreSQL Transaction]
        |  BEGIN;
        |  -- Upsert physical_inventory (gồm cả product_id denormalized)
        |  INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
        |    VALUES (:sku, :product, :wh, :qty, 0, NOW())
        |    ON CONFLICT (sku_id, warehouse_id)
        |    DO UPDATE SET actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
        |                  updated_at = NOW();
        |  -- Ledger audit trail
        |  INSERT INTO inventory_ledger (event_id, sku_id, product_id, warehouse_id, reference_type, quantity_change, balance_after, correlation_id, created_at)
        |    VALUES (:event_id, :sku, :product, :wh, 'ADMIN_RESTOCK', :qty, :new_balance, :correlation_id, NOW());
        |  -- Outbox for CDC
        |  INSERT INTO inventory_outbox (event_type='INVENTORY_STOCK_CHANGED', partition_key=:sku_id, payload=...);
        |  COMMIT;
        v
[Redis Cache Update]
        |  HMSET inventory:physic:{sku_id:warehouse_id}:stock
        |    total {new_actual}, reserved 0, available {new_actual}
        v
[Debezium CDC Connector]
        |  Read WAL of inventory_outbox
        |  Produce Kafka topic=t.inventory.events, key=sku_id
        v
[Kafka: t.inventory.events]
        v
[Product Service]
        |  Consume INVENTORY_STOCK_CHANGED
```

---

## Luồng 6: Return Restock (Nhận hàng hoàn về kho)

**Ngữ cảnh:** Shipping Service phát `SHIPMENT_RETURNED` khi kiện trả về kho.

### Điều kiện tiên quyết

Order đăng ký refund expectation trước bằng API nội bộ `POST /v1/inventory/returns/refund-expectations` (xem [Endpoints §3](inventory_endpoints.md)). Inventory đánh dấu các reservation row với `refund_expected = true, refund_request_id = X` để xác định khi nào đủ điều kiện emit `INVENTORY_RETURN_RECEIVED_ALL`.

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
        |  Layer 1: inventory_ledger.event_id UNIQUE
        |  (Đã lược bỏ inventory_refund_scopes.idempotency_key + Redis SETNX 2 lớp)
        v
[PostgreSQL Transaction]
        |  BEGIN;
        |  -- Restock physical per item
        |  UPDATE physical_inventory
        |    SET actual_quantity = actual_quantity + returned_qty,
        |        updated_at = NOW()
        |    WHERE sku_id = :sku AND warehouse_id = :wh;
        |  -- Mark reservation row as returned
        |  UPDATE inventory_reservations
        |    SET refund_returned_at = NOW(),
        |        updated_at = NOW()
        |    WHERE order_id = :order_id
        |      AND order_item_id = :order_item_id
        |      AND status = 'DEDUCTED'
        |      AND refund_returned_at IS NULL
        |    RETURNING refund_request_id;
        |  -- Ledger audit
        |  INSERT INTO inventory_ledger (event_id, sku_id, product_id, warehouse_id, reference_type, quantity_change, balance_after, correlation_id, created_at)
        |    VALUES (:event_id, :sku, :product, :wh, 'ORDER_RETURNED_RESTOCK', :qty, :new_balance, :correlation_id, NOW());
        |  -- Outbox for INVENTORY_STOCK_CHANGED
        |  INSERT INTO inventory_outbox
        |    (event_type='INVENTORY_STOCK_CHANGED', partition_key=:sku_id, payload=...);
        |
        |  -- Refund completion check
        |  SELECT COUNT(*) INTO :pending
        |  FROM inventory_reservations
        |  WHERE order_id = :order_id
        |    AND refund_request_id = :refund_request_id
        |    AND refund_expected = TRUE
        |    AND status = 'DEDUCTED'
        |    AND refund_returned_at IS NULL;
        |
        |  IF :pending = 0 THEN
        |    INSERT INTO inventory_outbox
        |      (event_type='INVENTORY_RETURN_RECEIVED_ALL', partition_key=:order_id, payload=...);
        |  END IF;
        |  COMMIT;
        v
[PostgreSQL: physical_inventory + inventory_ledger + inventory_reservations + inventory_outbox]
        v
[Redis Cache Update]
        |  HMSET inventory:physic:{sku_id:warehouse_id}:stock cập nhật total/available
        v
[Debezium CDC Connector]
        v
[Kafka: t.inventory.events]
        |-- INVENTORY_STOCK_CHANGED (change_type=ORDER_RETURNED_RESTOCK)
        v  [Product Service]
        |-- INVENTORY_RETURN_RECEIVED_ALL
           [Order Service]
           |  Trigger Payment refund step
```

### Contract Refund Expectation

- **Insert lần đầu:** UPDATE các `inventory_reservations` row match `(order_id, order_item_id[], status='DEDUCTED')` set `refund_expected = true, refund_request_id = X`.
- **Replay cùng `refund_request_id` + cùng tập item:** idempotent success.
- **Replay khác tập item cho cùng `(order_id, refund_request_id)`:** trả `409 REFUND_SCOPE_MISMATCH`.

### Nguyên tắc nghiệp vụ

- Nguồn kích hoạt restock duy nhất là `SHIPMENT_RETURNED`. Event `ORDER_REFUNDED` chỉ là trạng thái tài chính.
- `INVENTORY_RETURN_RECEIVED_ALL` là signal nghiệp vụ để Order mở bước gọi Payment refund, không thay thế event tồn kho `INVENTORY_STOCK_CHANGED`.

---

---

## Tóm tắt luồng produce event

| Event | Nguồn tạo | Kafka Topic | Cơ chế |
|:---|:---|:---|:---|
| `INVENTORY_STOCK_MUTATION` | Lua Script 1/2/3 | `t.inventory.events` | Redis Stream Outbox + Relay Worker |
| `INVENTORY_STOCK_CHANGED` | Ledger Worker / Restock / Return Consumer | `t.inventory.events` | PostgreSQL Outbox + Debezium CDC |
| `INVENTORY_RETURN_RECEIVED_ALL` | Return Consumer | `t.inventory.events` | PostgreSQL Outbox + Debezium CDC |
| `SHIPMENT_RETURNED` | Shipping Service | `t.shipping.events` | Kafka (consumed by Inventory) |

---

## Ghi chú: Khởi tạo Inventory Records

Inventory records (`physical_inventory`) **không auto-create** khi Product tạo SKU mới.

Entry point khởi tạo:
1. Admin Restock API.
2. WMS Integration API.

Khi restock lần đầu cho SKU, hệ thống upsert tạo record nếu chưa tồn tại. `product_id` được lưu cùng row lần restock đầu tiên và dùng cho mọi event về sau (denormalized, không cần lookup runtime).
