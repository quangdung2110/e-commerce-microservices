# Inventory Service - Quy Trình Hoạt Động (Workflows & API Data Flow)

**Liên kết nhanh (Related Documents):**
*   [Database & Cache Schema](inventory_db_design.md)
*   [Redis Caching & Lua Scripts](inventory_redis_design.md)
*   [Hybrid Routing Strategy](hybrid_routing_strategy.md)
*   [Smart Routing Engine](smart_routing_engine.md)
*   [Events Structure & Kafka Topics](inventory_events.md)
*   [Background Workers & Jobs](inventory_workers.md)

**Cross-Service References:**
*   [Order Temporal Saga](../order/temporal_saga_design.md) — Activities gọi Reserve/Deduct/Release
*   [Checkout Workflow](../checkout/checkout_workflow.md) — Preview stock check
*   [Shipping Workflow](../shipping/shipping_workflow.md#1-api-calculate-shipping-fee) — Routing Options gọi Shipping tính phí
*   [Shipping Events](../shipping/shipping_events.md#34-shipment_returned) — Inventory consume `SHIPMENT_RETURNED` để nhập trả kho

---

Tài liệu này mô tả các Workflows chính của Inventory Service. Mỗi flow ghi rõ: `[Redis Lua]`, `[PostgreSQL]`, `[Kafka]`.

---

## 1. API: Reserve Inventory (Temporal Activity `ReserveInventoryActivity`)

**Ngữ cảnh:** Order Temporal Saga Pre-Payment bước 1.

1. **[Backend]** Nhận request (gRPC):
   ```json
   {
     "order_id": 10001,
     "items": [
       {"sku_id": "sku-001", "quantity": 2, "warehouse_id": 5, "parcel_id": 2001}
     ]
   }
   ```
2. **[Hybrid Routing Check]** Xác định `routing_tier` cho mỗi item:
   - Nếu `warehouse_id = null` (Hot SKU / Fast-Lane) → Tier 1: trừ `inventory:logic:{sku_id}`.
   - Nếu `warehouse_id != null` (Smart Routed) → Tier 2: trừ `inventory:physic:{sku_id}:warehouse:{warehouse_id}`.
3. **[Redis Lua Script 1]** ([Reserve Script](inventory_redis_design.md#script-1-thuật-toán-khóa-giữ-chỗ-reserve-inventory)):
   - Check `available >= quantity` → Trừ `available`, cộng `reserved`.
   - Trả `1` (OK) hoặc `0` (Out of stock).
4. **[Nếu thành công]** → Produce Kafka event `INVENTORY_RESERVED` (phục vụ Ledger Worker).
5. **[Ledger Worker consume]** → INSERT `inventory_reservations` + `inventory_ledger` vào PostgreSQL.
6. **[Response]** `OK` hoặc `OUT_OF_STOCK`.

---

## 2. API: Deduct Inventory (Temporal Activity `DeductInventoryActivity`)

**Ngữ cảnh:** Order Temporal Saga Post-Payment bước 1 — Sau khi thanh toán thành công.

1. **[Backend]** Nhận request: `{order_id, items[{sku_id, quantity, warehouse_id}]}`.
2. **[Redis Lua Script 3]** ([Deduct Script](inventory_redis_design.md#script-3-xác-nhận-bán-đứt--tiền-trao-cháo-múc-commit-deduct)):
   - Trừ `reserved` (vĩnh viễn rời khỏi hệ thống).
   - Idempotency: `SETNX inventory:idemp:order:{order_id}:deduct`.
3. **[Kafka]** Produce `INVENTORY_DEDUCTED`.
4. **[Ledger Worker]** Update `inventory_reservations.status = 'DEDUCTED'` + INSERT ledger.
5. **[DOM Re-routing]** Nếu Fast-Lane items (warehouse_id = null) → Job DOM gán kho cụ thể + trừ `physical_inventory`. Xem [Hybrid Routing Bước 3](hybrid_routing_strategy.md#bước-3-giai-đoạn-hậu-kỳ--trả-nợ-kho-bãi-dom-re-routing).

---

## 3. API: Release Inventory (Temporal Compensation `RollbackInventoryActivity`)

**Ngữ cảnh:** Order cancelled/timeout → Nhả reserved stock về available.

1. **[Backend]** Nhận: `{order_id, items[{sku_id, quantity, warehouse_id}]}`.
2. **[Redis Lua Script 2]** ([Release Script](inventory_redis_design.md#script-2-hủy-đơn--hoàn-hàng-timeout-release--rollback-reservation)):
   - Trừ `reserved`, cộng lại `available`.
   - Idempotency: `SETNX inventory:idemp:order:{order_id}:cancel`.
3. **[Kafka]** Produce `INVENTORY_RELEASED`.
4. **[Ledger Worker]** Update `inventory_reservations.status = 'CANCELLED'` + INSERT ledger.

---

## 4. API: Get Routing Options (`POST /v1/inventory/routing-options`)

**Ngữ cảnh:** Checkout Preview gọi để tính phí ship. Xem chi tiết tại [Smart Routing Engine](smart_routing_engine.md).

1. **[Backend]** Nhận: `{items[{sku_id, quantity}], customer_location}`.
2. **[Hot SKU Check]** Kiểm tra L1 Cache (Caffeine) cờ `is_hot` cho mỗi SKU.
3. **[Split Sub-Carts]** Theo [Hybrid Routing](hybrid_routing_strategy.md#2-giải-pháp-kiến-trúc-bóp-routing-cấp-độ-đơn-phụ-sub-cart-level-fallback):
   - Sub-Cart có Hot SKU → Fast-Lane (flat-rate shipping).
   - Sub-Cart không có → Smart Routing.
4. **[Smart Routing]** Cho từng Normal Sub-Cart:
   - Query `physical_inventory` → Tìm kho có hàng.
   - Gọi [Shipping Service `POST /v1/shipping/calculate-batch`](../shipping/shipping_workflow.md#1-api-calculate-shipping-fee) → Get shipping fees.
   - [Scoring Algorithm](smart_routing_engine.md#bước-2-scoring-algorithm-chấm-điểm-phân-bổ) → Chọn option tối ưu.
5. **[Response]** Fulfillment options (standard, express, phí, estimated delivery days).

---

## 5. API: Admin Restock (`POST /v1/inventory/restock`)

**Ngữ cảnh:** Admin/WMS nhập hàng — tăng `total_quantity` và `available_quantity`.

1. **[Backend]** Validate: `warehouse_id` exists, `sku_id` exists.
2. **[PostgreSQL]** Update `physical_inventory` + `logical_inventory`:
   ```sql
   BEGIN;
   UPDATE physical_inventory SET actual_quantity = actual_quantity + :qty WHERE sku_id = :sku AND warehouse_id = :wh;
   UPDATE logical_inventory SET total_quantity = total_quantity + :qty, version = version + 1 WHERE sku_id = :sku AND version = :ver;
   INSERT INTO inventory_ledger (sku_id, reference_type, quantity_change, ...) VALUES (:sku, 'VENDOR_RESTOCK', :qty, ...);
   COMMIT;
   ```
3. **[Redis]** Update cached values: `HINCRBY inventory:logic:{sku_id} total :qty` + `HINCRBY ... available :qty`.
4. **[Kafka]** Produce `INVENTORY_QUANTITY_CHANGED` (change_type = `VENDOR_RESTOCK`) → Product Service updates `display_quantity`.

---

## 6. Workflow: Return Restock (Consume `SHIPMENT_RETURNED`)

**Ngữ cảnh:** Shipping Service phát `SHIPMENT_RETURNED` khi kiện trả về kho.

1. **[Kafka Consumer]** Nhận event từ `t.shipping.events` với payload `{order_id, parcel_id, returned_to_warehouse_id, items[]}`.
2. **[Idempotency]** Check key `inventory:return:{shipment_id}` để chống xử lý lặp.
3. **[PostgreSQL]** Tăng lại tồn kho vật lý + logic cho các item được trả về:
   - `physical_inventory.actual_quantity += returned_qty`
   - `logical_inventory.total_quantity += returned_qty`
   - `logical_inventory.available_quantity += returned_qty`
4. **[Ledger]** Insert `inventory_ledger` với `reference_type = 'ORDER_RETURNED_RESTOCK'`.
5. **[Kafka]** Produce `INVENTORY_QUANTITY_CHANGED` (change_type = `ORDER_RETURNED_RESTOCK`) cho Product/Analytics.

---

## Ghi chú Thiết kế: Khởi tạo Inventory Records

> **Quyết định:** Inventory records (`logical_inventory`, `physical_inventory`) **KHÔNG tự động khởi tạo** khi Product Service tạo SKU mới.
>
> **Lý do:**
> 1. SKU mới tạo (trạng thái DRAFT) chưa có hàng vật lý.
> 2. Số lượng tồn kho ban đầu phải đến từ WMS/nhập kho thực tế.
> 3. Auto-init với quantity = 0 vô nghĩa và tạo noise cho monitoring.
>
> **Entry Point duy nhất:** Admin Restock API (`POST /v1/inventory/restock`) hoặc WMS Integration API. Khi restock lần đầu cho 1 SKU, hệ thống tự tạo `logical_inventory` + `physical_inventory` record (upsert).
>
> **Tương lai:** Nếu cần auto-init, thêm Kafka consumer cho `PRODUCT_CREATED` event. Nhưng hiện tại, flow thủ công là intentional.
