# Inventory Service - Danh Sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Database & Cache Schema](inventory_db_design.md)
*   [Redis Caching & Lua Scripts](inventory_redis_design.md)
*   [Events Structure & Kafka Topics](inventory_events.md)
*   [Workflows (API & Data Flow)](inventory_workflow.md)

---

Inventory Service chạy các workers tập trung vào: ghi sổ cái (Ledger) tốc độ cao, khôi phục sự kiện bị lỗi, và đồng bộ cache.

---

## 1. Kafka Ledger Worker (Core Worker)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `inventory-ledger-writer`
**Consume Topic:** `t.inventory.events` (internal events từ Reserve/Deduct/Release)

**Sứ mệnh:** Ghi sổ cái `inventory_ledger` bằng Bulk Insert Batching, đồng thời UPDATE `inventory_reservations` và `logical_inventory` trong PostgreSQL. Đây là worker quan trọng nhất.

**Chi tiết luồng hoạt động:** (Xem công thức Batching tại [DB Design Section 4](inventory_db_design.md#4-kiến-trúc-event-streaming-và-xử-lý-sổ-cái-khối-lượng-lớn-batching-ledger))
1. **Long Polling:** `poll(100ms)` → Lấy batch 100 events.
2. **Group by `sku_id`:** `Map<sku_id, List<Events>>`.
3. **Check suspended locks:** bỏ qua các `sku_id` đang `SUSPENDED` trong bảng `suspended_sku_locks`, đẩy event vào `suspended_events`.
4. **Fetch current `balance_after`:** `SELECT sku_id, MAX(balance_after) FROM inventory_ledger WHERE sku_id IN (:skuIds) GROUP BY sku_id`.
5. **Tính toán RAM:** Cộng/trừ tuyến tính cho từng SKU.
6. **Bulk Insert `inventory_ledger`:** `executeBatch()` 100 records.
7. **Update `inventory_reservations`:** 
   - `INVENTORY_RESERVED` → INSERT reservation record.
   - `INVENTORY_DEDUCTED` → UPDATE `status = 'DEDUCTED'`.
   - `INVENTORY_RELEASED` → UPDATE `status = 'CANCELLED'`.
8. **Update `logical_inventory`:** Reconcile `reserved_quantity` và `total_quantity`.
9. **Produce downstream event:** `INVENTORY_QUANTITY_CHANGED` → Product Service.

**Throughput:** ~6.000 TPS/thread. Scale bằng thêm Kafka partitions + consumer instances.

---

## 2. Suspended Events Recovery Worker (CronJob)
**Loại hình:** CronJob mỗi 5 phút.

**Sứ mệnh:** Khôi phục sự kiện bị "đóng băng" trong Parking Lot sau khi Poison Pill đã được fix.

**Chi tiết:** (Xem [Parking Lot Design](inventory_db_design.md#42-xử-lý-lỗi-thuốc-độc-poison-pill-với-phòng-chờ-parking-lot))
1. Quét `suspended_events WHERE status = 'PARKED'`:
   ```sql
   SELECT DISTINCT sku_id FROM suspended_events WHERE status = 'PARKED';
   ```
2. Check xem SKU đã được unlock chưa (DLQ event đã fix):
   ```sql
   SELECT COUNT(*) FROM dlq_events WHERE sku_id = :sku AND status = 'UNRESOLVED';
   ```
3. Nếu `count = 0` (đã fix) → Bốc suspended events ra, re-process qua Ledger Worker pipeline.
4. Update `suspended_events SET status = 'RECOVERED'`.

---

## 3. Redis Cache Warm-Up Worker (Startup + CronJob)
**Loại hình:** Chạy khi service khởi động + CronJob hàng ngày (3:00 AM).

**Sứ mệnh:** Nạp tồn kho từ PostgreSQL vào Redis, đảm bảo cache luôn hot.

```sql
SELECT sku_id, total_quantity, reserved_quantity, 
  (total_quantity - reserved_quantity) as available_quantity
FROM logical_inventory
WHERE available_quantity > 0;
```

→ Pipeline `HSET inventory:logic:{sku_id} total :total reserved :reserved available :available`.

**Bucketing:** Với Hot SKUs (cờ `is_hot = true`): chia available ra 10 buckets:
```
For bucket_id in 1..10:
    HSET inventory:logic:{sku_id}:bucket:{bucket_id} 
      total (total/10) reserved 0 available (available/10)
```

---

## 4. Reservation Expiry Scanner (CronJob)
**Loại hình:** CronJob mỗi 2 phút.

**Sứ mệnh:** Quét reservations quá 15 phút mà chưa nhận Deduct/Cancel event → Auto-release.

```sql
SELECT * FROM inventory_reservations 
WHERE status = 'RESERVED' AND expires_at < NOW()
LIMIT 100 FOR UPDATE SKIP LOCKED;
```

**Xử lý:**
1. Gọi Redis Lua Script Release cho mỗi reservation quá hạn.
2. Produce `INVENTORY_RELEASED` (reason: `RESERVATION_EXPIRED`).
3. Update `inventory_reservations.status = 'CANCELLED'`.

> [!NOTE]
> Scanner này là **hàng rào cuối cùng** bảo vệ stock không bị khóa vĩnh viễn, song song với Temporal Timer 15 phút ở Order Service. Cơ chế dual-protection: Temporal cancel + Scanner cleanup.

---

## 5. Stock Reconciliation Worker (Weekly CronJob)
**Loại hình:** CronJob hàng tuần (Chủ nhật 4:00 AM).

**Sứ mệnh:** So khớp Redis cache vs PostgreSQL DB → Phát hiện và sửa drift.

```sql
SELECT li.sku_id, li.total_quantity, li.reserved_quantity, li.available_quantity
FROM logical_inventory li;
```

→ Compare với `HGETALL inventory:logic:{sku_id}`:
- Nếu khớp → OK.
- Nếu lệch → **Ưu tiên DB** (PostgreSQL là nguồn sự thật) → Re-sync Redis → Alert cho ops team.

---

## 6. Hot SKU Flag Manager (CronJob + Real-time)
**Loại hình:** CronJob mỗi 10 phút + Real-time khi Flash Sale bắt đầu.

**Sứ mệnh:** Quản lý cờ `is_hot` cho SKUs:
- **Flash Sale config:** Admin đánh dấu SKU sắp Flash Sale → Set `is_hot = true` trên Redis + Caffeine L1 Cache.
- **Auto-detect:** SKU có `reserved/total > 0.7` (70% đã bán/giữ) → Auto-flag `is_hot`.
- **Auto-unflag:** Flash Sale hết → Remove flag.
