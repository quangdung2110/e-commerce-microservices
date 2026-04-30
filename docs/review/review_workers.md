# Review Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](review_workflow.md)
*   [Background Workers & Jobs](review_workers.md)
*   [Caching Schema](review_caching_schema.md)
*   [Database Schema](review_db_schema.md)
*   [Events Structure](review_events.md)

---
Review Service có tải đọc cao, ghi bất đồng bộ nhiều và yêu cầu dữ liệu đánh giá hội tụ nhanh. Tài liệu này mô tả đầy đủ các worker để đảm bảo:

1. Không mất event khi publish (Outbox + CDC).
2. Không ghi sai thứ tự trong môi trường at-least-once.
3. Không drift giữa PostgreSQL, Redis và các read-model downstream.

## Tổng Hợp Kafka Topics

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| **Produce** | `t.review.events` | — | Topic chính phát mọi event nghiệp vụ của Review |
| **Produce** | `t.review.retry` | — | Retry topic nội bộ cho replay có kiểm soát |
| **Produce** | `t.review.dlq` | — | Parking lot cho contract violation/poison message |
| **Consume** | `t.order.events` | `review-order-sync` | Cấp quyền review theo item đã mua |
| **Consume** | `t.user.events` | `review-privacy-sync` | Nhận `ACCOUNT_DELETED` để ẩn danh dữ liệu tác giả |
| **Consume** | `t.review.events` | `review-rating-aggregator` | Self-consume để cập nhật aggregate rating |
| **Consume** | `t.review.retry` | `review-retry-replayer` | Replay lỗi tạm thời có kiểm soát |

> **Policy đã chốt:** Review Service **không consume** `USER_PROFILE_UPDATED` để đồng bộ đổi tên hiển thị. `user_name` là snapshot tại thời điểm tạo review; chỉ xử lý ẩn danh khi nhận `ACCOUNT_DELETED`.

## Nguyên tắc vận hành bắt buộc

1. Idempotency theo `event_id` cho mọi consumer.
2. Out-of-order guard bằng `event_sequence_id` hoặc `state_version`.
3. Không ACK message trước khi local transaction commit thành công.
4. Retry config chuẩn cho mọi consumer:
   - `max_retries = 10`
   - `initial_backoff = 1s`
   - `max_backoff = 5m`
   - `backoff = min(initial_backoff * 2^attempt, max_backoff)`
5. Poison message phải đi `t.review.dlq`, không retry vô hạn.
6. Cấu hình backpressure tối thiểu:
   - `batch_size = 100..500`
   - `max_in_flight_records = 500`
   - `max_poll_interval_ms` đủ lớn để tránh rebalance giả.

## 1. Debezium CDC Connector (Outbox Publisher Chính)

**Loại hình:** Kafka Connect Source Connector.

**Sứ mệnh:** Đọc WAL PostgreSQL và publish dữ liệu từ `review_outbox` sang `t.review.events` với độ trễ thấp, không poll vòng lặp CPU.

**Chi tiết luồng hoạt động:**
1. API/worker nghiệp vụ ghi dữ liệu domain + insert bản ghi vào `review_outbox` trong cùng transaction DB.
2. PostgreSQL flush thay đổi vào WAL.
3. Debezium đọc WAL qua replication slot, compose CloudEvents envelope từ metadata cột outbox + payload business.
4. Connector lấy `partition_key` từ cột outbox (bắt buộc là `product_id`) để giữ ordering theo sản phẩm.
5. Connector publish sang `t.review.events`.
6. Nếu publish thất bại quá ngưỡng, connector chuyển bản ghi lỗi sang DLQ của Connect theo policy vận hành.

**Ràng buộc quan trọng:**
*   Không dùng worker polling outbox ở app layer như baseline cũ.
*   Không dùng các cột `status`, `retry_count`, `next_retry_at`, `last_error` trong DB.
*   Retry/DLQ ownership thuộc Kafka Connect runtime.

## 2. Outbox Retention Trimmer (Partition Cleanup)

**Loại hình:** Scheduled Job.

**Sứ mệnh:** Dọn dữ liệu outbox cũ theo retention mà không gây bloat table/WAL và không làm mất event chưa publish.

**Safety gate bắt buộc trước khi drop partition:**
1. Connector lag < 120 giây.
2. Không có alert connector `FAILED`/`PAUSED`.
3. Partition sắp xóa không chứa record mới hơn mốc retention.

**Chi tiết luồng hoạt động:**
1. `review_outbox` partition theo tháng dựa trên `created_at`.
2. Job chạy ngoài giờ cao điểm (ví dụ 03:00).
3. Chỉ drop partition sau khi qua đầy đủ safety gate.
4. Nếu safety gate fail, skip cleanup và phát cảnh báo vận hành.

```sql
DROP TABLE IF EXISTS review_outbox_y2026m01;
```

## 3. Order Completed Consumer

**Topic:** `t.order.events`

**Consumer Group:** `review-order-sync`

**Event:** `ORDER_COMPLETED`

**Sứ mệnh:** Cấp eligibility 1-1-1-1 (order, product, sku, user) để user có quyền gửi review.

**Chi tiết luồng hoạt động:**
1. Validate payload bắt buộc: `order_id`, `user_id`, `status=COMPLETED`, `items[]`, `completed_at`.
2. Normalize `user_id` về canonical `u-<digits>`.
3. Mỗi item phải có `product_id`, `sku_id`; `variant_name` có thể null nhưng phải được log warning.
4. Trong transaction, upsert `review_eligibilities` idempotent (idempotency tự nhiên với unique key ở tầng Database)
5. Gán `expires_at = completed_at + review_window_days` (mặc định 30 ngày).
6. Nếu tại thời điểm xử lý `expires_at <= NOW()`, set `status='EXPIRED'`.
7. Commit rồi ACK.
8. Nếu payload sai schema: retry theo policy; quá ngưỡng đẩy `t.review.dlq`.

```sql
INSERT INTO review_eligibilities (
  id, user_id, order_id, product_id, sku_id, variant_name,
  status, attempt_count, expires_at, created_at, updated_at
)
VALUES (
  gen_random_uuid(), :user_id, :order_id, :product_id, :sku_id, :variant_name,
  :initial_status, 0, :expires_at, NOW(), NOW()
)
ON CONFLICT (order_id, product_id, sku_id, user_id)
DO UPDATE
SET variant_name = COALESCE(EXCLUDED.variant_name, review_eligibilities.variant_name),
    updated_at = NOW()
WHERE review_eligibilities.status IN ('PENDING');
```

## 4. Rating Aggregator Consumer

**Topic:** `t.review.events`

**Consumer Group:** `review-rating-aggregator`

**Events:** `REVIEW_APPROVED`, `REVIEW_HIDDEN`

**Sứ mệnh:** Cập nhật bảng aggregate (`product_rating_aggregates`) và phát `REVIEW_METRICS_UPDATED`.

**Chi tiết luồng hoạt động:**
1. Begin transaction.
2. Idempotency insert vào `rating_aggregator_idempotency` với `PROCESSING`.
   - Duplicate + `processing_status IN ('COMPLETED', 'SKIPPED')` -> ACK.
   - Duplicate + `processing_status='PROCESSING'` và `updated_at < NOW()-15m` -> mark `FAILED`, retry.
3. Validate business guard:
   - `REVIEW_APPROVED`: cộng điểm/tổng số review.
   - `REVIEW_HIDDEN`: trừ điểm/tổng số review nếu review đang `APPROVED`.
4. Upsert aggregate product và SKU bằng SQL atomic
5. Insert outbox `REVIEW_METRICS_UPDATED` cùng transaction:
6. Cập nhật idempotency `COMPLETED`.
7. Commit transaction.
8. Sau commit, overwrite cache Redis `review:prod:agg:*`
9. Publish lệnh invalidate L1 qua Redis Pub/Sub.

**Tối ưu:** xử lý theo micro-batch 100-500 events/lần để giảm lock contention.

## 5. Helpful Votes Sync Worker

**Loại hình:** Scheduled micro-batch mỗi 30 giây.

**Sứ mệnh:** Đồng bộ delta like/unlike từ Redis xuống DB an toàn khi crash và an toàn khi multi-pod.

**Chi tiết luồng hoạt động:**
1. Acquire distributed lock `SET review:sync:lock:deltas 1 NX EX 55`.
2. Nếu không lấy được lock, skip chu kỳ.
3. Ưu tiên phục hồi từ `HGETALL review:processing_deltas`.
4. Nếu rỗng, dùng Lua để move atomically từ `review:dirty_delta_keys` sang `review:processing_deltas`.
5. Batch update DB:
   - `helpful_count = GREATEST(helpful_count + delta, 0)`
6. Commit DB thành công mới `HDEL` các phần tử đã xử lý trong `processing_deltas`.
7. Refresh TTL bitmap theo policy khi có user interaction.

**Ràng buộc an toàn:**
*   Không xóa `review:liked_bits:{review_id}` trong chu kỳ sync.
*   Nếu worker crash sau bước move, chu kỳ sau phải tiếp tục từ `processing_deltas`.

## 6. L1 Cache Invalidation Broadcaster

**Loại hình:** Redis Pub/Sub subscriber trên mỗi pod.

**Sứ mệnh:** Đồng bộ invalidation cache L1 (in-memory per pod) theo cơ chế broadcast + self-heal.

**Kênh:** `review:l1:invalidate`

**Payload mẫu:**
```json
{
  "product_id": "prod-001",
  "reason": "REVIEW_METRICS_UPDATED",
  "timestamp": "2026-03-28T10:00:00Z"
}
```

**Self-heal rule bắt buộc:**
1. Mỗi entry L1 lưu kèm `version` tại thời điểm cache.
2. Khi read L1, so với `review:prod_ver:{product_id}` trên Redis:
   - nếu version lệch -> evict và fallback Redis/DB.
3. Khi pod startup, phải warmup watermark version tối thiểu cho tập product nóng.

**Lưu ý:** Không dùng Kafka consumer group cho invalidation L1 vì mỗi pod đều phải nhận thông điệp.

## 7. Privacy Anonymization Consumer

**Topic:** `t.user.events`

**Consumer Group:** `review-privacy-sync`

**Event:** `ACCOUNT_DELETED`

**Sứ mệnh:** Đảm bảo tuân thủ quyền riêng tư: ẩn danh thông tin tác giả review khi tài khoản bị xóa.

**Chi tiết luồng hoạt động:**
1. Validate payload có `user_id`, `deleted_at`, `retention_window_expires_at`, `state_version`.
2. Normalize `user_id` canonical.
3. Query danh sách `product_id` bị ảnh hưởng của user (distinct).
4. Trong transaction:
   - `UPDATE reviews SET user_name='Người dùng đã ẩn danh', is_author_anonymized=true, anonymized_at=:deleted_at WHERE user_id=:user_id AND is_author_anonymized=false`
5. Commit transaction.
6. Invalidate Redis theo batch pipeline:
   - mỗi batch 200 product gọi `INCR review:prod_ver:{product_id}`.
7. Broadcast invalidation L1 cho các product liên quan (retry tối đa 3 lần).
8. Nếu broadcast fail sau retry, đẩy task sang `review:l1:invalidate:retry` để replayer xử lý.
9. Ghi audit log compliance gồm `user_id`, `deleted_at`, `retention_window_expires_at`, `state_version` để phục vụ kiểm toán dữ liệu.

**Ràng buộc quan trọng:**
*   Không có worker cập nhật tên theo `USER_PROFILE_UPDATED`.
*   Chỉ chạy luồng anonymize khi có `ACCOUNT_DELETED` (hoặc legal takedown tương đương).

## 8. Reconciliation Cronjob

**Tần suất:** 5 phút.

**Sứ mệnh:** Thu hồi eligibility bị kẹt và đồng bộ lifecycle expiration.

**8.1. Recover `PROCESSING` stale an toàn**

Chỉ recover row kẹt khi **không tồn tại review tương ứng** để tránh mở lại eligibility trong lúc review đã được tạo.

```sql
UPDATE review_eligibilities e
SET status = CASE
  WHEN e.attempt_count >= 2 THEN 'REJECTED_FINAL'
  ELSE 'PENDING'
END,
updated_at = NOW()
WHERE e.status = 'PROCESSING'
  AND e.updated_at < NOW() - INTERVAL '30 minutes'
  AND NOT EXISTS (
    SELECT 1 FROM reviews r WHERE r.eligibility_id = e.id
  );
```

**8.2. Expire eligibility quá hạn**

```sql
UPDATE review_eligibilities
SET status = 'EXPIRED', updated_at = NOW()
WHERE status IN ('PENDING', 'REJECTED_RETRY_PENDING')
  AND expires_at <= NOW();
```

**8.3. Reconcile outbox backlog bất thường**

Phát hiện event có nguy cơ chưa được publish do connector lag hoặc lỗi kéo dài.

```sql
SELECT COUNT(*) AS stale_outbox_count
FROM review_outbox
WHERE created_at < NOW() - INTERVAL '15 minutes';
```

Nếu `stale_outbox_count` vượt ngưỡng vận hành, raise alert và tạm dừng cleanup partition.

## 9. Idempotency Cleanup Cronjob

**Tần suất:** hằng ngày (ví dụ 02:30).

**Mục tiêu:** tránh phình bảng idempotency.

**Safety gate trước cleanup:**
1. Max consumer lag của các group Review < 120 giây.
2. Không có group ở trạng thái `DOWN`/`UNHEALTHY`.
3. Nếu safety gate fail -> skip cleanup và raise alert.

```sql
DELETE FROM ai_moderation_idempotency
WHERE created_at < NOW() - INTERVAL '30 days';

DELETE FROM rating_aggregator_idempotency
WHERE created_at < NOW() - INTERVAL '30 days';
```

**SLO bắt buộc:** export `idempotency_table_row_count` và `last_cleanup_timestamp`.

## 10. Retry Replayer Worker

**Topic Consume:** `t.review.retry`

**Sứ mệnh:** replay các event tạm lỗi có kiểm soát.

**Rule:**
1. Mỗi envelope retry có `retry_round`.
2. `retry_round > 3` -> chuyển `t.review.dlq`.
3. Replay giữ nguyên `event_id` để idempotency hoạt động đúng.

## 12. Non-Functional Guardrails

1. **Lock order chuẩn:**
   - `review_eligibilities` -> `reviews` -> aggregate tables.
   - Không đảo thứ tự lock giữa các worker.
2. **Idempotency storage:**
   - Mỗi nghiệp vụ dùng bảng idempotency riêng.
   - Retention idempotency bắt buộc 30 ngày + cleanup job.
3. **Backpressure:**
   - Consumer batch size 100-500.
   - `max_in_flight_records = 500`.
4. **Retry/DLQ:**
   - Retry ngắn hạn ở consumer.
   - Lỗi schema hoặc poison message đi `t.review.dlq` để điều tra thủ công.
5. **SLO giám sát bắt buộc:**
   - Connector lag (Debezium)
   - Kafka consumer lag từng group
   - `review:dirty_delta_keys` size
   - P95/P99 latency update aggregate
   - Tỷ lệ event vào `t.review.dlq`
