# Inventory Service - Danh Sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Workflows (API & Data Flow)](inventory_workflow.md)
* [Observability & SLO](inventory_observability_slo.md)

---

Inventory Service chạy các workers tập trung vào event relay, ghi sổ cái, recovery và đồng bộ cache.

## 1. Redis Stream Relay Worker (Core Ingress Worker)

**Loại hình:** Background worker chạy liên tục.
**Redis Stream Consumer Group:** `inventory-stream-relay`

**Sứ mệnh:** Đọc events từ Redis Stream Outbox và forward sang Kafka `t.inventory.events`.

1. Đọc stream key `inventory:stream:{sku_id:warehouse_id}:events`.
2. Parse payload + gán CloudEvents header.
3. Produce Kafka với key = `sku_id`.
4. Ack stream entry khi produce thành công.
5. Retry/backoff khi Kafka lỗi.

> Worker này là cầu nối bắt buộc để biến mutation Redis thành Kafka event mà không mất atomicity.

---

## 2. Kafka Ledger Worker (Core Persistence Worker)

**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `inventory-ledger-writer`
**Consume Topic:** `t.inventory.events`

**Sứ mệnh:** Ghi `inventory_ledger`, cập nhật `inventory_reservations`, cập nhật aggregate inventory và tạo `inventory_outbox` cho `INVENTORY_QUANTITY_CHANGED`.

### Luồng xử lý

1. `poll(100ms)` lấy batch.
2. Dedup bằng `inventory_event_idempotency` theo `event_id`.
3. Group by `sku_id`.
4. Nếu SKU đang `SUSPENDED` -> park vào `suspended_events`.
5. Tính toán balance trong RAM.
6. Bulk insert `inventory_ledger`.
7. Update `inventory_reservations`:
   - `INVENTORY_RESERVED` -> upsert `RESERVED`.
   - `INVENTORY_DEDUCTED` -> `DEDUCTED`.
   - `INVENTORY_RELEASED` -> `CANCELLED`.
8. Update `physical_inventory.reserved_quantity` và `logical_inventory` aggregate.
9. Insert `inventory_outbox` (`INVENTORY_QUANTITY_CHANGED`) cùng transaction.

---

## 3. Debezium CDC Connector (Thay thế Outbox Polling Worker)

**Loại hình:** Kafka Connect Source Connector (Đọc Real-time dưới tầng Đĩa cứng).

**Sứ mệnh:** Chuyển Event đã sinh ra từ bảng `inventory_outbox` sang Kafka Topic (`t.inventory.events`) với độ trễ < 5ms thông qua luồng bắt Read-Log (Write-Ahead-Log).

**Chi tiết luồng hoạt động:**
1. Khi Worker nội bộ có tạo mới Event (Ví dụ `INVENTORY_QUANTITY_CHANGED`), nó đồng thời Insert Record vào bảng `inventory_outbox` (với UUID `event_id`). DB cập nhật WAL dưới ổ cứng.
2. Debezium Connector gắn qua cổng Logical_Replication_Slot tiến hành đọc stream tự động, không chiếm dụng tài nguyên CPU cấp Application.
3. **[CRITICAL Kafka Routing]:** Debezium trích xuất giá trị cột `partition_key` (có thể là `sku_id` hoặc `order_id` tuỳ event type) làm Kafka Message Key. Hệ thống tuyệt đối không bóc JSON payload ở tầng Connect.
4. Retry/DLQ được xử lý 100% tại Kafka Connect, không dùng logic Polling/Update trạng thái db.

---

## 4. Suspended Events Recovery Worker (CronJob)

**Loại hình:** CronJob mỗi 5 phút.

1. Quét `suspended_events WHERE status = 'PARKED'`.
2. Kiểm tra `dlq_events` của SKU còn `UNRESOLVED` không.
3. Nếu đã resolve -> replay qua pipeline Ledger Worker.
4. Update `suspended_events.status = 'RECOVERED'` và unlock `suspended_sku_locks`.

---

## 5. Redis Cache Warm-Up Worker (Startup + CronJob)

**Loại hình:** chạy khi service khởi động + cron hàng ngày.

**Mục tiêu:** Nạp nóng cả logical và physical cache.

1. Load `logical_inventory` -> `inventory:logic:{sku_id}:stock`.
2. Load `physical_inventory` -> `inventory:physic:{sku_id:warehouse_id}:stock`.
3. Với hot SKU, build bucket keys cho read-path.

---

## 5.1. Product Mapping Sync Worker

**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `inventory-product-mapping-sync`
**Consume Topic:** `t.product.events`

**Sứ mệnh:** Đồng bộ bảng `sku_product_mapping` để Inventory luôn enrich đúng `product_id` khi phát event/outbox mà không phải gọi runtime sang Product Service.

1. Consume sự kiện thay đổi SKU/Product từ Product Service.
2. Upsert `sku_product_mapping (sku_id, product_id, source_version, updated_at)` theo version tăng dần.
3. Nếu detect out-of-order version, bỏ qua update cũ và ghi metric drift.

---

## 6. Reservation Expiry Scanner (CronJob)

**Loại hình:** CronJob mỗi 2 phút.

**Mục tiêu:** Giải phóng reservation quá hạn không bị deduct/cancel.

```sql
SELECT * FROM inventory_reservations
WHERE status = 'RESERVED'
  AND expires_at + INTERVAL '60 seconds' < NOW()
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

Xử lý mỗi row:
1. Gọi Release Lua script (reason = `RESERVATION_EXPIRED`).
2. Lua trả `1` -> event `INVENTORY_RELEASED` vào stream.
3. Update reservation `CANCELLED`.

---

## 7. Stock Reconciliation Worker (Weekly CronJob)

**Loại hình:** CronJob hàng tuần.

1. So khớp logical + physical giữa Redis và PostgreSQL.
2. Nếu drift -> ưu tiên DB làm chuẩn, rebuild cache.
3. Ghi metric drift + alert.

---

## 8. Hot SKU Flag Manager (CronJob + Real-time)

**Loại hình:** Cron mỗi 10 phút + trigger real-time.

1. Auto-flag hot SKU theo ngưỡng `reserved/total`.
2. Sync flag vào Redis + L1 cache.
3. Unflag khi traffic giảm.

---

## 9. Ops Cleanup Jobs

1. **Time-Window Clean Up**: Định kỳ dọn `inventory_outbox` cũ (vd. lưu giữ quá 7 ngày) dựa trên watermark an toàn đã replicate bởi Debezium để không cản trở dung lượng đĩa.
2. Archiver `suspended_events` cũ.
3. DLQ sweep và dashboard refresh.