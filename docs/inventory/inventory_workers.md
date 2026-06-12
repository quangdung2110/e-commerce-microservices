# Inventory Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Internal Data Flow](inventory_workflow.md)
* [Observability & SLO](inventory_observability_slo.md)

---

Inventory Service chạy các workers tập trung vào event relay, ghi sổ cái, recovery và đồng bộ cache.

---

## 1. Redis Stream Relay Worker

**Loại hình:** Background worker chạy liên tục.
**Redis Stream Consumer Group:** `inventory-stream-relay`

### Sứ mệnh

Đọc events từ Redis Stream Outbox và forward sang Kafka `t.inventory.commands`. Worker này là cầu nối bắt buộc để biến mutation Redis thành Kafka event mà không mất atomicity.

### Luồng xử lý

```
1. READ  stream key inventory:stream:{sku_id:warehouse_id}:events
2. PARSE payload + gán CloudEvents header
3. PRODUCE Kafka topic=t.inventory.commands, key=sku_id
4. XACK  stream entry khi produce thành công
5. RETRY backoff khi Kafka lỗi
```

### Chi tiết cơ chế

- Lua script (Reserve/Deduct/Release) thực hiện `XADD` vào stream cùng slot với stock hash, đảm bảo atomic: mutation Redis đã apply VÀ event đã được queue trong cùng một Lua execution.
- Relay Worker đọc stream và produce lên Kafka. Kafka key = `sku_id` để đảm bảo ordering per-SKU.
- Khi Kafka produce thất bại, Worker retry với exponential backoff cho đến khi thành công hoặc đạt max retry.

### Metric cần theo dõi

- `inventory_stream_relay_lag_seconds` — độ trễ từ khi event được XADD đến khi được produce lên Kafka
- `inventory_stream_relay_success_total` — số event produce thành công
- `inventory_stream_relay_failed_total` — số event produce thất bại

---

## 2. Kafka Ledger Worker

**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `inventory-ledger-writer`
**Consume Topic:** `t.inventory.commands`
**Poll Interval:** 100ms

### Sứ mệnh

Ghi `inventory_ledger`, cập nhật `inventory_reservations`, cập nhật aggregate inventory và tạo `inventory_outbox` cho `INVENTORY_QUANTITY_CHANGED`.

### Luồng xử lý chi tiết

```
1. poll(100ms) lấy batch messages từ Kafka
2. Dedup: kiểm tra Redis idempotency keys + inventory_refund_scopes.idempotency_key
3. Group messages by sku_id
4. Với mỗi sku_id:
   a. Kiểm tra Redis key inventory:suspended:{sku_id:warehouse_id}
      Nếu tồn tại (SKU đang SUSPENDED):
        XADD vào inventory:failed:{sku_id:warehouse_id}:events
        CONTINUE (skip SKU này)
   b. Fetch base balance từ inventory_ledger (bản ghi cuối cùng theo sku_id)
   c. Tính running balance trong RAM theo thứ tự event
5. Bulk INSERT inventory_ledger (reference_type phân theo event_type)
6. Bulk UPDATE inventory_reservations:
   - INVENTORY_RESERVED  -> upsert row mới, status=RESERVED
   - INVENTORY_DEDUCTED  -> UPDATE status='DEDUCTED'
   - INVENTORY_RELEASED  -> UPDATE status='CANCELLED'
7. Bulk UPDATE physical_inventory.reserved_quantity (per warehouse)
8. Bulk UPDATE logical_inventory aggregate (total_quantity, reserved_quantity)
9. Bulk INSERT inventory_outbox
   event_type='INVENTORY_QUANTITY_CHANGED'
   partition_key=sku_id
   payload chứa: sku_id, product_id, available_quantity, previous_available_quantity
10. PostgreSQL Transaction COMMIT (tất cả bước 5-9 trong cùng 1 transaction)
```

### Cơ chế idempotency

- Redis Lua script đã chặn duplicate ở tầng hot path (trả mã `2` cho idempotent hit).
- Ledger Worker dùng `inventory_refund_scopes.idempotency_key` làm lớp bảo vệ thứ hai cho luồng return (đã gộp từ `inventory_event_idempotency`).

### Poison handling trong batch (Redis-based)

- Nếu toàn bộ batch fail: chia nhỏ bằng binary split (logarithmic) để xác định event gây lỗi.
- Event gây lỗi: `XADD` vào Redis Stream `inventory:failed:{sku_id:warehouse_id}:events` với `failure_reason=DLQ`.
- SKU đặt vào Redis: `SET inventory:suspended:{sku_id:warehouse_id}` TTL=24h.
- Event đi sau cùng SKU trong batch: `XADD` vào Redis Stream failed với `failure_reason=SKU_SUSPENDED`.

---

## 3. Debezium CDC Connector

**Loại hình:** Kafka Connect Source Connector (đọc WAL — Write-Ahead Log).

### Sứ mệnh

Chuyển event từ bảng `inventory_outbox` sang Kafka Topic (`t.inventory.events`) với độ trễ < 5ms, hoàn toàn offload khỏi PostgreSQL application.

### Luồng hoạt động

```
1. APPLICATION: Ledger Worker INSERT record vào inventory_outbox
   (UUID event_id, aggregate_type, event_type, partition_key, payload, created_at)
   DB ghi WAL dưới ổ cứng
2. DEBEZIUM: Connector gắn qua Logical Replication Slot
   Tiến hành đọc WAL stream TỰ ĐỘNG, không chiếm dụng tài nguyên CPU cấp Application
3. ROUTING: Debezium trích xuất giá trị cột partition_key
   (sku_id cho INVENTORY_QUANTITY_CHANGED, order_id cho INVENTORY_RETURN_RECEIVED_ALL)
   làm Kafka Message Key
   Hệ thống tuyệt đối KHÔNG bóc JSON payload ở tầng Connect
4. KAFKA: Produce message lên t.inventory.events
   partition được xác định bởi message key đã extract ở bước 3
5. RETRY/DLQ: 100% xử lý tại Kafka Connect runtime
   Không dùng logic Polling/Update trạng thái db
```

### Tại sao không dùng polling?

- Polling tốn CPU application liên tục để SELECT + UPDATE status.
- CDC đọc WAL trực tiếp từ storage engine — zero additional load trên PostgreSQL.
- Event được publish ngay khi WAL flush, độ trễ < 5ms so với polling 100ms+.

---

## 4. Suspended Events Recovery Worker

**Loại hình:** CronJob mỗi 5 phút.

### Sứ mệnh

Replay các event đã bị park trong Redis Stream `inventory:failed:*` khi SKU được un-suspend (TTL expired hoặc unlock thủ công).

### Luồng xử lý

```
1. SCAN inventory:suspended:* keys
   -- Tìm các SKU đang bị suspend
2. Với mỗi suspended key:
   a. Kiểm tra TTL:
      - Nếu TTL đã hết (Redis tự động xóa key):
        XREADBLOCKING inventory:failed:{sku_id:warehouse_id}:events
        Replay event qua Kafka t.inventory.commands
        DEL inventory:suspended:{sku_id:warehouse_id} (nếu key còn)
      - Nếu TTL còn: CONTINUE (chờ)
   b. Kiểm tra failure_reason của entry đầu tiên:
      - DLQ: chờ human review, KHÔNG auto-replay
      - SKU_SUSPENDED: auto-replay khi key expired
3. Với trường hợp unlock thủ công:
   DEL inventory:suspended:{sku_id:warehouse_id}
   THEN XREAD + replay như bước 2a
```

### Metric cần theo dõi

- `inventory_suspended_skus_total` — số Redis keys `inventory:suspended:*`
- `inventory_failed_events_parked_total` — số entries trong `inventory:failed:*` streams

---

## 5. Redis Cache Warm-Up Worker

**Loại hình:** Chạy khi service khởi động + cron hàng ngày.

### Sứ mệnh

Nạp nóng cả logical và physical cache từ PostgreSQL lên Redis để đảm bảo hot path luôn có data sẵn sàng.

### Luồng xử lý

```
1. SELECT sku_id, total_quantity, reserved_quantity, available_quantity
   FROM logical_inventory ORDER BY updated_at DESC
2. Với mỗi logical record:
   HMSET inventory:logic:{sku_id}:stock
     total, reserved, available, updated_at_ms
3. SELECT sku_id, warehouse_id, actual_quantity, reserved_quantity, available_quantity
   FROM physical_inventory ORDER BY updated_at DESC
4. Với mỗi physical record:
   HMSET inventory:physic:{sku_id:warehouse_id}:stock
     total, reserved, available, updated_at_ms
5. Với hot SKU: build bucket keys cho read-path
```

---

## 6. Product Mapping Sync Worker

**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `inventory-product-mapping-sync`
**Consume Topic:** `t.product.events`

### Sứ mệnh

Đồng bộ bảng `sku_product_mapping` để Inventory luôn enrich đúng `product_id` khi phát event/outbox mà không phải gọi runtime sang Product Service.

### Luồng xử lý

```
1. Consume sự kiện thay đổi SKU/Product từ Product Service
2. Parse payload: {sku_id, product_id, version}
3. INSERT INTO sku_product_mapping ... ON CONFLICT DO UPDATE
   WHERE EXCLUDED.source_version > sku_product_mapping.source_version
4. Nếu version cũ hơn: bỏ qua + ghi metric drift
```

---

## 7. Reservation Expiry Scanner

**Loại hình:** CronJob mỗi 2 phút.

### Sứ mệnh

Giải phóng reservation quá hạn không bị deduct/cancel.

### Luồng xử lý

```
1. SELECT FROM inventory_reservations
   WHERE status = 'RESERVED'
     AND expires_at + INTERVAL '60 seconds' < NOW()
   LIMIT 100 FOR UPDATE SKIP LOCKED
2. Với mỗi expired reservation:
   a. Gọi Lua Script 2 (Release)
   b. Lua trả 1 -> event INVENTORY_RELEASED đã XADD vào stream
   c. UPDATE inventory_reservations SET status = 'CANCELLED'
   d. Relay Worker -> Kafka -> Ledger Worker ghi ledger
```

---

## 8. Stock Reconciliation Worker

**Loại hình:** CronJob hàng tuần.

### Sứ mệnh

Phát hiện và tự động khắc phục drift giữa Redis cache và PostgreSQL source of truth.

### Luồng xử lý

```
1. SELECT FROM physical_inventory
2. So sánh Redis vs DB: actual_quantity, reserved_quantity
3. Nếu drift > threshold: ưu tiên DB làm chuẩn, rebuild Redis cache
4. Tương tự với logical_inventory vs inventory:logic:*:stock
5. Ghi báo cáo drift metrics
```

---

## 9. Hot SKU Flag Manager

**Loại hình:** Cron mỗi 10 phút + trigger real-time.

### Sứ mệnh

Auto-flag hot SKU để ưu tiên xử lý và tối ưu routing.

### Luồng xử lý

```
1. Cron 10 phút: query top SKU theo reserved_ratio
2. Real-time: trigger khi reserved tăng đột biến
3. SET inventory:hot:{sku_id}:flag = '1' TTL 1h
4. Unflag khi traffic giảm
```

---

## 10. Ops Cleanup Jobs

### 10.1. Time-Window Outbox Cleanup

| Chi tiết | Mô tả |
|:---|:---|
| Tần suất | CronJob hàng ngày |
| Logic | DELETE FROM inventory_outbox WHERE created_at < :watermark |
| An toàn | Chỉ xóa event đã được Debezium publish (watermark đảm bảo không cản trở CDC) |

### 10.2. Failed Events Stream Cleanup (Redis-based)

| Chi tiết | Mô tả |
|:---|:---|
| Tần suất | CronJob hàng ngày |
| Logic | XTRIM inventory:failed:* MAXLEN ~1000 |
| Lưu giữ | 7 ngày qua TTL trên key |

### 10.3. Ops Dashboard Refresh

| Chi tiết | Mô tả |
|:---|:---|
| Tần suất | CronJob hàng giờ |
| Logic | SCAN inventory:suspended:* + XLEN inventory:failed:* |

---

## Tóm tắt Workers

| Worker | Loại | Tần suất | Dependency |
|:---|:---|:---|:---|
| Redis Stream Relay Worker | Continuous | Real-time | Redis Stream, Kafka |
| Kafka Ledger Worker | Continuous | poll(100ms) | Kafka, PostgreSQL |
| Debezium CDC Connector | Kafka Connect | Real-time (WAL) | PostgreSQL WAL, Kafka |
| Suspended Events Recovery | CronJob | 5 phút | Redis, Kafka |
| Redis Cache Warm-Up | Startup + Cron | Daily | PostgreSQL, Redis |
| Product Mapping Sync | Continuous | Real-time | Kafka, PostgreSQL |
| Reservation Expiry Scanner | CronJob | 2 phút | PostgreSQL, Redis, Kafka |
| Stock Reconciliation | CronJob | Weekly | PostgreSQL, Redis |
| Hot SKU Flag Manager | CronJob | 10 phút | PostgreSQL, Redis |
| Outbox Cleanup | CronJob | Daily | PostgreSQL |
| Failed Events Stream Cleanup | CronJob | Daily | Redis |
