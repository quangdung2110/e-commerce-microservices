# Review Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](review_workflow.md)
*   [Background Workers & Jobs](review_workers.md)
*   [Database Schema](review_db_schema.md)
*   [Events Structure](review_events.md)

---
Review Service có tải đọc cao, ghi bất đồng bộ nhiều và yêu cầu dữ liệu đánh giá hội tụ nhanh. Tài liệu này mô tả đầy đủ các worker để đảm bảo:

1. Không mất event khi publish (Outbox + CDC).
2. Không ghi sai thứ tự trong môi trường at-least-once.
3. Không drift giữa PostgreSQL và các read-model downstream.

## Tổng Hợp Kafka Topics

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| **Produce** | `t.review.events` | — | Topic chính phát mọi event nghiệp vụ của Review |
| **Consume** | `t.order.events` | `review-order-sync` | Cấp quyền review theo item đã mua |
| **Consume** | `t.user.events` | `review-privacy-sync` | Nhận `ACCOUNT_DELETED` để ẩn danh dữ liệu tác giả |
| **Consume** | `t.review.events` | `review-rating-aggregator` | Self-consume để cập nhật aggregate rating |
| **Consume** | `t.report.events` | `review-report-processor` | Xử lý báo cáo vi phạm và phát REVIEW_HIDDEN |

> **Policy đã chốt:** Review Service **không consume** `USER_PROFILE_UPDATED` để đồng bộ đổi tên hiển thị. `user_name` là snapshot tại thời điểm tạo review; chỉ xử lý ẩn danh khi nhận `ACCOUNT_DELETED`.

## Nguyên tắc vận hành bắt buộc

1. **Idempotency tự nhiên qua DB:** Consumer sử dụng unique constraints của DB để đảm bảo không ghi trùng khi xử lý at-least-once delivery.
2. Không ACK message trước khi local transaction commit thành công.
3. Retry config chuẩn cho mọi consumer:
   - `max_retries = 10`
   - `initial_backoff = 1s`
   - `max_backoff = 5m`
   - `backoff = min(initial_backoff * 2^attempt, max_backoff)`
4. Cấu hình backpressure tối thiểu:
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
2. Validate `user_id` là UUID hợp lệ theo chuẩn thống nhất.
3. Mỗi item phải có `product_id`, `sku_id`; `variant_name` có thể null nhưng phải được log warning.
4. Trong transaction, upsert `review_eligibilities` với idempotency tự nhiên qua `UNIQUE(order_id, product_id, sku_id, user_id)`.
5. Gán `expires_at = completed_at + review_window_days` (mặc định 30 ngày).
6. Nếu tại thời điểm xử lý `expires_at <= NOW()`, set `status='EXPIRED'`.
7. Commit rồi ACK.
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

**Events:** `REVIEW_CREATED`, `REVIEW_APPROVED`, `REVIEW_HIDDEN`

**Sứ mệnh:** Cập nhật bảng aggregate (`product_rating_aggregates`) theo product và phát `REVIEW_METRICS_UPDATED`.

**Chi tiết luồng hoạt động:**
1. Begin transaction.
2. Validate business guard:
   - `REVIEW_CREATED`: cộng điểm/tổng số review vào `product_rating_aggregates`.
   - `REVIEW_APPROVED`: cộng điểm/tổng số review (restore từ hidden).
   - `REVIEW_HIDDEN`: trừ điểm/tổng số review.
3. Upsert aggregate theo `product_id` bằng SQL atomic:
   ```sql
   UPDATE product_rating_aggregates
   SET average_rating = :new_avg,
       total_reviews = :new_total,
       total_X_star = total_X_star +/- 1,
       total_with_media = total_with_media +/- :has_media,
       updated_at = NOW()
   WHERE product_id = :product_id;
   ```
4. Insert outbox `REVIEW_METRICS_UPDATED` cùng transaction.
5. Commit transaction.

> **Lưu ý:** Producer không lưu `state_version` trên aggregate, không chạy idempotency check bằng version. Consumer chấp nhận over-count hiếm gặp do at-least-once; Reconciliation Cronjob và dashboard monitoring `product_rating_aggregates` drift là lưới an toàn cuối cùng.

**Tối ưu:** xử lý theo micro-batch 100-500 events/lần để giảm lock contention.

## 5. Privacy Anonymization Consumer

**Topic:** `t.user.events`

**Consumer Group:** `review-privacy-sync`

**Event:** `ACCOUNT_DELETED`

**Sứ mệnh:** Đảm bảo tuân thủ quyền riêng tư: ẩn danh thông tin tác giả review khi tài khoản bị xóa.

**Chi tiết luồng hoạt động:**
1. Validate payload có `user_id`, `deleted_at`, `retention_window_expires_at`.
2. Validate `user_id` là UUID hợp lệ theo chuẩn thống nhất.
3. Query danh sách `product_id` bị ảnh hưởng của user (distinct).
4. Trong transaction:
   - `UPDATE reviews SET user_name='Tài khoản đã bị xóa'` WHERE `user_id` = :user_id`.
5. Commit transaction.
6. Ghi audit log compliance gồm `user_id`, `deleted_at`, `retention_window_expires_at` để phục vụ kiểm toán dữ liệu.

**Ràng buộc quan trọng:**
*   Không có worker cập nhật tên theo `USER_PROFILE_UPDATED`.
*   Chỉ chạy luồng anonymize khi có `ACCOUNT_DELETED` (hoặc legal takedown tương đương).

## 6. Review Report Processor

**Topic Consume:** `t.report.events`

**Consumer Group:** `review-report-processor`

**Event:** `REVIEW_REPORTED` (hoặc tương đương từ Report Service)

**Sứ mệnh:** Xử lý báo cáo vi phạm, đếm số report và ẩn review khi đạt threshold.

**Chi tiết luồng hoạt động:**
1. Validate payload: `review_id`, `reporter_id`, `report_reason`.
2. `SELECT review ... FOR UPDATE` để lấy thông tin review.
3. Kiểm tra review có tồn tại và đang ở `status='APPROVED'`.
4. Insert report vào bảng `review_reports` (nếu có) hoặc tăng `report_count` trên `reviews`.
5. Check threshold:
   - Nếu `report_count < REPORT_THRESHOLD` -> ACK, không làm gì thêm.
   - Nếu `report_count >= REPORT_THRESHOLD`:
     - Update `reviews.status = 'HIDDEN_BY_REPORT'`.
     - Insert outbox `REVIEW_HIDDEN` với `hidden_reason='REPORT_THRESHOLD_EXCEEDED'`.
6. Commit transaction.
7. Ghi audit log cho báo cáo.

**Ràng buộc:**
*   Threshold mặc định là 10 reports (configurable).
*   Mỗi user chỉ được report 1 review 1 lần (idempotency qua `UNIQUE(review_id, reporter_id)`).

> **Lưu ý:** Nếu Report Service không phát event, có thể dùng polling hoặc synchronous call từ API endpoint report.

## 7. Reconciliation Cronjob

**Tần suất:** 5 phút.

**Sứ mệnh:** Thu hồi eligibility bị kẹt và đồng bộ lifecycle expiration.

### 6.1. Recover `REVIEWED` stale an toàn

Chỉ recover row kẹt khi **không tồn tại review tương ứng** để tránh mở lại eligibility trong lúc review đã được tạo.

```sql
UPDATE review_eligibilities e
SET status = 'PENDING',
    attempt_count = 0,
    updated_at = NOW()
WHERE e.status = 'REVIEWED'
   AND e.attempt_count = 0
   AND e.updated_at < NOW() - INTERVAL '30 minutes'
   AND NOT EXISTS (
      SELECT 1 FROM reviews r WHERE r.eligibility_id = e.id
   );
```

### 6.2. Expire eligibility quá hạn

```sql
UPDATE review_eligibilities
SET status = 'EXPIRED', updated_at = NOW()
WHERE status IN ('PENDING', 'REJECTED_RETRY_PENDING')
  AND expires_at <= NOW();
```

### 6.3. Reconcile outbox backlog bất thường

Phát hiện event có nguy cơ chưa được publish do connector lag hoặc lỗi kéo dài.

```sql
SELECT COUNT(*) AS stale_outbox_count
FROM review_outbox
WHERE created_at < NOW() - INTERVAL '15 minutes';
```

Nếu `stale_outbox_count` vượt ngưỡng vận hành, raise alert và tạm dừng cleanup partition.
