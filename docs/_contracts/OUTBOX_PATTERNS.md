# Outbox Patterns (SSOT)

**Chuẩn về Outbox Pattern** cho mọi service trong hệ thống. Mọi service doc khác khi nhắc đến outbox phải tham chiếu file này.

---

## 1. Vấn đề (Dual-Write)

Khi một service cần ghi DB **VÀ ĐỒNG THỜI** publish event lên Kafka:

| Kịch bản | Rủi ro |
|---|---|
| DB OK, Kafka Fail | Event bị mất vĩnh viễn → downstream không biết |
| Kafka OK, DB Fail | Event sai sự thật → downstream xử lý data ảo |

## 2. Hai pattern chuẩn

### 2.1. CDC Pattern (Debezium Kafka Connect)

**Áp dụng cho:** Order, Inventory, Promotion.

**Cơ chế:**
1. Trong cùng PostgreSQL transaction nghiệp vụ, application INSERT vào bảng `*_outbox` (append-only).
2. Debezium CDC Connector đọc WAL qua replication slot, không cần polling application.
3. Route theo `aggregate_type` → topic đích; Kafka key = `partition_key` column.

**Schema chuẩn của bảng `*_outbox`:**

| Column | Type | Required | Ghi chú |
|---|---|:---:|---|
| `event_id` | UUID | ✅ | PK, unique |
| `schema_version` | INT | ✅ | Hiện tại = `1` |
| `aggregate_type` | VARCHAR(50) | ✅ | VD: `Order`, `Payment`, `Inventory`, `Promotion`, `MarkdownRule` |
| `aggregate_id` | VARCHAR(255) | ✅ | ID của entity |
| `event_type` | VARCHAR(100) | ✅ | VD: `ORDER_CREATED` |
| `partition_key` | VARCHAR(255) | ✅ | **Bắt buộc NOT NULL** — Kafka key |
| `payload` | JSONB | ✅ | Nội dung event |
| `created_at` | TIMESTAMPTZ | ✅ | Thời điểm tạo |

> **Cấm tuyệt đối:** KHÔNG thêm cột `status`, `retry_count`, `max_retries`, `next_retry_at`, `published_at`, `error_message`. Retry/DLQ quản lý ở Kafka Connect runtime.

**Ưu điểm:**
- Latency thấp (Debezium đọc trực tiếp WAL, < 5ms).
- Zero load trên application CPU (không polling).
- Crash giữa commit DB và publish Kafka không mất event (Debezium commit offset cùng transaction).

### 2.2. Direct-Produce Pattern (Kafka Producer inline)

**Áp dụng cho:** Promotion `COUPON_CLAIMED` (Flash Sale claim), Checkout (không có outbox), Payment Webhook dump (`t.payment.webhooks.raw`).

**Cơ chế:**
1. Sau khi xử lý nghiệp vụ trên Redis/PostgreSQL, application gọi `producer.send(...)` ngay trong request handler hoặc worker.
2. Không qua bảng outbox DB.

**Khi nào dùng:**
- Event từ runtime ngắn (Flash Sale claim, span < 1s).
- Event dump raw cần low-latency ingest (webhook).
- Service KHÔNG có PostgreSQL outbox (Checkout là stateless, không có DB).

**Yêu cầu bắt buộc:**
- Rescue job phát lại khi mất mạng (vd: `Pending Claims Sweeper` cho `COUPON_CLAIMED`).
- Mỗi lần republish tạo `event_id` mới nhưng giữ `claim_id` bất biến → consumer dedup 2 tầng (`event_id` + `claim_id`).

### 2.3. Polling Pattern (Outbox Polling Worker) — DEPRECATED

**Không được dùng cho service mới.** Lý do:
- Latency cao (poll interval 200-500ms × retry).
- Load thêm trên application CPU và DB connection.
- CDC hiện đại hơn, đã chứng minh ổn định ở scale lớn.

> Payment Service hiện đang dùng pattern này ở `outbox_events`. **Cần migrate sang CDC** trong giai đoạn tiếp theo (xem Phase 9 sync).

## 3. Pattern theo service

| Service | Pattern | Bảng outbox | Notes |
|---|---|---|---|
| Order | CDC | `outbox_events` (append-only, không có status/retry_count) | Đã chuẩn từ thiết kế ban đầu |
| Payment | Polling (legacy) | `outbox_events` (có `status/retry_count/max_retries/next_retry_at`) | **Cần migrate sang CDC** |
| Inventory | CDC | `inventory_outbox` (chỉ fields CDC) | Đã chuẩn |
| Promotion | CDC (default) + Direct-Produce (`COUPON_CLAIMED`) | `promotion_outbox` (chỉ fields CDC) | Đã chuẩn |
| Checkout | (không có outbox — stateless, không có DB) | — | Dùng Redis + Sync API |

## 4. Migration path cho Payment

Payment Service cần chuyển từ Polling sang CDC:

### 4.1. Bước 1: Schema cleanup
- Bỏ các cột polling: `status`, `retry_count`, `max_retries`, `next_retry_at`, `published_at`.
- Bảng `payment_outbox` chỉ giữ: `event_id`, `schema_version`, `aggregate_type`, `aggregate_id`, `event_type`, `partition_key`, `payload`, `created_at`.
- Xoá `Outbox Polling Worker` (Section 1 payment_workers.md).

### 4.2. Bước 2: Setup Debezium
- Tạo Kafka Connect Source cho `payment_outbox`.
- Route `PAYMENT_*` và `REFUND_*` events → `t.payment.events`.
- `aggregate_type = Payment` (luôn).
- `partition_key = order_id` (giữ strict ordering theo order).

### 4.3. Bước 3: Worker migration
- `Outbox Polling Worker` → tắt.
- DLQ/retry/backoff → chuyển sang Kafka Connect runtime.
- Cập nhật `payment_workers.md` và `payment_db_design.md` để bám theo CDC chuẩn.

### 4.4. Bước 4: Validation
- Smoke test: write → publish latency < 5s.
- Backfill: nếu có event pending ở polling queue, xử lý xong trước khi cắt polling.

## 5. Outbox Cleanup

| Service | Retention | Job |
|---|---|---|
| Order | 15 ngày | Outbox Trimmer CronJob (kèm CDC watermark check) |
| Payment | (sau migration) 15 ngày | (chưa có — cần thêm) |
| Inventory | 7 ngày (conservative) | Outbox Cleanup Job (track CDC offset) |
| Promotion | 7 ngày (chỉ xóa sau khi connector confirm đã đọc) | Outbox GC CronJob |

> **Bắt buộc:** Không được xóa outbox row trước khi CDC connector confirm đã publish. Dùng `connector_safe_watermark` (track từ connector lag metric) + buffer 1 ngày.

## 6. Cross-service consistency

- **Tất cả event đều qua outbox** (trừ Promotion `COUPON_CLAIMED` direct-produce + Checkout không có outbox + Payment webhook dump).
- **Tất cả event envelope** đều có `schema_version: 1` — backward compat chỉ thêm field, không xóa/rename.
- **Partition key đa dạng** theo event scope:
  - Order lifecycle: `order_id`
  - Order metrics: `product_id`
  - Order item: `order_item_id` (chỉ cho topic nội bộ nếu có)
  - Payment/Refund: `order_id` (để group cùng order)
  - Inventory stock: `sku_id`; Inventory return: `order_id`
  - Promotion markdown: `rule_id`; Promotion coupon: `order_id` (lifecycle) hoặc `claim_id` (claim)
- **Không tự ý suy luận partition key từ JSON payload** — luôn chốt ở column `partition_key` lúc ghi.

## 7. Khắc phục sự cố

| Symptom | Nguyên nhân thường gặp | Cách xử lý |
|---|---|---|
| Event bị duplicate ở downstream | CDC restart/rebalance giữa commit DB và commit offset | Consumer phải idempotent theo `event_id` |
| Event bị miss | Outbox row đã xóa trước khi connector đọc | KHÔNG BAO GIỜ xóa sớm; check `connector_safe_watermark` |
| Lag tăng cao | Debezium connector bị stuck | Restart connector, check Kafka Connect health |
| Schema mismatch | Producer thêm field mới nhưng consumer parse fail | Consumer phải `ignore unknown fields` |

## 8. Tóm tắt quy tắc vàng

1. **CDC là pattern mặc định** cho mọi service có PostgreSQL.
2. **Bảng outbox append-only** — không có cột polling, không có status, không có retry_count.
3. **`partition_key` column** chốt lúc ghi, không suy luận từ payload.
4. **`event_id` unique** cho mỗi lần publish (kể cả republish trong rescue).
5. **Direct-produce chỉ cho runtime event ngắn** (claim, webhook dump) — KHÔNG dùng cho business event.
6. **Cleanup retention ≥ CDC watermark + safety buffer** — không xóa sớm record chưa ship.
