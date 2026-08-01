# Review Service - Trình Tự Nghiệp Vụ API (Workflows)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](review_workflow.md)
*   [Background Workers & Jobs](review_workers.md)
*   [Database Schema](review_db_schema.md)
*   [Events Structure](review_events.md)

---
Tài liệu này mô tả chi tiết trình tự từ lúc client gọi API Review cho đến khi dữ liệu hội tụ qua PostgreSQL và Kafka event pipeline.

Review Service áp dụng mô hình **PostgreSQL-first** cho dữ liệu chuẩn, kết hợp CDC outbox để đồng bộ bất đồng bộ an toàn. Read API đọc thẳng từ PostgreSQL, không có cache trung gian.

## 1. Gợi ý Base API Path

`[POST/GET/PATCH] /api/v1/reviews/...`

## 2. API: Tạo Review Mới

**Endpoint:** `POST /api/v1/reviews`

**Mục tiêu:** User gửi đánh giá cho item đã mua.

**Idempotency:** Sử dụng header `Idempotency-Key` kết hợp với `UNIQUE(eligibility_id)` để đảm bảo không tạo trùng review.

**Workflow chuẩn:**
1. **Validate request:** `user_id`, `order_id`, `product_id`, `sku_id`, `rating`, nội dung/media theo policy.
2. **Validate user_id:** đảm bảo `user_id` là UUID hợp lệ theo chuẩn thống nhất toàn hệ thống.
3. **Lock quyền review (DB atomic):** chạy `UPDATE review_eligibilities SET status='REVIEWED', attempt_count = attempt_count + 1, updated_at = NOW() WHERE status IN ('PENDING') AND attempt_count = 0 AND expires_at > NOW() RETURNING ...` trên `review_eligibilities`.
4. **Nếu lock thất bại:**
   - `expires_at <= NOW()` -> trả `410 GONE`.
   - vượt số lần thử hoặc đã review -> trả `409 CONFLICT`.
5. **Trong cùng transaction:**
   - Insert `reviews` với `status='APPROVED'` (idempotent qua `UNIQUE(eligibility_id)`).
   - Insert `review_media` nếu có.
   - Insert outbox `REVIEW_CREATED` (kèm envelope fields version/sequence).
6. Commit transaction.
7. Trả HTTP 201, payload trả về đầy đủ review vừa tạo để đảm bảo **Read-Your-Own-Writes**.
8. Debezium CDC publish event `REVIEW_CREATED` lên `t.review.events`.


## 3. API: Sửa Review (Duplicatable 1 Lần)

**Endpoint:** `PATCH /api/v1/reviews/{review_id}`

**Mục tiêu:** User chỉnh sửa review đã tạo, chỉ cho phép tối đa 1 lần chỉnh sửa.

**Idempotency:** Sử dụng header `Idempotency-Key` kết hợp với `PRIMARY KEY (eligibility_id, attempt_count)` logic check.

**Rule state:**
1. Review phải ở trạng thái `APPROVED`.
2. Eligibility phải có `status='REVIEWED'` và `attempt_count = 1` (đã submit 1 lần, chưa edit).

**Workflow chuẩn:**
1. Validate ownership theo `user_id` canonical.
2. `SELECT review + eligibility ... FOR UPDATE`.
3. Kiểm tra:
   - Review `status='APPROVED'`
   - Eligibility `status='REVIEWED'` và `attempt_count = 1`
4. **Trong cùng transaction:**
   - Update eligibility: `status = 'REJECTED_FINAL'`, `attempt_count = 2`.
   - Update review: cập nhật các fields mới (`rating`, `content`, `media`).
   - Insert outbox `REVIEW_UPDATED`.
5. Commit.

**Lưu ý:** Read-Your-Own-Writes cho tác giả đảm bảo bằng payload trả về ngay trong response. Downstream (Search Service) tự reindex khi nhận event `REVIEW_UPDATED` phát qua outbox.




## 4. API: Admin Reply

**Endpoint:** `POST /api/v1/reviews/{review_id}/admin-replies`

**Idempotency:** `UNIQUE(review_id)` trên bảng `review_admin_replies` với upsert logic.

**Workflow chuẩn:**
1. `SELECT review ... FOR UPDATE`, chỉ cho reply khi `reviews.status='APPROVED'`.
2. Upsert `review_admin_replies` (1 review chỉ 1 reply active) với `ON CONFLICT DO UPDATE`.
3. Update `reviews.is_admin_replied=true`.
4. Nếu là insert mới -> insert outbox `ADMIN_REPLY_CREATED` (bắt buộc có `content`).
5. Nếu là update reply đã tồn tại -> không phát event notify mới để tránh spam; chỉ cập nhật nội dung reply.
6. Commit.

## 5. API Đọc: Aggregate và Danh Sách Review

Read API đọc thẳng từ PostgreSQL, không qua cache trung gian. Pagination dùng keyset/cursor để ổn định khi dữ liệu lớn.

### 5.1. Aggregate

**Endpoint:** `GET /api/v1/reviews/products/{product_id}/aggregate`

**Workflow:**
1. Query trực tiếp bảng `product_rating_aggregates` theo `product_id`.
2. Nếu tải tăng cao, dùng read replica hoặc connection pool tuning; không duy trì cache trong service.

### 5.2. List Reviews

**Endpoint:** `GET /api/v1/reviews/products/{product_id}`

**Workflow:**
1. Query bảng `reviews` theo `product_id` với index phù hợp (`B-Tree(product_id, sku_id, created_at DESC)` hoặc `B-Tree(product_id, rating, created_at DESC)`).
2. Filter thêm `has_media = TRUE` nếu client yêu cầu review có media (dùng partial index tương ứng).
3. Trả về danh sách phân trang, sắp xếp theo `created_at DESC` mặc định.

## 6. Event Workflow: ORDER_COMPLETED -> Eligibility

**Trigger:** `ORDER_COMPLETED` từ `t.order.events`.

**Xử lý:** [Order Completed Consumer](review_workers.md#3-order-completed-consumer)

**Trình tự:**
1. Consumer validate event.
2. Validate `user_id` là UUID hợp lệ theo chuẩn thống nhất.
3. Upsert eligibility từng item với idempotency tự nhiên qua `UNIQUE(order_id, product_id, sku_id, user_id)`.
4. Nếu `expires_at <= NOW()` tại lúc xử lý, set `EXPIRED` ngay để tránh eligibility rác.
5. Commit rồi ACK.

## 7. Event Workflow: REVIEW_METRICS_UPDATED -> Search

**Trigger:** Event nội bộ `REVIEW_METRICS_UPDATED` được phát từ aggregator. [Rating Aggregator Consumer](review_workers.md#4-rating-aggregatorConsumer)

**Trình tự liên service:**
1. Search consumer `search-review-metrics-sync` nhận event và `_update_by_query` các document theo `product_id`.
2. Ordering đảm bảo bằng partition key `product_id` (Kafka partition ordering); Search Service không cần guard bằng sequence field trên envelope.

## 8. Event Workflow: ACCOUNT_DELETED -> Author Anonymization

**Trigger:** `ACCOUNT_DELETED` từ `t.user.events`.

**Xử lý:** [Privacy Anonymization Consumer](review_workers.md#6-privacy-anonymization-consumer)

**Trình tự:**
1. Consumer nhận `user_id`, `deleted_at`, `retention_window_expires_at`
2. Validate `user_id` là UUID hợp lệ theo chuẩn thống nhất.
3. Update `reviews.user_name='Tài khoản đã bị xóa'` để ẩn danh hiển thị ngay.

## 9. Ma Trận Nhất Quán (Consistency Matrix)

| Luồng | Source of Truth | Read-Your-Own-Writes | Cửa sổ eventual consistency | Cơ chế hội tụ |
|---|---|---|---|---|
| Create review | PostgreSQL (`reviews`, `review_eligibilities`) | Có ở API response | 1-3s để event xuất hiện downstream | CDC outbox + consumer retries |
| Admin reply | PostgreSQL + outbox | Có sau commit | 1-5s cho downstream reindex | CDC outbox publish `ADMIN_REPLY_CREATED` |
| Account deletion anonymize | PostgreSQL (`reviews`) | Không yêu cầu RYOW cho user đã xóa | <= 60s để downstream (Search) reindex các review bị ẩn danh | Consumer `ACCOUNT_DELETED` cập nhật trực tiếp + event nội bộ |

