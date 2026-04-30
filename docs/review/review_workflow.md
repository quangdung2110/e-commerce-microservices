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
3. **Lock quyền review (DB atomic):** chạy `UPDATE ... WHERE status IN ('PENDING','REJECTED_RETRY_PENDING') AND attempt_count < 2 AND expires_at > NOW() RETURNING ...` trên `review_eligibilities`.
4. **Nếu lock thất bại:**
   - `expires_at <= NOW()` -> trả `410 GONE`.
   - vượt số lần thử hoặc đã review -> trả `409 CONFLICT`.
5. **Resolve seller_id:**
   - ưu tiên lấy từ `review_eligibilities.seller_id`.
   - nếu null (legacy), gọi Product snapshot nội bộ để lấy seller_id rồi backfill eligibility.
6. **Trong cùng transaction:**
   - Insert `reviews` với `status='PENDING_MODERATION'`.
   - Insert `review_media` nếu có.
   - Insert outbox `REVIEW_CREATED` (kèm envelope fields version/sequence).
7. Commit transaction.
8. Trả HTTP 201.
9. Debezium CDC publish event `REVIEW_CREATED` lên `t.review.events`.

**Lưu ý nhất quán:** Không invalidate cache page public ở bước tạo mới vì review còn `PENDING_MODERATION`, chưa tham gia danh sách hiển thị công khai.

## 3. API: Sửa Review (Trước Khi Duyệt Hoặc Theo Retry Policy)

**Endpoint:** `PATCH /api/v1/reviews/{review_id}`

**Rule state:**
1. Cho phép sửa khi `reviews.status='PENDING_MODERATION'`.
2. Cho phép sửa để resubmit khi `reviews.status='REJECTED'` và eligibility tương ứng đang `REJECTED_RETRY_PENDING`.
3. Không cho sửa khi `APPROVED` hoặc `HIDDEN_BY_REPORT`.

**Workflow chuẩn:**
1. Validate ownership theo `user_id` canonical.
2. `SELECT review + eligibility ... FOR UPDATE`.
3. Kiểm tra state theo rule ở trên.
4. Update `content/media`.
5. Reset moderation sub-states:
   - `text_moderation_status='PENDING'`
   - `media_moderation_status='PENDING'`
   - giữ nguyên `attempt_count` (không tăng ở PATCH)
6. Set `reviews.status='PENDING_MODERATION'` nếu trước đó là `REJECTED`.
7. Insert outbox `REVIEW_UPDATED`.
8. Commit.

**Lưu ý:** Không invalidate cache page public vì review chưa/không ở trạng thái hiển thị ngay sau PATCH.

## 4. API: Like/Unlike Review

**Endpoint:** `POST /api/v1/reviews/{review_id}/helpful`

**Guard bắt buộc trước khi mutate:**
1. Review phải ở trạng thái `APPROVED`.
2. Chặn Like/Unlike khi `NOW() - review.created_at > 6 tháng`.

**Workflow chuẩn:**
1. API gọi Lua script atomically:
   - Like: `SETBIT=1` nếu trước đó là 0 thì `INCR review:{review_id}:delta` + `SADD review:dirty_delta_keys`.
   - Unlike: `SETBIT=0` nếu trước đó là 1 thì `DECR delta` + `SADD dirty`.
2. API trả kết quả ngay (không block DB write), gồm:
   - `helpful_count_realtime` (DB + delta hiện tại)
   - `helpful_count_persisted` (giá trị DB đã commit gần nhất)
3. Scheduled worker mỗi 30s sync delta xuống DB và cập nhật cache.

## 5. API: Report Review

**Endpoint:** `POST /api/v1/reviews/{review_id}/reports`

**Policy report chuẩn hóa:**
1. `auto_hide_threshold = 5` unique reporters.
2. `critical_reason_threshold = 3` unique reporters cho nhóm lý do `ILLEGAL_CONTENT`, `DOXXING`, `HATE_SPEECH`.
3. Chỉ phát `REVIEW_HIDDEN` khi có transition thật từ trạng thái hiện tại sang `HIDDEN_BY_REPORT`.
4. Các ngưỡng lấy từ config runtime của Review Service; giá trị trên là default fallback khi config store lỗi.

**Workflow chuẩn:**
1. Validate reporter role (`USER` hoặc `SELLER`) và quyền nghiệp vụ.
2. Trong **một transaction duy nhất**:
   - Insert `review_reports` (unique index chống duplicate).
   - Tăng `reviews.report_count`.
   - Tính unique reporters + policy threshold.
   - Nếu đạt ngưỡng policy và review đang `APPROVED`, đổi `reviews.status='HIDDEN_BY_REPORT'`.
   - Nếu có transition status, insert outbox `REVIEW_HIDDEN`.
3. Commit và trigger aggregate recalculation qua event pipeline.

## 6. API: Seller Reply

**Endpoint:** `POST /api/v1/reviews/{review_id}/seller-replies`

**Workflow chuẩn:**
1. Validate seller ownership của product.
2. `SELECT review ... FOR UPDATE`, chỉ cho reply khi `reviews.status='APPROVED'`.
3. Upsert `review_seller_replies` (1 review chỉ 1 reply active) với optimistic guard theo `seller_id`.
4. Update `reviews.is_seller_replied=true`.
5. Nếu là insert mới -> insert outbox `SELLER_REPLY_CREATED` (bắt buộc có `content`).
6. Nếu là update reply đã tồn tại -> không phát event notify mới để tránh spam; chỉ invalidate cache.
7. Commit.
8. `INCR review:prod_ver:{product_id}` để refresh page cache hiển thị reply.

## 7. API Đọc: Aggregate và Danh Sách Review

### 7.1. Aggregate

**Endpoint:** `GET /api/v1/reviews/products/{product_id}/aggregate`

**Workflow:**
1. Read `review:prod:agg:{product_id}` từ Redis.
2. Cache miss -> query `product_rating_aggregates`, rồi SET cache với TTL + deterministic jitter.
3. Nếu DB quá tải, bật stale-while-revalidate tối đa 30s.

### 7.2. List Reviews

**Endpoint:** `GET /api/v1/reviews/products/{product_id}`

**Workflow:**
1. Lấy `version = GET review:prod_ver:{product_id}` (default 1).
2. Với page 1: đọc cache theo key có version (`review:prod:{product_id}:v:{version}:...`).
3. Cache miss page 1: query DB theo index phù hợp, SET cache.
4. Với page 2+: luôn query DB bằng cursor (`id` hoặc `(helpful_count,id)`).

## 8. Event Workflow: ORDER_COMPLETED -> Eligibility

**Trigger:** `ORDER_COMPLETED` từ `t.order.events`.

**Xử lý:** [Order Completed Consumer](review_workers.md#3-order-completed-consumer)

**Trình tự:**
1. Consumer validate event.
2. Normalize `user_id`.
3. Upsert eligibility từng item.
4. Nếu `expires_at <= NOW()` tại lúc xử lý, set `EXPIRED` ngay để tránh eligibility rác.
5. Commit rồi ACK.

## 9. Event Workflow: REVIEW_METRICS_UPDATED -> Search

**Trigger:** Event nội bộ `REVIEW_METRICS_UPDATED` được phát từ aggregator. [Rating Aggregator Consumer](review_workers.md#4-rating-aggregatorConsumer)

**Trình tự liên service:**
1. Search consumer `search-review-metrics-sync` nhận event và `_update_by_query` các document SKU theo `product_id`.
2. Guard **duy nhất** theo `event_sequence_id`.
3. Nếu thiếu `event_sequence_id`, phải chuyển DLQ (không fallback timestamp).

## 10. Event Workflow: ACCOUNT_DELETED -> Author Anonymization

**Trigger:** `ACCOUNT_DELETED` từ `t.user.events`.

**Xử lý:** [Privacy Anonymization Consumer](review_workers.md#7-privacy-anonymization-consumer)

**Trình tự:**
1. Consumer nhận `user_id`, `deleted_at`, `retention_window_expires_at`, `state_version`.
2. Normalize `user_id`.
3. Update `reviews.user_name='Người dùng đã ẩn danh'`
4. Invalidate cache page theo danh sách product bị ảnh hưởng (`INCR review:prod_ver:{product_id}`).
5. Broadcast L1 invalidation.

## 11. Ma Trận Nhất Quán (Consistency Matrix)

| Luồng | Source of Truth | Read-Your-Own-Writes | Cửa sổ eventual consistency | Cơ chế hội tụ |
|---|---|---|---|---|
| Create review | PostgreSQL (`reviews`, `review_eligibilities`) | Có ở API response | 1-3s để event xuất hiện downstream | CDC outbox + consumer retries |
| Helpful vote | Redis delta (ngắn hạn), PostgreSQL (dài hạn) | Có ở API action result | <= 30s cho DB aggregate | Lua atomic + sync worker 30s |
| Seller reply | PostgreSQL + outbox | Có sau commit | 1-5s cho cache/list downstream | Versioned cache invalidation |
| Account deletion anonymize | PostgreSQL (`reviews`) | Không yêu cầu RYOW cho user đã xóa | <= 60s để mọi page cache hội tụ | Batch anonymize + version bump per product |

## 12. Kịch bản lỗi và tự phục hồi

1. **Duplicate event:** idempotency table chặn xử lý lặp.
2. **Out-of-order event:** guard bằng `event_sequence_id`/`state_version` trước khi apply.
3. **Missing required sequencing field:** chuyển `t.review.dlq`, không xử lý mù.
4. **Connector lag tăng cao:** cảnh báo theo lag SLO, tăng tài nguyên Kafka Connect hoặc giảm batch producer.
5. **Crash giữa chừng helpful sync:** phục hồi từ `review:processing_deltas`.
6. **Poison message:** đẩy DLQ sau ngưỡng retry, không block toàn bộ partition.
