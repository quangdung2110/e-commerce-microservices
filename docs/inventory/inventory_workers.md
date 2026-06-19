# Inventory Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
* [Database Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Internal Data Flow](inventory_workflow.md)
* [Smart Routing Engine](smart_routing_engine.md)

---

Inventory Service chạy 3 worker/cron chính, tập trung vào event relay, ghi sổ cái và dọn dẹp dữ liệu. Toàn bộ logic "tự phục hồi" (reconciliation, hot SKU, cache warm-up, expiry scanner) đã được lược bỏ — drift và inconsistency xử lý qua cảnh báo + manual intervention.

**Lưu ý về đơn giản hóa:** Bản này giảm từ 11 worker xuống còn 3 (1 Kafka Connect + 1 stream relay + 1 ledger consumer) + 1 cron cleanup. Phần lớn công việc trước đây nằm trong các worker giờ do chính API layer xử lý eager (lazy load) hoặc không cần (drift quá hiếm để tự động hoá).

---

## 1. Redis Stream Relay Worker

**Loại hình:** Background worker chạy liên tục.
**Redis Stream Consumer Group:** `inventory-stream-relay`

### Sứ mệnh

Đọc events từ Redis Stream Outbox và forward sang Kafka `t.inventory.events`. Worker này là cầu nối bắt buộc để biến mutation Redis thành Kafka event mà không mất atomicity.

### Luồng xử lý

```
1. READ  stream key inventory:stream:{sku_id:warehouse_id}:events
2. PARSE payload + gán CloudEvents header
3. PRODUCE Kafka topic=t.inventory.events, key=sku_id, event_type=INVENTORY_STOCK_MUTATION
4. XACK  stream entry khi produce thành công
5. RETRY backoff khi Kafka lỗi
```

### Chi tiết cơ chế

- Lua script (Reserve/Deduct/Release) thực hiện `XADD` vào stream cùng slot với stock hash, đảm bảo atomic: mutation Redis đã apply VÀ event đã được queue trong cùng một Lua execution.
- Relay Worker đọc stream và produce lên Kafka. Kafka key = `sku_id` để đảm bảo ordering per-SKU.
- Khi Kafka produce thất bại, Worker retry với exponential backoff cho đến khi thành công. Stream XACK chỉ xảy ra sau khi Kafka produce xác nhận.


> Lưu ý: Event `INVENTORY_STOCK_MUTATION` từ Relay Worker sẽ được consume lại bởi chính Kafka Ledger Worker (§2) của cùng service. Worker ledger filter theo `event_type` để chỉ xử lý MUTATION, ignore các event `INVENTORY_STOCK_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL` do chính nó hoặc DB outbox phát ra.

---

## 2. Kafka Ledger Worker

**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `inventory-ledger-writer`
**Consume Topic:** `t.inventory.events` (lọc `event_type = INVENTORY_STOCK_MUTATION`)
**Poll Interval:** 100ms

### Sứ mệnh

Ghi `inventory_ledger`, cập nhật `inventory_reservations` và `physical_inventory`, đồng thời tạo `inventory_outbox` cho `INVENTORY_STOCK_CHANGED` (event public aggregate per-SKU).

### Luồng xử lý chi tiết

```
1. poll(100ms) lấy batch messages từ Kafka
2. Filter: chỉ xử lý event_type=INVENTORY_STOCK_MUTATION
3. Dedup: kiểm tra inventory_ledger.event_id UNIQUE
4. Group messages by sku_id
5. Với mỗi sku_id:
   a. Kiểm tra Redis key inventory:suspended:{sku_id:warehouse_id}
      Nếu tồn tại (SKU đang SUSPENDED): bỏ qua batch này (event đã được Lua suspend-check,
      nên thực tế sẽ không có event MUTATION nào tới cho SKU suspended; lớp check này chỉ là safety)
   b. Fetch base balance từ inventory_ledger (bản ghi cuối cùng theo sku_id)
   c. Tính running balance trong RAM theo thứ tự event
6. Bulk INSERT inventory_ledger (reference_type theo change_type của event)
7. Bulk UPDATE inventory_reservations:
   - change_type=ORDER_RESERVED    -> upsert row mới, status=RESERVED
   - change_type=ORDER_DEDUCTED    -> UPDATE status='DEDUCTED'
   - change_type=ORDER_CANCELLED   -> UPDATE status='CANCELLED'
   - change_type=RESERVATION_EXPIRED -> UPDATE status='CANCELLED'
8. Bulk UPDATE physical_inventory.reserved_quantity, actual_quantity
9. Tính total_available_quantity per SKU:
   SELECT SUM(available_quantity) FROM physical_inventory WHERE sku_id = :sku
10. Bulk INSERT inventory_outbox
    event_type='INVENTORY_STOCK_CHANGED'
    partition_key=sku_id
    payload chứa: sku_id, product_id, available_quantity, change_type
11. PostgreSQL Transaction COMMIT (tất cả bước 6-10 trong cùng 1 transaction)
```

### Cơ chế idempotency

- Redis Lua script đã chặn duplicate ở tầng hot path (trả mã `2` cho idempotent hit).
- Ledger Worker dùng `inventory_ledger.event_id` UNIQUE constraint làm lớp bảo vệ cuối cùng.

---

## 3. Debezium CDC Connector

**Loại hình:** Kafka Connect Source Connector (đọc WAL — Write-Ahead Log).

### Sứ mệnh

Chuyển event từ bảng `inventory_outbox` sang Kafka Topic `t.inventory.events` với độ trễ < 5ms, hoàn toàn offload khỏi PostgreSQL application.

### Luồng hoạt động

```
1. APPLICATION: Ledger Worker / Return Consumer INSERT record vào inventory_outbox
   (UUID event_id, aggregate_type, event_type, partition_key, payload, created_at)
   DB ghi WAL dưới ổ cứng
2. DEBEZIUM: Connector gắn qua Logical Replication Slot
   Tiến hành đọc WAL stream TỰ ĐỘNG, không chiếm dụng tài nguyên CPU cấp Application
3. ROUTING: Debezium trích xuất giá trị cột partition_key
   (sku_id cho INVENTORY_STOCK_CHANGED, order_id cho INVENTORY_RETURN_RECEIVED_ALL)
   làm Kafka Message Key
   Hệ thống tuyệt đối KHÔNG bóc JSON payload ở tầng Connect
4. KAFKA: Produce message lên t.inventory.events
   partition được xác định bởi message key đã extract ở bước 3
5. RETRY/DLQ: 100% xử lý tại Kafka Connect runtime
```

---

## 4. Outbox Cleanup Job (Cron)

**Loại hình:** CronJob hàng ngày.

### Sứ mệnh

Xoá các row `inventory_outbox` đã được Debezium CDC publish thành công để giữ bảng outbox ở kích thước ổn định.

### Luồng xử lý

```
1. Cron trigger hàng ngày (ví dụ 02:00 AM UTC)
2. DELETE FROM inventory_outbox
   WHERE created_at < :watermark
   AND event_id IN (
     SELECT event_id FROM inventory_outbox
     WHERE created_at < :watermark
     LIMIT 10000
   );
3. Watermark đảm bảo chỉ xoá các row đã được CDC đọc xong
   (Debezium commit offset cùng transaction; offset lag < 1 phút trong steady state)
```

> Lưu ý: Có thể thay bằng TTL 7 ngày đơn giản (`created_at < NOW() - INTERVAL '7 days'`) vì CDC thường lag < 1 phút. Tuỳ ngữ cảnh deployment có thể chọn cách conservative hơn (track offset).

---

## 5. Kafka Return Consumer (Phase 14 — REMOVED)

> **Phase 14 đã loại bỏ worker này.** Trước Phase 14, worker này consume `SHIPMENT_RETURNED` từ `t.shipping.events` để restock physical_inventory khi hàng hoàn về kho. Sau khi Shipping Service bị xóa, restock giờ do admin thực hiện qua API `POST /v1/inventory/restock` với `reference_type='ORDER_RETURNED_RESTOCK'` (xem [Workflow §6](inventory_workflow.md) và [Endpoints §2](inventory_endpoints.md#2-post-v1inventoryrestock)).

Logic cũ (chỉ để tham khảo):

### Sứ mệnh (Phase 13 cũ)

Khi Shipping xác nhận hàng hoàn về kho, consumer này thực hiện restock physical_inventory, đánh dấu reservation `refund_returned_at`, kiểm tra completion và emit `INVENTORY_RETURN_RECEIVED_ALL` nếu đủ.

### Luồng xử lý (Phase 13 cũ)

```
1. poll lấy batch SHIPMENT_RETURNED từ Kafka
2. Với mỗi event:
   a. Idempotency: inventory_ledger.event_id UNIQUE
      Nếu đã tồn tại: skip
   b. PostgreSQL Transaction:
      - UPDATE physical_inventory SET actual_quantity += qty
      - UPDATE inventory_reservations SET refund_returned_at = NOW()
        WHERE order_id, order_item_id, status='DEDUCTED', refund_returned_at IS NULL
        RETURNING refund_request_id
      - INSERT inventory_ledger (reference_type=ORDER_RETURNED_RESTOCK)
      - INSERT inventory_outbox (event_type=INVENTORY_STOCK_CHANGED, partition_key=sku_id)
      - SELECT COUNT(*) WHERE refund_expected=TRUE AND refund_returned_at IS NULL
        for the same (order_id, refund_request_id)
      - IF count = 0: INSERT inventory_outbox (event_type=INVENTORY_RETURN_RECEIVED_ALL,
                                                partition_key=order_id)
      - COMMIT
   c. Update Redis cache: HMSET inventory:physic:{sku:wh}:stock
3. Chi tiết đầy đủ: [Workflow §6](inventory_workflow.md)
```

---

## Tóm tắt Workers

| Worker / Job | Loại | Tần suất | Dependency | Thay thế cho (bản cũ) |
|:---|:---|:---|:---|:---|
| Redis Stream Relay Worker | Continuous | Real-time | Redis Stream, Kafka | Giữ nguyên |
| Kafka Ledger Worker | Continuous | poll(100ms) | Kafka, PostgreSQL | Giữ nguyên (đã đơn giản poison handling) |
| Debezium CDC Connector | Kafka Connect | Real-time (WAL) | PostgreSQL WAL, Kafka | Giữ nguyên |
| ~~Kafka Return Consumer~~ | ~~Continuous~~ | ~~poll(100ms)~~ | ~~Kafka, PostgreSQL, Redis~~ | **REMOVED in Phase 14** (replaced by admin restock API) |
| Outbox Cleanup Job | CronJob | Daily | PostgreSQL | Giữ nguyên (gộp các cleanup job cũ) |

