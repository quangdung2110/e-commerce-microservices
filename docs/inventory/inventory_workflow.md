# Inventory Service - Quy Trình Hoạt Động (Workflows & API Data Flow)

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Hybrid Routing Strategy](hybrid_routing_strategy.md)
* [Smart Routing Engine](smart_routing_engine.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Observability & SLO](inventory_observability_slo.md)

**Cross-Service References:**
* [Order Temporal Saga](../order/temporal_saga_design.md) — Activities gọi Reserve/Deduct/Release
* [Checkout Workflow](../checkout/checkout_workflow.md) — Preview stock check và submit
* [Shipping Workflow](../shipping/shipping_workflow.md#1-api-calculate-shipping-fee) — Routing Options gọi Shipping tính phí
* [Shipping Events](../shipping/shipping_events.md#34-shipment_returned) — Inventory consume `SHIPMENT_RETURNED` để nhập trả kho

---

Tài liệu này mô tả các workflow chính của Inventory Service. Mỗi flow ghi rõ boundary: `[Redis Lua]`, `[PostgreSQL]`, `[Redis Stream]`, `[Kafka]`.

## Quy ước chung trước khi đọc flow

1. `warehouse_id` là bắt buộc cho mọi item mutation (reserve/deduct/release).
2. Multi-item request áp dụng chính sách **all-or-nothing**.
3. Event `INVENTORY_RESERVED/DEDUCTED/RELEASED` không produce trực tiếp từ API vào Kafka; event được append vào Redis Stream trong chính Lua script, sau đó Relay Worker forward sang Kafka.

---

## 1. API: Reserve Inventory (Temporal Activity `ReserveInventoryActivity`)

**Ngữ cảnh:** Order Temporal Saga Pre-Payment bước 1.

1. **[Backend]** Nhận request (gRPC/REST):
   ```json
   {
     "order_id": 10001,
     "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
     "route_version": 3,
     "items": [
       {"sku_id": "sku-001", "product_id": "prod-uuid-001", "quantity": 2, "warehouse_id": 5}
     ]
   }
   ```
2. **[Contract Guard]** Validate:
   - mọi item phải có `warehouse_id`.
   - `route_plan_id` còn hiệu lực.
   - không cho phép mutation partial nếu có item sai contract.
3. **[All-or-Nothing Sequencing]** Sort items theo `(sku_id, warehouse_id)` và gọi [Redis Lua Script 1](inventory_redis_design.md#script-1-thuật-toán-khóa-giữ-chỗ-reserve-inventory) cho từng item.
4. **[Lua return-code handling]**
   - `1`: apply mới.
   - `2`: idempotent hit.
   - `0`: out_of_stock.
   - `-1`: invariant drift (fail-safe lane, không auto-heal).
5. **[Failure handling theo mã trả về]**
   - Nếu gặp `0`: service gọi Release script để rollback các item đã apply trong request hiện tại rồi trả lỗi nghiệp vụ tổng.
   - Nếu gặp `-1`: dừng request ngay, **không** chạy rollback mù quáng trên SKU lỗi; đưa SKU vào `suspended_sku_locks`, phát alert vận hành và tạo task reconciliation trước khi mở khóa lại.
6. **[Redis Stream Outbox]** Với item trả `1`, Lua script đã append `INVENTORY_RESERVED` vào stream cùng slot.
7. **[Async persistence]** Relay Worker forward event sang Kafka `t.inventory.events`; Ledger Worker consume và ghi `inventory_reservations` + `inventory_ledger`.
8. **[Response]**
   - Thành công: `RESERVED` + danh sách item.
   - Thất bại: `OUT_OF_STOCK` hoặc `INVARIANT_BREACH` + failed items.

---

## 2. API: Deduct Inventory (Temporal Activity `DeductInventoryActivity`)

**Ngữ cảnh:** Order Temporal Saga Post-Payment bước 1.

1. **[Backend]** Nhận request: `{order_id, items[{sku_id, quantity, warehouse_id}]}`.
2. **[Redis Lua Script 3]** ([Deduct Script](inventory_redis_design.md#script-3-xác-nhận-bán-đứt--tiền-trao-cháo-múc-commit-deduct)):
   - Trừ `reserved` và `total`.
   - Dùng idempotency key theo item.
3. **[Lua return-code handling]**
   - `1`: apply mới.
   - `2`: idempotent hit.
   - `0`: insufficient_reserved.
   - `-1`: invariant drift -> chuyển fail-safe lane (suspend SKU + reconciliation), không tự tiếp tục deduct.
4. **[Redis Stream Outbox]** Event `INVENTORY_DEDUCTED` được append bởi Lua cho item trả `1`.
5. **[Kafka + Ledger Worker]** Worker update `inventory_reservations.status = 'DEDUCTED'` + append ledger.
6. **[Response]** `DEDUCTED` hoặc lỗi nghiệp vụ.

> Không có DOM re-routing sau deduct. Nếu cần đổi kho phải release + reserve lại theo route plan mới.

---

## 3. API: Release Inventory (Temporal Compensation `RollbackInventoryActivity`)

**Ngữ cảnh:** Order cancelled/timeout hoặc scanner cleanup.

1. **[Backend]** Nhận request: `{order_id, items[{sku_id, quantity, warehouse_id}], reason}`.
2. **[Redis Lua Script 2]** ([Release Script](inventory_redis_design.md#script-2-hủy-đơn--hoàn-hàng-timeout-release--rollback-reservation)):
   - Trừ `reserved`, cộng `available`.
   - Không clamp im lặng.
3. **[Lua return-code handling]**
   - `1`: apply mới.
   - `2`: idempotent hit.
   - `0`: release_underflow.
4. **[Redis Stream Outbox]** Event `INVENTORY_RELEASED` được append khi trả `1`.
5. **[Kafka + Ledger Worker]** Update `inventory_reservations.status = 'CANCELLED'` + append ledger.

---

## 4. API: Get Routing Options (`POST /v1/inventory/routing-options`)

**Ngữ cảnh:** Checkout Preview gọi để lấy plan fulfillment đầy đủ.

1. **[Backend]** Nhận: `{items[{sku_id, quantity}], customer_location, service_level}`.
2. **[Candidate Generation]** Query tồn kho khả dụng theo kho (`physical_inventory.available_quantity`) và constraints.
3. **[Shipping Quote]** Inventory gọi [Shipping `POST /v1/shipping/calculate-batch`](../shipping/shipping_workflow.md#1-api-calculate-shipping-fee).
4. **[Scoring]** Áp dụng [Smart Routing Engine](smart_routing_engine.md) + tie-break determinism.
5. **[Response]** Trả route plan có version để submit tái sử dụng:
   ```json
   {
     "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
     "route_version": 3,
     "algorithm_version": "routing-v2.1.0",
     "inventory_snapshot_at": "2026-04-01T08:00:00Z",
     "shipping_rate_version": "2026-04-01T07:55:00Z",
     "expires_at": "2026-04-01T08:20:00Z",
     "items": [
       {
         "sku_id": "sku-001",
         "quantity": 2,
         "warehouse_id": 5,
         "routing_tier": "FAST_LANE"
       }
     ],
     "shipping_options": [
       {"provider": "GHN", "service": "STANDARD", "fee": 35000, "delivery_days": 2}
     ]
   }
   ```

---

## 5. API: Admin Restock (`POST /v1/inventory/restock`)

**Ngữ cảnh:** Admin/WMS nhập hàng.

1. **[Backend]** Validate `warehouse_id`, `sku_id`, `product_id`.
2. **[PostgreSQL Transaction]** Upsert cả physical + logical:
   ```sql
   BEGIN;
   INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
   VALUES (:sku, :product, :wh, :qty, 0, NOW())
   ON CONFLICT (sku_id, warehouse_id)
   DO UPDATE SET actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
                 updated_at = NOW();

   INSERT INTO logical_inventory (sku_id, product_id, total_quantity, reserved_quantity, version, updated_at)
   VALUES (:sku, :product, :qty, 0, 1, NOW())
   ON CONFLICT (sku_id)
   DO UPDATE SET total_quantity = logical_inventory.total_quantity + EXCLUDED.total_quantity,
                 version = logical_inventory.version + 1,
                 updated_at = NOW();

   INSERT INTO inventory_ledger (sku_id, warehouse_id, reference_type, quantity_change, balance_after, created_at)
   VALUES (:sku, :wh, 'VENDOR_RESTOCK', :qty, :new_balance, NOW());

   INSERT INTO inventory_outbox (... event_type = 'INVENTORY_QUANTITY_CHANGED', partition_key = :sku_id ...);
   COMMIT;
   ```
3. **[Redis]** Update cả physical key và logical key.
4. **[CDC Connector]** Debezium đọc bản ghi từ `inventory_outbox` cấu trúc theo WAL và tự động Publish lên Kafka `INVENTORY_QUANTITY_CHANGED`.

---

## 6. Workflow: Return Restock (Consume `SHIPMENT_RETURNED`)

**Ngữ cảnh:** Shipping Service phát `SHIPMENT_RETURNED` khi kiện trả về kho.

**Precondition contract với Order refund lane:**
- Order đăng ký refund scope trước bằng API nội bộ `POST /v1/inventory/returns/refund-scopes`.
- Inventory dùng scope này để xác định khi nào đủ điều kiện phát event `INVENTORY_RETURN_RECEIVED_ALL`.

**Contract tối thiểu cho `POST /v1/inventory/returns/refund-scopes`:**

Request:
```json
{
   "order_id": 10001,
   "refund_request_id": "rr-2026-0001",
   "required_order_item_ids": [2001, 2002]
}
```

Response (idempotent success):
```json
{
   "order_id": 10001,
   "refund_request_id": "rr-2026-0001",
   "scope_status": "OPEN",
   "required_count": 2,
   "received_count": 0
}
```

Rules:
- Lần đầu: insert row vào `inventory_refund_scopes`.
- Replay cùng tập `required_order_item_ids`: trả success idempotent.
- Replay khác tập item cho cùng `(order_id, refund_request_id)`: trả `409 REFUND_SCOPE_MISMATCH`.

1. **[Kafka Consumer]** Nhận event từ `t.shipping.events` với payload bắt buộc:
   `{shipment_id, order_id, order_item_id, returned_to_warehouse_id, items[]}`.
2. **[Idempotency]** Kích hoạt Dual-layer Guard: dùng Redis SETNX `inventory:return:{order_id}:{shipment_id}` để khoá nhanh, và DataBase table `inventory_event_idempotency` để chặn vĩnh viễn với `action_scope_key = shipment_id`.
3. **[PostgreSQL Transaction]** Với mỗi item:
   - `physical_inventory.actual_quantity += returned_qty` theo `returned_to_warehouse_id`.
   - `logical_inventory.total_quantity += returned_qty`.
   - append `inventory_ledger` với `reference_type = 'ORDER_RETURNED_RESTOCK'`.
   - insert `inventory_outbox` cho `INVENTORY_QUANTITY_CHANGED` (kèm partition_key = `sku_id`).
4. **[Redis]** Re-sync physical + logical keys theo số mới.
5. **[CDC Connector]** Debezium xử lý tiếp đoạn WAL và ném Message `INVENTORY_QUANTITY_CHANGED` (change_type=`ORDER_RETURNED_RESTOCK`) lên Kafka.
6. **[Refund Scope Aggregation]** Update bảng `inventory_refund_scopes` theo `order_id + refund_request_id`:
   - Đánh dấu `order_item_id` đã nhận.
   - Nếu `received_order_item_ids` bao phủ toàn bộ `required_order_item_ids`, transition scope `OPEN -> COMPLETED` và insert `inventory_outbox` `INVENTORY_RETURN_RECEIVED_ALL` (partition_key = `order_id`) để Debezium tự động publish lên `t.inventory.events`.
   - Event này chỉ phát một lần cho mỗi `refund_request_id`.

> Nguồn kích hoạt restock duy nhất là `SHIPMENT_RETURNED`. Event `ORDER_REFUNDED` chỉ là trạng thái tài chính.
>
> `INVENTORY_RETURN_RECEIVED_ALL` là signal nghiệp vụ để Order mở bước gọi Payment refund, không thay thế event tồn kho `INVENTORY_QUANTITY_CHANGED`.

---

## Ghi chú Thiết kế: Khởi tạo Inventory Records

Inventory records (`logical_inventory`, `physical_inventory`) không auto-create khi Product tạo SKU mới.

Entry point khởi tạo:
1. Admin Restock API.
2. WMS Integration API.

Khi restock lần đầu cho SKU, hệ thống upsert tạo record nếu chưa tồn tại.