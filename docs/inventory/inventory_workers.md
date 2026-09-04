# Inventory Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
* [Database Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Internal Data Flow](inventory_workflow.md)
* [Smart Routing Engine](smart_routing_engine.md)

---

Inventory Service chạy 4 worker/cron chính, tập trung vào event relay, ghi sổ cái và dọn dẹp dữ liệu. Toàn bộ logic "tự phục hồi" (reconciliation, hot SKU, cache warm-up, expiry scanner) đã được lược bỏ — drift và inconsistency xử lý qua cảnh báo + manual intervention.


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

Ghi `inventory_ledger`, cập nhật `inventory_reservations` và `physical_inventory`, rồi
materialize `sku_stock_state` và outbox stock snapshot v1 cho Search. `INVENTORY_STOCK_CHANGED`
chỉ còn là compatibility event trong thời gian migration.

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
10. UPSERT sku_stock_state: ghi tổng quantity, observed_at, tăng stock_revision đúng một lần.
11. Bulk INSERT inventory_outbox cho stock v1, key=sku_id, payload snapshot gồm
    product_id, sku_id, available_quantity, stock_revision, observed_at.
12. Trong migration có thể ghi thêm INVENTORY_STOCK_CHANGED legacy.
13. PostgreSQL Transaction COMMIT (tất cả bước 6-12 trong cùng 1 transaction)
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
1. APPLICATION: Ledger Worker / Restock API INSERT record vào inventory_outbox
   (UUID event_id, aggregate_type, event_type, partition_key, payload, created_at)
   DB ghi WAL dưới ổ cứng
2. DEBEZIUM: Connector gắn qua Logical Replication Slot
   Tiến hành đọc WAL stream TỰ ĐỘNG, không chiếm dụng tài nguyên CPU cấp Application
3. ROUTING: Debezium trích xuất giá trị cột partition_key
   (sku_id cho INVENTORY_STOCK_CHANGED, order_id cho INVENTORY_RETURN_RECEIVED_ALL và INVENTORY_SHIP_TO_CUSTOMER)
   làm Kafka Message Key
   Hệ thống tuyệt đối KHÔNG bóc JSON payload ở tầng Connect
4. KAFKA: Produce message lên t.inventory.events
   partition được xác định bởi message key đã extract ở bước 3
5. RETRY/DLQ: 100% xử lý tại Kafka Connect runtime
```

---

## 4. Outbox Cleanup Job (Cron)

**Loại hình:** CronJob chạy **đúng 1 lần/ngày lúc 02:00 UTC** (không polling liên tục).

### Sứ mệnh

Xoá các row `inventory_outbox` đã được Debezium CDC publish thành công để giữ bảng outbox ở kích thước ổn định.

### Luồng xử lý

```
1. Cron trigger đúng 1 lần/ngày lúc 02:00 UTC
2. Watermark = NOW() - INTERVAL '24 hours'
   (Debezium CDC lag < 1 phút trong steady state, nên 24h là đủ an toàn
    để đảm bảo mọi row đã được CDC đọc xong trước khi xoá)
3. Lặp lại cho đến khi không còn row nào thoả điều kiện
   (tránh DELETE một lần quá nhiều row gây lock bảng):
   DELETE FROM inventory_outbox
   WHERE created_at < :watermark
   AND event_id IN (
     SELECT event_id FROM inventory_outbox
     WHERE created_at < :watermark
     LIMIT 10000
   );
```

> Lưu ý: Có thể thay bằng TTL 7 ngày đơn giản (`created_at < NOW() - INTERVAL '7 days'`) vì CDC thường lag < 1 phút. Tuỳ ngữ cảnh deployment có thể chọn cách conservative hơn (track offset).

---

---

## Tóm tắt Workers

| Worker / Job | Loại | Tần suất | Dependency | Thay thế cho (bản cũ) |
|:---|:---|:---|:---|:---|
| Redis Stream Relay Worker | Continuous | Real-time | Redis Stream, Kafka | Giữ nguyên |
| Kafka Ledger Worker | Continuous | poll(100ms) | Kafka, PostgreSQL | Giữ nguyên (đã đơn giản poison handling) |
| Debezium CDC Connector | Kafka Connect | Real-time (WAL) | PostgreSQL WAL, Kafka | Giữ nguyên |
| Outbox Cleanup Job | CronJob | Daily | PostgreSQL | Giữ nguyên (gộp các cleanup job cũ) |

