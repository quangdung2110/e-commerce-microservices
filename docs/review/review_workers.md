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
| **Consume** | `t.order.events` | `review-order-sync` | Cấp quyền review theo item đã mua |
| **Consume** | `t.ai_agent.events` | `review-moderation` | Nhận kết quả kiểm duyệt nội dung/media |
| **Consume** | `t.user.events` | `review-privacy-sync` | Nhận `ACCOUNT_DELETED` để ẩn danh dữ liệu tác giả |
| **Consume** | `t.review.events` | `review-rating-aggregator` | Self-consume để cập nhật aggregate rating |

> **Policy đã chốt:** Review Service **không consume** `USER_PROFILE_UPDATED` để đồng bộ đổi tên hiển thị. `user_name` là snapshot tại thời điểm tạo review; chỉ xử lý ẩn danh khi nhận `ACCOUNT_DELETED`.

## Nguyên tắc vận hành bắt buộc

1. Idempotency theo `event_id` cho mọi consumer.
2. Out-of-order guard bằng `event_sequence_id` hoặc `state_version`.
3. Không ACK message trước khi local transaction commit thành công.
4. Retry + DLQ đặt tại hạ tầng Kafka Connect hoặc Kafka consumer, không dùng cột trạng thái retry trong bảng outbox.

## 1. Debezium CDC Connector (Outbox Publisher Chính)

**Loại hình:** Kafka Connect Source Connector.

**Sứ mệnh:** Đọc WAL PostgreSQL và publish dữ liệu từ `review_outbox` sang `t.review.events` với độ trễ thấp, không poll vòng lặp CPU.

**Chi tiết luồng hoạt động:**
1. API/worker nghiệp vụ ghi dữ liệu domain + insert bản ghi vào `review_outbox` trong cùng transaction DB.
2. PostgreSQL flush thay đổi vào WAL.
3. Debezium đọc WAL qua replication slot, convert bản ghi outbox thành event CloudEvents.
4. Connector lấy `partition_key` từ cột outbox (bắt buộc là `product_id`) để giữ ordering theo sản phẩm.
5. Connector publish sang `t.review.events`.
6. Nếu publish thất bại quá ngưỡng, connector chuyển bản ghi lỗi sang DLQ của Connect theo policy vận hành.

**Ràng buộc quan trọng:**
*   Không dùng worker polling outbox ở app layer như baseline cũ.
*   Không dùng các cột `status`, `retry_count`, `next_retry_at`, `last_error` trong DB.
*   Retry/DLQ ownership thuộc Kafka Connect runtime.

## 2. Outbox Retention Trimmer (Partition Cleanup)

**Loại hình:** Scheduled Job.

**Sứ mệnh:** Dọn dữ liệu outbox cũ theo retention mà không gây bloat table/WAL.

**Chi tiết luồng hoạt động:**
1. `review_outbox` được partition theo tháng dựa trên `created_at`.
2. Job chạy ngoài giờ cao điểm (ví dụ 03:00), drop partition quá hạn retention.
3. Không dùng `DELETE` theo từng dòng để tránh vacuum pressure.

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
2. Mỗi item phải có `product_id`, `sku_id`, `variant_name`.
3. Trong transaction, insert `review_eligibilities` bằng `ON CONFLICT DO NOTHING` để idempotent.
4. Gán `expires_at = completed_at + review_window_days`.
5. Commit rồi ACK.
6. Nếu payload sai schema: retry theo policy; quá ngưỡng đẩy DLQ.

```sql
INSERT INTO review_eligibilities (
  id, user_id, order_id, product_id, sku_id, variant_name,
  status, attempt_count, expires_at, created_at, updated_at
)
VALUES (
  gen_random_uuid(), :user_id, :order_id, :product_id, :sku_id, :variant_name,
  'PENDING', 0, :expires_at, NOW(), NOW()
)
ON CONFLICT (order_id, product_id, sku_id, user_id)
DO NOTHING;
```

## 4. AI Moderation Synchronizer

**Topic:** `t.ai_agent.events`

**Consumer Group:** `review-moderation`

**Events:** `TEXT_MODERATED`, `MEDIA_MODERATED`

**Sứ mệnh:** Đồng bộ kết quả kiểm duyệt AI, chuyển trạng thái review đúng state machine và phát event downstream.

**Chi tiết luồng hoạt động (transaction đơn):**
1. Insert lock idempotency vào `ai_moderation_idempotency` với trạng thái `PROCESSING`; duplicate thì skip và ACK.
2. `SELECT ... FOR UPDATE` review theo `review_id`.
3. Chỉ xử lý nếu `reviews.status='PENDING_MODERATION'`; trạng thái khác thì mark `SKIPPED` và ACK.
4. Nếu `is_safe=true`:
   - `reviews.status='APPROVED'`
   - `review_eligibilities.status='REVIEWED'`
   - Insert outbox `REVIEW_APPROVED`
5. Nếu `is_safe=false`:
   - `reviews.status='REJECTED'`
   - `attempt_count=1` -> `review_eligibilities.status='REJECTED_RETRY_PENDING'`, emit `REVIEW_REJECTED(can_retry=true)`
   - `attempt_count>=2` -> `review_eligibilities.status='REJECTED_FINAL'`, emit `REVIEW_REJECTED(can_retry=false)`
6. Cập nhật idempotency thành `COMPLETED`, commit, sau đó ACK.

**Luồng hậu kiểm report:** Khi admin xác nhận report hợp lệ, hệ thống đổi `reviews.status='HIDDEN_BY_REPORT'`, insert outbox `REVIEW_HIDDEN`, rồi trigger worker aggregate để trừ thống kê nếu cần.

## 5. Rating Aggregator Consumer

**Topic:** `t.review.events`

**Consumer Group:** `review-rating-aggregator`

**Events:** `REVIEW_APPROVED`, `REVIEW_REJECTED`, `REVIEW_HIDDEN`

**Sứ mệnh:** Cập nhật bảng aggregate (`product_rating_aggregates`, `sku_rating_aggregates`) và phát `REVIEW_METRICS_UPDATED`.

**Chi tiết luồng hoạt động:**
1. Begin transaction.
2. Idempotency insert vào `rating_aggregator_idempotency(event_id PK)`; duplicate thì skip.
3. Guard nghiệp vụ:
   - `REVIEW_APPROVED`: cộng điểm/tổng số review.
   - `REVIEW_REJECTED` hoặc `REVIEW_HIDDEN`: chỉ trừ khi `previous_status='APPROVED'`.
4. Upsert aggregate product và SKU bằng SQL atomic.
5. Insert outbox `REVIEW_METRICS_UPDATED` cùng transaction, điền đủ `event_schema_version`, `state_version`, `event_sequence_id`.
6. Commit transaction.
7. Sau commit, overwrite cache Redis `review:prod:agg:*` và `review:sku:agg:*`.
8. Publish lệnh invalidate L1 qua Redis Pub/Sub.

**Tối ưu:** xử lý theo micro-batch 100-500 events/lần để giảm lock contention.

## 6. Helpful Votes Sync Worker

**Loại hình:** Scheduled micro-batch mỗi 30 giây.

**Sứ mệnh:** Đồng bộ delta like/unlike từ Redis xuống DB an toàn khi crash.

**Chi tiết luồng hoạt động:**
1. Ưu tiên phục hồi từ `HGETALL review:processing_deltas`.
2. Nếu rỗng, dùng Lua để move atomically từ `review:dirty_delta_keys` sang `review:processing_deltas`.
3. Batch update DB:
   - `helpful_count = GREATEST(helpful_count + delta, 0)`
4. Commit DB thành công mới `HDEL` các phần tử đã xử lý trong `processing_deltas`.

**Ràng buộc an toàn:**
*   Không xóa `review:liked_bits:{review_id}` trong chu kỳ sync.
*   Nếu worker crash sau bước 2, chu kỳ sau phải tiếp tục từ `processing_deltas`.

## 7. L1 Cache Invalidation Broadcaster

**Loại hình:** Redis Pub/Sub subscriber trên mỗi pod.

**Sứ mệnh:** Đồng bộ invalidation cache L1 (in-memory per pod) theo cơ chế broadcast.

**Kênh:** `review:l1:invalidate`

**Payload mẫu:**
```json
{
  "product_id": "prod-001",
  "reason": "REVIEW_METRICS_UPDATED",
  "timestamp": "2026-03-28T10:00:00Z"
}
```

**Lưu ý:** Không dùng Kafka consumer group cho invalidation L1 vì mỗi pod đều phải nhận thông điệp.

## 8. Privacy Anonymization Consumer

**Topic:** `t.user.events`

**Consumer Group:** `review-privacy-sync`

**Event:** `ACCOUNT_DELETED`

**Sứ mệnh:** Đảm bảo tuân thủ quyền riêng tư: ẩn danh thông tin tác giả review khi tài khoản bị xóa.

**Chi tiết luồng hoạt động:**
1. Validate payload có `user_id`, `deleted_at`.
2. Query danh sách `product_id` bị ảnh hưởng của user (distinct).
3. Trong transaction:
   - `UPDATE reviews SET user_name='Người dùng đã ẩn danh', is_author_anonymized=true, anonymized_at=:deleted_at WHERE user_id=:user_id AND is_author_anonymized=false`
4. Commit transaction.
5. Với mỗi `product_id` bị ảnh hưởng: `INCR review:prod_ver:{product_id}` để invalidation page 1 cache.
6. Broadcast invalidation L1 cho các product liên quan.

**Ràng buộc quan trọng:**
*   Không có worker cập nhật tên theo `USER_PROFILE_UPDATED`.
*   Chỉ chạy luồng anonymize khi có `ACCOUNT_DELETED` (hoặc legal takedown tương đương).

## 9. Reconciliation Cronjob

**Tần suất:** 5 phút.

**Sứ mệnh:** Thu hồi eligibility bị kẹt `PROCESSING` quá lâu do crash/mất ACK.

**Logic phục hồi chuẩn:**
*   `attempt_count = 1` -> trả về `PENDING`.
*   `attempt_count >= 2` -> trả về `REJECTED_FINAL`.

```sql
UPDATE review_eligibilities
SET status = CASE
  WHEN attempt_count >= 2 THEN 'REJECTED_FINAL'
  ELSE 'PENDING'
END,
updated_at = NOW()
WHERE status = 'PROCESSING'
  AND updated_at < NOW() - INTERVAL '30 minutes';
```

## 10. Non-Functional Guardrails

1. **Lock order chuẩn:**
   - `review_eligibilities` -> `reviews` -> aggregate tables.
   - Không đảo thứ tự lock giữa các worker.
2. **Idempotency storage:**
   - Mỗi nghiệp vụ dùng bảng idempotency riêng.
   - Retention idempotency tối thiểu 30 ngày.
3. **Backpressure:**
   - Consumer batch size 100-500.
   - Max poll interval và max in-flight phải cấu hình để không rebalance liên tục.
4. **Retry/DLQ:**
   - Retry ngắn hạn ở consumer.
   - Lỗi schema hoặc poison message đi DLQ để điều tra thủ công.
5. **SLO giám sát bắt buộc:**
   - Connector lag (Debezium)
   - Kafka consumer lag từng group
   - `review:dirty_delta_keys` size
   - P95/P99 latency update aggregate
   - Tỷ lệ event vào DLQ
