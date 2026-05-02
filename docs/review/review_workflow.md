# Review Service - Trình Tự Nghiệp Vụ API (Workflows)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](review_workflow.md)
*   [Background Workers & Jobs](review_workers.md)
*   [Caching Schema](review_caching_schema.md)
*   [Database Schema](review_db_schema.md)
*   [Events Structure](review_events.md)

---
Tài liệu này mô tả chi tiết trình tự từ lúc client gọi API Review cho đến khi dữ liệu hội tụ qua PostgreSQL, Redis cache và Kafka event pipeline.

Review Service áp dụng mô hình **PostgreSQL-first** cho dữ liệu chuẩn, kết hợp cache versioning để tối ưu đọc và CDC outbox để đồng bộ bất đồng bộ an toàn.

## 1. Gợi ý Base API Path

`[POST/GET/PATCH] /api/v1/reviews/...`

## 2. API: Tạo Review Mới

**Endpoint:** `POST /api/v1/reviews`

**Mục tiêu:** User gửi đánh giá cho item đã mua.

**Workflow chuẩn:**
1. **Validate request:** `user_id`, `order_id`, `product_id`, `sku_id`, `rating`, nội dung/media theo policy.
2. **Normalize identity:** chuẩn hóa `user_id` về canonical format `u-<digits>`.
3. **Lock quyền review (DB atomic):** chạy `UPDATE review_eligibilities SET status='REVIEWED', attempt_count = attempt_count + 1, updated_at = NOW() WHERE status IN ('PENDING') AND attempt_count = 0 AND expires_at > NOW() RETURNING ...` trên `review_eligibilities`.
4. **Nếu lock thất bại:**
   - `expires_at <= NOW()` -> trả `410 GONE`.
   - vượt số lần thử hoặc đã review -> trả `409 CONFLICT`.
5. **Trong cùng transaction:**
   - Insert `reviews` với `status='APPROVED'`.
   - Insert `review_media` nếu có.
   - Insert outbox `REVIEW_CREATED` (kèm envelope fields version/sequence).
6. Commit transaction.
7. Trả HTTP 201, payload trả về đầy đủ review vừa tạo để đảm bảo **Read-Your-Own-Writes**.
8. Debezium CDC publish event `REVIEW_CREATED` lên `t.review.events`.


## 3. API: Sửa Review (Trước Khi Duyệt Hoặc Theo Retry Policy)

**Endpoint:** `PATCH /api/v1/reviews/{review_id}`

**Rule state:**
1. Cho phép sửa khi:
   - `reviews.status='APPROVED'` **và** eligibility `status='REVIEWED'` **và** `attempt_count = 1`.
   - **hoặc** `reviews.status='HIDDEN_BY_REPORT'` **và** eligibility `status='REJECTED_RETRY_PENDING'` **và** `attempt_count = 1`.

**Workflow chuẩn:**
1. Validate ownership theo `user_id` canonical.
2. `SELECT review + eligibility ... FOR UPDATE`.
3. Kiểm tra state theo rule ở trên.
4. Update eligibility:
   - `attempt_count = attempt_count + 1`
   - `status = 'REJECTED_FINAL'`
5. Cập nhật `reviews`:
   - Nếu trước đó `HIDDEN_BY_REPORT` thì set lại `status='APPROVED'`.
6. Insert outbox `REVIEW_UPDATED`.
7. Nếu có transition `HIDDEN_BY_REPORT -> APPROVED`, insert thêm outbox `REVIEW_APPROVED`.
8. Commit.

**Lưu ý:** Không invalidate cache page public sau PATCH để tránh stampede. Client nhận payload trả về để đảm bảo RYOW cho tác giả.

## 4. API: Like/Unlike Review

**Endpoint:** `POST /api/v1/reviews/{review_id}/helpful`

**Guard bắt buộc trước khi mutate:**
1. Review phải ở trạng thái `APPROVED`.

**Workflow chuẩn (DB-First):**
1. Rate limit (chống spam): `SET review:ratelimit:{user_id}:{review_id} "1" NX EX 10`.
   - Nếu key đã tồn tại -> trả `429 Too Many Requests`.
2. Gọi Database trong một transaction:
   - **Trường hợp Like:**
     ```sql
     BEGIN;
     INSERT INTO review_likes (review_id, user_id) VALUES (:rid, :uid) ON CONFLICT DO NOTHING;
     -- Chỉ update count nếu insert thành công (số dòng thực sự được thêm là 1)
     UPDATE reviews SET helpful_count = helpful_count + 1 WHERE id = :rid AND status = 'APPROVED';
     COMMIT;
     ```
   - **Trường hợp Unlike:**
     ```sql
     BEGIN;
     DELETE FROM review_likes WHERE review_id = :rid AND user_id = :uid;
     -- Chỉ update count nếu delete thành công (số dòng thực sự bị xóa là 1)
     UPDATE reviews SET helpful_count = helpful_count - 1 WHERE id = :rid AND helpful_count > 0;
     COMMIT;
     ```
3. API trả kết quả ngay với giá trị `helpful_count` đã được cập nhật từ DB.
4. Cập nhật Cache (tùy chọn): Tuỳ định hướng, ta có thể cập nhật `review:helpful:{review_id}` hoặc phụ thuộc vào payload cache của trang 1 được refresh theo TTL.
5. (Lưu ý: Không xử lý xóa unlike khi tài khoản user bị xóa để tiết kiệm chi phí, do số lượt like không tác động xấu đến trải nghiệm người dùng hiện tại).


## 5. API: admin Reply

**Endpoint:** `POST /api/v1/reviews/{review_id}/admin-replies`

**Workflow chuẩn:**
1. `SELECT review ... FOR UPDATE`, chỉ cho reply khi `reviews.status='APPROVED'`.
2. Upsert `review_admin_replies` (1 review chỉ 1 reply active).
3. Update `reviews.is_admin_replied=true`.
4. Nếu là insert mới -> insert outbox `` (bắt buộc có `content`).
5. Nếu là update reply đã tồn tại -> không phát event notify mới để tránh spam; chỉ invalidate cache.
6. Commit.
7. `INCR review:prod_ver:{product_id}` để refresh page cache hiển thị reply.

## 6. API Đọc: Aggregate và Danh Sách Review

### 6.1. Aggregate

**Endpoint:** `GET /api/v1/reviews/products/{product_id}/aggregate`

**Workflow:**
1. Read `review:prod:agg:{product_id}` từ Redis.
2. Cache miss -> query `product_rating_aggregates`, rồi SET cache với TTL + deterministic jitter.
3. Nếu DB quá tải, bật stale-while-revalidate tối đa 30s.

### 6.2. List Reviews

**Endpoint:** `GET /api/v1/reviews/products/{product_id}`

**Workflow:**
1. Lấy `version = GET review:prod_ver:{product_id}` (default 1).
2. Với page 1: đọc cache theo key có version (`review:prod:{product_id}:v:{version}:...`).
3. Cache miss page 1: query DB theo index phù hợp, SET cache.
4. Với page 2+: luôn query DB bằng cursor (`id` hoặc `(helpful_count,id)`).

## 7. Event Workflow: ORDER_COMPLETED -> Eligibility

**Trigger:** `ORDER_COMPLETED` từ `t.order.events`.

**Xử lý:** [Order Completed Consumer](review_workers.md#3-order-completed-consumer)

**Trình tự:**
1. Consumer validate event.
2. Normalize `user_id`.
3. Upsert eligibility từng item.
4. Nếu `expires_at <= NOW()` tại lúc xử lý, set `EXPIRED` ngay để tránh eligibility rác.
5. Commit rồi ACK.

## 8. Event Workflow: REVIEW_METRICS_UPDATED -> Search

**Trigger:** Event nội bộ `REVIEW_METRICS_UPDATED` được phát từ aggregator. [Rating Aggregator Consumer](review_workers.md#4-rating-aggregatorConsumer)

**Trình tự liên service:**
1. Search consumer `search-review-metrics-sync` nhận event và `_update_by_query` các document SKU theo `product_id`.
2. Guard **duy nhất** theo `event_sequence_id` trên **envelope**.
3. Nếu thiếu `event_sequence_id`, phải chuyển DLQ (không fallback timestamp).

## 9. Event Workflow: ACCOUNT_DELETED -> Author Anonymization

**Trigger:** `ACCOUNT_DELETED` từ `t.user.events`.

**Xử lý:** [Privacy Anonymization Consumer](review_workers.md#6-privacy-anonymization-consumer)

**Trình tự:**
1. Consumer nhận `user_id`, `deleted_at`, `retention_window_expires_at`
2. Normalize `user_id`.
3. Update `reviews.user_name='Tài khoản đã bị xóa'` để ẩn danh hiển thị ngay.
4. Invalidate cache page theo danh sách product bị ảnh hưởng (`INCR review:prod_ver:{product_id}`).
5. Broadcast L1 invalidation.

## 10. Ma Trận Nhất Quán (Consistency Matrix)

| Luồng | Source of Truth | Read-Your-Own-Writes | Cửa sổ eventual consistency | Cơ chế hội tụ |
|---|---|---|---|---|
| Create review | PostgreSQL (`reviews`, `review_eligibilities`) | Có ở API response | 1-3s để event xuất hiện downstream | CDC outbox + consumer retries |
| Helpful vote | PostgreSQL (`reviews`, `review_likes`) | Có ở API action result | Nguyên tử (Atomic) | DB Transaction |
| admin reply | PostgreSQL + outbox | Có sau commit | 1-5s cho cache/list downstream | Versioned cache invalidation |
| Account deletion anonymize | PostgreSQL (`reviews`) | Không yêu cầu RYOW cho user đã xóa | <= 60s để mọi page cache hội tụ | Batch anonymize + version bump per product |

## 11. Kịch bản lỗi và tự phục hồi

1. **Duplicate event:** idempotency table chặn xử lý lặp.
2. **Out-of-order event:** guard bằng `event_sequence_id`/`state_version` trước khi apply.
3. **Missing required sequencing field:** chuyển `t.review.dlq`, không xử lý mù.
4. **Connector lag tăng cao:** cảnh báo theo lag SLO, tăng tài nguyên Kafka Connect hoặc giảm batch producer.
5. **Poison message:** đẩy DLQ sau ngưỡng retry, không block toàn bộ partition.
