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
* [Inventory Events](inventory_events.md) — Inventory produce `INVENTORY_STOCK_*` events; từ Phase 14 không còn consume Shipping events.

---

Tài liệu này mô tả chi tiết luồng dữ liệu nội bộ của Inventory Service. Mỗi luồng ghi rõ dữ liệu được vận chuyển qua các thành phần như thế nào: `[Redis Lua]`, `[PostgreSQL]`, `[Redis Stream]`, `[Kafka]`, `[CDC]`.

## Quy ước chung

1. `warehouse_id` là bắt buộc cho mọi item mutation (reserve/deduct/release).
2. Multi-item request áp dụng chính sách **all-or-nothing**.
3. Event `INVENTORY_STOCK_MUTATION` được append vào Redis Stream trong chính Lua script, sau đó Relay Worker forward sang Kafka `t.inventory.events`.
4. Event `INVENTORY_STOCK_CHANGED`, `INVENTORY_RETURN_RECEIVED_ALL` và `INVENTORY_SHIP_TO_CUSTOMER` được insert vào PostgreSQL Outbox rồi Debezium CDC tự động publish lên cùng topic `t.inventory.events`.

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
        |  Request: {items[{sku_id, quantity}], customer_location}
        v
[Candidate Generation]
        |  SELECT sku_id, product_id, warehouse_id, available_quantity
        |  FROM physical_inventory
        |  WHERE sku_id = ANY(:sku_ids) AND available_quantity > 0
        |  Build feasible warehouse combinations (stock sufficient cho mỗi item)
        v
[Warehouse Selection Engine]
        |  Pick warehouse có stock đủ
        |  Tie-break: same-province preferred, warehouse_id ASC
        |  Output: route_plan với concrete warehouse_id cho mỗi item + product_id denormalized
        v
[Cache Route Plan]
        |  SET route_plan:{route_plan_id} = JSON, TTL = expires_at
        v
[Response]
        |  {
        |    "route_plan_id": "...",
        |    "expires_at": "...",
        |    "items": [{sku_id, product_id, quantity, warehouse_id}],
        |    "warehouse_assignments": [{warehouse_id, available_quantity}]
        |  }
```

> Thuật toán mới: chọn kho có stock đủ, ưu tiên same-province. Route plan TTL 20 phút. Khi checkout submit, nếu plan không có trong cache (đã expire) → reject `CONTRACT_INVALID_ROUTE_PLAN` và yêu cầu re-preview. Chi tiết tại [Warehouse Selection Engine](smart_routing_engine.md).

---

## Luồng 5: Admin Restock (Nhập hàng vào kho)

**Ngữ cảnh:** Admin nhập hàng. Entry point khởi tạo inventory duy nhất.

### Luồng dữ liệu

```
[Admin]
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
        |    total {new_actual}, reserved 0, available {new_actual}, updated_at_ms {now_ms}
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

**Ngữ cảnh:** Admin xác nhận đã nhận hàng hoàn từ khách. Quy trình: admin restock với `reference_type='ORDER_RETURNED_RESTOCK'` hoặc khách tự mang hàng tới kho.

### Điều kiện tiên quyết

Order đăng ký refund expectation trước bằng API nội bộ `POST /v1/inventory/returns/refund-expectations` (xem [Endpoints §3](inventory_endpoints.md)). Inventory đánh dấu các reservation row với `refund_expected = true, refund_request_id = X` để xác định khi nào đủ điều kiện emit `INVENTORY_RETURN_RECEIVED_ALL`.

### Luồng dữ liệu

```
[Admin hoặc buyer bring back]
        |  Request: {warehouse_id, sku_id, product_id, quantity, reference_type='ORDER_RETURNED_RESTOCK', order_id, order_item_id, refund_request_id}
        v
[Contract Guard]
        |  Validate warehouse_id tồn tại & active
        |  Validate quantity > 0
        |  Validate reservation (order_id, order_item_id, status='DEDUCTED', refund_expected=true) tồn tại
        v
[PostgreSQL Transaction]
        |  BEGIN;
        |  -- Restock physical per item (upsert để xử lý trường hợp SKU chưa từng được restock tại kho này)
        |  INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
        |    VALUES (:sku, :product, :wh, :qty, 0, NOW())
        |    ON CONFLICT (sku_id, warehouse_id)
        |    DO UPDATE SET
        |      actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
        |      updated_at = NOW();
        |  -- Mark reservation row as returned (chỉ mark nếu chưa được restock trước đó)
        |  UPDATE inventory_reservations
        |    SET refund_returned_at = NOW(),
        |        updated_at = NOW()
        |    WHERE order_id = :order_id
        |      AND order_item_id = :order_item_id
        |      AND status = 'DEDUCTED'
        |      AND refund_returned_at IS NULL
        |    RETURNING refund_request_id;
        |  -- Ledger audit (product_id lấy từ request, validate khớp với physical_inventory.product_id)
        |  INSERT INTO inventory_ledger (event_id, sku_id, product_id, warehouse_id, reference_type, quantity_change, balance_after, correlation_id, created_at)
        |    VALUES (:event_id, :sku, :product, :wh, 'ORDER_RETURNED_RESTOCK', :qty, :new_balance, :correlation_id, NOW());
        |  -- Outbox for INVENTORY_STOCK_CHANGED (cho Product Service cập nhật display cache)
        |  INSERT INTO inventory_outbox
        |    (event_type='INVENTORY_STOCK_CHANGED', partition_key=:sku_id, payload=...);
        |
        |  -- Refund completion check + build payload (2 query trong cùng transaction)
        |  -- (a) Pending count
        |  SELECT COUNT(*) INTO :pending
        |  FROM inventory_reservations
        |  WHERE order_id = :order_id
        |    AND refund_request_id = :refund_request_id
        |    AND refund_expected = TRUE
        |    AND status = 'DEDUCTED'
        |    AND refund_returned_at IS NULL;
        |  -- (b) Order item ids (toàn bộ item trong refund scope; tại emit time = received vì pending = 0)
        |  SELECT array_agg(order_item_id) INTO :order_item_ids
        |  FROM inventory_reservations
        |  WHERE order_id = :order_id
        |    AND refund_request_id = :refund_request_id
        |    AND refund_expected = TRUE
        |    AND status = 'DEDUCTED'
        |    AND refund_returned_at IS NOT NULL;
        |
        |  IF :pending = 0 THEN
        |    -- Unique constraint (order_id, refund_request_id) WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL'
        |    -- đảm bảo emit đúng 1 lần dù có concurrent insert (xem DB §1.5)
        |    INSERT INTO inventory_outbox (
        |      event_type='INVENTORY_RETURN_RECEIVED_ALL', partition_key=:order_id, refund_request_id=:refund_request_id,
        |      payload=jsonb_build_object(
        |        'order_id', :order_id,
        |        'refund_request_id', :refund_request_id,
        |        'order_item_ids', :order_item_ids,
        |        'confirmed_at', NOW()
        |      )
        |    );
        |  END IF;
        |  COMMIT;
        v
[PostgreSQL: physical_inventory + inventory_ledger + inventory_reservations + inventory_outbox]
        v
[Redis Cache Update]
        |  HMSET inventory:physic:{sku_id:warehouse_id}:stock
        |    total {new_actual}, reserved 0, available {new_actual}, updated_at_ms {now_ms}
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

- Phase 14 đã loại bỏ trigger từ `SHIPMENT_RETURNED`. Restock vật lý giờ do admin thực hiện qua `POST /v1/inventory/restock` với `reference_type='ORDER_RETURNED_RESTOCK'` hoặc khách tự mang hàng tới kho và admin xác nhận.
- `INVENTORY_RETURN_RECEIVED_ALL` là signal nghiệp vụ để Order mở bước gọi Payment refund, không thay thế event tồn kho `INVENTORY_STOCK_CHANGED`.

---

## Luồng 7: Ship to Customer (Xuất hàng cho khách)

**Ngữ cảnh:** Admin xác nhận kho đã xuất hàng cho khách. Endpoint: `POST /v1/inventory/ship-to-customer` (xem [Endpoints §4](inventory_endpoints.md#4-post-v1inventoryship-to-customer-phase-14--mới)).

**Lưu ý quan trọng:** Luồng này **không mutate tồn kho** (stock đã được DEDUCTED lúc payment success). Nó chỉ phát signal nghiệp vụ `INVENTORY_SHIP_TO_CUSTOMER` để Order Service chuyển `order_items.status` từ `PROCESSING` → `DELIVERED`.

### Luồng dữ liệu

```
[Admin]
        |  Request: {order_id, warehouse_id, shipped_at, courier_note?}
        v
[Contract Guard]
        |  Validate warehouse_id tồn tại & active
        |  Validate order_id có ít nhất 1 reservation DEDUCTED tại (order_id, warehouse_id) này
        |  (Nếu không có → trả 404 RESERVATION_NOT_FOUND, xem [Endpoints §4](inventory_endpoints.md#4-post-v1inventoryship-to-customer-phase-14--mới))
        v
[PostgreSQL Transaction]
        |  BEGIN;
        |  -- Chỉ insert outbox, KHÔNG update physical_inventory / inventory_reservations / inventory_ledger
        |  INSERT INTO inventory_outbox (
        |    event_id, schema_version, aggregate_type, aggregate_id,
        |    event_type, partition_key, payload, created_at
        |  ) VALUES (
        |    :event_id, 1, 'Inventory', :order_id,
        |    'INVENTORY_SHIP_TO_CUSTOMER', :order_id,
        |    '{"order_id":..., "warehouse_id":..., "shipped_at":..., "courier_note":...}'::jsonb,
        |    NOW()
        |  );
        |  COMMIT;
        v
[Debezium CDC Connector]
        |  Read WAL of inventory_outbox
        |  Produce Kafka topic=t.inventory.events, key=order_id
        v
[Kafka: t.inventory.events]
        v
[Order Service: order-inventory-ship-consumer]
        |  Consume INVENTORY_SHIP_TO_CUSTOMER
        |  Query `SELECT order_item_id FROM inventory_reservations WHERE order_id = :order_id AND warehouse_id = :warehouse_id AND status = 'DEDUCTED'`
        |  (LỌC warehouse_id là bắt buộc để tránh DELIVERED nhầm item ở kho khác trong multi-warehouse order)
        |  Với mỗi order_item_id: UPDATE order_items.status PROCESSING → DELIVERED (idempotent theo event_id)
        |  Auto-Complete check: nếu tất cả items của order (mọi warehouse) đều DELIVERED → ORDER_COMPLETED
```

### Tại sao không có Redis / Lua / Ledger

- **Không ghi `inventory_ledger`**: ship-to-customer không phải biến động tồn kho (stock đã DEDUCTED lúc payment), nên không có ledger entry. Việc ghi ledger với `reference_type` không tồn tại sẽ vi phạm invariant "mỗi ledger entry đại diện cho 1 biến động tồn kho".
- **Không qua Redis Lua**: ship-to-customer không mutate Redis hash, không cần Lua script.
- **Không cập nhật `inventory_reservations`**: status reservation vẫn là `DEDUCTED` (vì stock đã trừ thật). Trạng thái shipping thuộc về Order Service (`order_items.status`).
- **Không emit `INVENTORY_STOCK_CHANGED`**: vì không có thay đổi tồn kho.

### Nguyên tắc nghiệp vụ

- Idempotency: 2 admin clicks (cùng hay khác `event_id`) đều an toàn — Order Service consumer filter theo `event_id` và bỏ qua event đã xử lý.
- `affected_items` trong response là số `order_item_id` thuộc `(order_id, warehouse_id)` đang ở trạng thái DEDUCTED trên reservation (query tại API Layer trước khi emit outbox). Filter `warehouse_id` bắt buộc để không đếm nhầm item ở kho khác.

---

---

## Tóm tắt luồng produce event

| Event | Nguồn tạo | Kafka Topic | Cơ chế |
|:---|:---|:---|:---|
| `INVENTORY_STOCK_MUTATION` | Lua Script 1/2/3 | `t.inventory.events` | Redis Stream Outbox + Relay Worker |
| `INVENTORY_STOCK_CHANGED` | Ledger Worker / Restock API | `t.inventory.events` | PostgreSQL Outbox + Debezium CDC |
| `INVENTORY_RETURN_RECEIVED_ALL` | Return Restock API (admin gọi `/v1/inventory/restock` với `reference_type='ORDER_RETURNED_RESTOCK'`) | `t.inventory.events` | PostgreSQL Outbox + Debezium CDC |
| `INVENTORY_SHIP_TO_CUSTOMER` | Ship to Customer API (`POST /v1/inventory/ship-to-customer`) | `t.inventory.events` | Debezium CDC (DB outbox) |

---

## Ghi chú: Khởi tạo Inventory Records

Inventory records (`physical_inventory`) **không auto-create** khi Product tạo SKU mới.

Entry point khởi tạo:
1. Admin Restock API.

Khi restock lần đầu cho SKU, hệ thống upsert tạo record nếu chưa tồn tại. `product_id` được lưu cùng row lần restock đầu tiên và dùng cho mọi event về sau (denormalized, không cần lookup runtime).
