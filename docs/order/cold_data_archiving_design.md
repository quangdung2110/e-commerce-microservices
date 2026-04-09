# Order Service - Cold Data Archiving Design

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workers](order_workers.md)
*   [CQRS Elasticsearch Design](cqrs_elasticsearch_design.md)

---

Tài liệu này mô tả chiến lược lưu trữ dữ liệu đơn hàng cũ (cold data) để giảm tải OLTP, đồng thời bảo đảm API vẫn đọc được lịch sử qua tuyến hot -> cold.

## 1. Mục tiêu

- Giảm kích thước bảng nóng trong PostgreSQL.
- Giữ truy vấn nghiệp vụ hiện tại nhanh và ổn định.
- Đảm bảo truy xuất lịch sử phục vụ đối soát/audit.
- Có khả năng replay deterministic khi archive batch bị lỗi.

## 2. Data Model cho Cold Archive

### 2.1. Bảng định tuyến đọc lạnh: `archived_orders_lookup`

```sql
CREATE TABLE archived_orders_lookup (
        order_id BIGINT PRIMARY KEY,
        archived BOOLEAN NOT NULL DEFAULT true,
        archive_batch_id VARCHAR(100) NOT NULL,
        s3_file_key VARCHAR(512) NOT NULL,
        order_created_at TIMESTAMPTZ,
        archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_archived_orders_lookup_batch
        ON archived_orders_lookup (archive_batch_id);

CREATE INDEX idx_archived_orders_lookup_created_at
        ON archived_orders_lookup (order_created_at DESC);
```

**Vai trò:**
- Tra nhanh theo `order_id` để biết có phải dữ liệu lạnh hay không.
- Định vị chính xác object trên S3 bằng `s3_file_key`.
- Hỗ trợ truy vết batch bằng `archive_batch_id`.

### 2.2. Bảng điều khiển batch: `archive_batches`

Lưu lifecycle mỗi batch archive/replay (`PENDING`, `RUNNING`, `FAILED`, `REPLAYING`, `COMPLETED`), kèm `attempt_count`, `failure_stage`, `failure_reason`.

### 2.3. Bảng membership batch: `archive_batch_items`

Lưu danh sách `order_id` thuộc mỗi batch và trạng thái từng item (`PENDING`, `ARCHIVED`, `DELETED`, `FAILED`) để replay theo `batch_id` mà không cần đẩy toàn bộ `order_id` vào event.

---

## 3. Quy trình Archive Chuẩn

1. CronJob tạo `archive_batch_id`, insert `archive_batches(status = RUNNING)`.
2. Quét đơn đủ điều kiện theo lô nhỏ (ví dụ 10000 đơn/lần):
     - `status IN ('COMPLETED', 'CANCELLED', 'REFUNDED')`
     - `updated_at < NOW() - INTERVAL '6 months'`
3. Ghi membership vào `archive_batch_items`.
4. Denormalize dữ liệu từ `orders`, `order_parcels`, `order_items`, `order_promotions` -> snapshot JSON/Parquet.
5. Upload S3 và verify checksum.
6. Upsert `archived_orders_lookup` cho từng đơn đã verify thành công.
7. Hard delete dữ liệu nóng (cascade) chỉ sau khi lookup đã được ghi thành công.
8. Chốt `archive_batch_items` và `archive_batches.status = COMPLETED`.

> Quy tắc bất biến: Không được xóa nóng trước khi có `s3_file_key` hợp lệ trong `archived_orders_lookup`.

---

## 4. Quy trình phục hồi truy vấn (Hot -> Cold Routing)

- API `GET /v1/orders/{orderId}`:
    1. Đọc từ DB nóng trước.
    2. Nếu miss, tra `archived_orders_lookup` theo `order_id`.
    3. Nếu hit, gọi cold read adapter đọc object theo `s3_file_key` và trả response.
- API `GET /v1/orders/search`:
    - Mặc định query nóng trong 90 ngày từ `orders_read`.
    - Nếu `from/to` vượt hot window, chuyển sang cold-search adapter hoặc trả metadata yêu cầu refine filter.

---

## 5. Replay Batch Lỗi

### 5.1. Event trigger

Khi batch fail ở bất kỳ stage nào (`SNAPSHOT`, `UPLOAD`, `VERIFY`, `DELETE`):
- Cập nhật `archive_batches.status = FAILED` + `failure_stage` + `failure_reason`.
- Insert outbox event `ORDER_ARCHIVE_BATCH_FAILED` (`aggregate_type = ArchiveBatch`, `partition_key = archive_batch_id`) để CDC publish lên `t.order.archive.retry` với payload chỉ gồm metadata:
    - `archive_batch_id`
    - `attempt`
    - `failure_stage`
    - `error_code`/`error_message`

### 5.2. Replay consumer

`order-archive-replay-consumer` nhận event, đọc `archive_batch_items` theo `archive_batch_id`, rồi replay idempotent cho các item `PENDING/FAILED`.

---

## 6. So sánh phương án chứa dữ liệu replay trong event

| Phương án | Ưu điểm | Nhược điểm | Kết luận |
|---|---|---|---|
| Nhét toàn bộ `order_id` vào event | Consumer không cần query DB | Payload lớn, dễ vượt hạn mức Kafka, replay nặng, coupling cao | Không chọn |
| Thêm `batch_id` vào bảng `orders` | Query batch từ bảng nóng thuận tiện | Ô nhiễm schema OLTP, tăng write amplification, khó bảo trì lifecycle | Không chọn |
| Dùng `archive_batches` + `archive_batch_items` (batch ledger) | Event nhẹ, replay deterministic, scale tốt, audit rõ | Thêm 2 bảng control plane | **Chọn** |

**Kết luận cuối cùng:** dùng batch ledger + event nhẹ chỉ chứa `archive_batch_id`.

---

## 7. An toàn dữ liệu và Idempotency

- Idempotent theo `archive_batch_id + attempt`.
- Replay guard 2 lớp:
    - Fast path: Redis `SETNX archive:replay:{archive_batch_id}:{attempt}`.
    - Durable path: `order_event_idempotency` với `action_type = ARCHIVE_BATCH_REPLAY`.
- Theo dõi đối soát tự động: số bản ghi archived, deleted, replayed theo từng batch.
