# Review Service - Caching Schema

**Liên kết nhanh (Related Documents):**
*   [Database Schema](review_db_schema.md)
*   [Events Structure](review_events.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

Review Service là read-heavy nên caching phải đạt đồng thời 3 mục tiêu:

1. Giảm tải PostgreSQL.
2. Tránh stampede khi có invalidation dồn dập.
3. Giữ độ tươi dữ liệu ổn định cho list và aggregate.

## 1. Cache aggregate ratings

**Keys:**
*   `review:prod:agg:{product_id}`
*   `review:sku:agg:{product_id}:{sku_id}`

**Data type:** Redis String (JSON payload).

**TTL:** 24 giờ + deterministic jitter 1..300s theo hash `product_id`.

**Update strategy:** soft refresh, không hard delete.
*   Event áp dụng: `REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_REJECTED` (chỉ khi `previous_status='APPROVED'`).
*   Worker cập nhật DB trước, commit xong mới ghi đè cache bằng `SET`.
*   Cache miss luôn fallback về DB (source of truth).

## 2. Cache danh sách review (ưu tiên page 1)

**Version key:** `review:prod_ver:{product_id}`.

**Data key pattern:**
*   `review:prod:{product_id}:v:{version}:page:1:sort:{sort_type}`
*   `review:prod:{product_id}:v:{version}:filter:{filter_type}:page:1:sort:{sort_type}`

**TTL:** 1-2 giờ + jitter 1..300s.

**Invalidation bằng version bump (`INCR review:prod_ver:{product_id}`) khi có:**
*   `REVIEW_APPROVED`
*   `REVIEW_REJECTED`
*   `REVIEW_HIDDEN`
*   `SELLER_REPLY_CREATED`
*   `ACCOUNT_DELETED` (áp dụng cho các product có review của user bị xóa tài khoản)

**Trang 2+ luôn đi DB cursor:**
*   Sort newest: `WHERE id < :last_id_v7`
*   Sort top_helpful: `WHERE (helpful_count, id) < (:last_likes, :last_id_v7)`

## 3. Redis Bitmap cho like/unlike

### 3.1. Keys

*   `review:liked_bits:{review_id}`: bitmap dedup trạng thái vote theo `user_id`
*   `review:{review_id}:delta`: bộ đếm delta like/unlike
*   `review:dirty_delta_keys`: set review cần sync
*   `review:processing_deltas`: hash vùng đệm crash-safe

### 3.2. TTL và chống data loss

*   Rule ứng dụng: chặn Like/Unlike khi `NOW() - review.created_at > 6 tháng`.
*   Bitmap `review:liked_bits:*` có thể đặt TTL 6 tháng để dọn RAM.
*   Không xóa bitmap trong chu kỳ sync delta.
*   Chỉ xóa key delta sau khi move vào `processing_deltas` và DB commit thành công.

### 3.3. Atomic API actions

Không dùng chuỗi GETBIT -> SETBIT -> INCR/DECR ở app layer.

*   Like: Lua script atomically `SETBIT=1`, nếu bit cũ là 0 thì `INCR delta` + `SADD dirty`.
*   Unlike: Lua script atomically `SETBIT=0`, nếu bit cũ là 1 thì `DECR delta` + `SADD dirty`.

## 4. Đồng bộ helpful_count xuống DB

Flow crash-safe chuẩn:

1. Đầu chu kỳ đọc `HGETALL review:processing_deltas`.
2. Nếu rỗng, Lua move từ `dirty_delta_keys` vào `processing_deltas` và xóa key delta cũ.
3. Batch update DB `helpful_count = GREATEST(helpful_count + delta, 0)`.
4. Commit DB thành công mới `HDEL processing_deltas`.

**Tần suất khuyến nghị:** 30 giây/lần.

## 5. L1 cache invalidation (per-pod memory cache)

L1 cache nằm trong RAM từng pod, nên bắt buộc broadcast invalidation qua Redis Pub/Sub:

*   Channel: `review:l1:invalidate`
*   Payload gồm `product_id`, `reason`, `timestamp`

**Rule:** mọi pod phải nhận được thông điệp; không dùng Kafka consumer group cho mục đích này.

## 6. Consistency windows

| Luồng | Cache đọc chính | Cửa sổ stale cho phép | Cơ chế làm tươi |
|---|---|---|---|
| Aggregate rating | `review:prod:agg:*`, `review:sku:agg:*` | <= 10s (bình thường), <= 30s khi stale-while-revalidate | Worker overwrite sau commit + async refresh |
| Product review page 1 | `review:prod:*:page:1:*` | <= 60s | Version bump + lazy rebuild |
| Helpful sort | DB + cache page 1 | <= 30s | Delta sync worker chạy mỗi 30s |
| Anonymization display | Cache page 1 theo product | <= 60s | Batch product invalidation từ `ACCOUNT_DELETED` |

## 7. Tối ưu hiệu năng bổ sung

1. Prewarm page 1 cho top product traffic cao mỗi 5 phút.
2. Bật nén payload JSON ở response list.
3. Áp dụng stale-while-revalidate cho endpoint aggregate.
4. Theo dõi metrics bắt buộc:
   *   Cache hit rate
   *   P95/P99 latency đọc aggregate/list
   *   Size `review:dirty_delta_keys`
   *   Lag worker sync helpful
   *   Tỷ lệ invalidation lag giữa L1 và Redis

## Tóm lược

| Yêu cầu | Giải pháp | Tác dụng |
| :--- | :--- | :--- |
| Tốc độ đọc aggregate | Redis aggregate + soft refresh | Giảm truy vấn DB nóng |
| Tối ưu page 1 | Versioned cache + INCR invalidation | Tránh scan/delete key hàng loạt |
| Chống spam vote | Bitmap + Lua + delta sync crash-safe | Không cần bảng review_likes, giảm lock DB |
| Độ tươi top helpful | Sync 30s/lần | Trải nghiệm sort hữu ích mượt hơn |
| Tuân thủ privacy | Invalidate theo product khi anonymize | Tránh lộ tên user đã xóa tài khoản |
