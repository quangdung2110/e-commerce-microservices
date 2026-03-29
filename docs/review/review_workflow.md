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
2. **Lock quyền review (DB atomic):** chạy câu `UPDATE ... WHERE status IN ('PENDING','REJECTED_RETRY_PENDING') AND attempt_count < 2 RETURNING attempt_count` trên `review_eligibilities`.
3. **Nếu lock thất bại:** trả lỗi nghiệp vụ (đã review hoặc quá số lần thử).
4. **Trong cùng transaction:**
   - Insert `reviews` với `status='PENDING_MODERATION'`.
   - Insert `review_media` nếu có.
   - Insert outbox `REVIEW_CREATED` (kèm envelope fields version/sequence).
5. Commit transaction.
6. Increment cache version `review:prod_ver:{product_id}` để invalidation page 1.
7. Trả HTTP 201.
8. Debezium CDC publish event `REVIEW_CREATED` lên `t.review.events`.

## 3. API: Sửa Review (Trước Khi Duyệt)

**Endpoint:** `PATCH /api/v1/reviews/{review_id}`

**Rule:** Chỉ cho sửa khi review chưa duyệt (`PENDING_MODERATION`) hoặc theo policy cho phép resubmit sau reject.

**Workflow chuẩn:**
1. Validate ownership theo `user_id`.
2. `SELECT ... FOR UPDATE` review cần sửa.
3. Kiểm tra state cho phép chỉnh sửa.
4. Update `content/media`, tăng `state_version`.
5. Insert outbox event phù hợp (nếu policy cần phát event update).
6. Commit và refresh cache version theo `product_id`.

## 4. API: Like/Unlike Review

**Endpoint:** `POST /api/v1/reviews/{review_id}/helpful`

**Workflow chuẩn:**
1. API gọi Lua script atomically:
   - Like: `SETBIT=1` nếu trước đó là 0 thì `INCR review:{review_id}:delta` + `SADD review:dirty_delta_keys`.
   - Unlike: `SETBIT=0` nếu trước đó là 1 thì `DECR delta` + `SADD dirty`.
2. API trả kết quả ngay (không block DB write).
3. Scheduled worker mỗi 30s sync delta xuống DB và cập nhật cache.

## 5. API: Report Review

**Endpoint:** `POST /api/v1/reviews/{review_id}/reports`

**Workflow chuẩn:**
1. Validate reporter role (`USER` hoặc `SELLER`).
2. Insert `review_reports` với unique constraints chống report trùng.
3. Tăng `reviews.report_count` trong transaction.
4. Nếu vượt ngưỡng policy, đổi `reviews.status='HIDDEN_BY_REPORT'`.
5. Insert outbox `REVIEW_HIDDEN` khi state thực sự đổi.
6. Commit và trigger aggregate recalculation qua event pipeline.

## 6. API: Seller Reply

**Endpoint:** `POST /api/v1/reviews/{review_id}/seller-replies`

**Workflow chuẩn:**
1. Validate seller ownership của product.
2. Upsert `review_seller_replies` (1 review chỉ 1 reply active).
3. Update `reviews.is_seller_replied=true`.
4. Insert outbox `SELLER_REPLY_CREATED`.
5. Commit.
6. `INCR review:prod_ver:{product_id}` để refresh page cache hiển thị reply.

## 7. API Đọc: Aggregate và Danh Sách Review

### 7.1. Aggregate

**Endpoint:** `GET /api/v1/reviews/products/{product_id}/aggregate`

**Workflow:**
1. Read `review:prod:agg:{product_id}` từ Redis.
2. Cache miss -> query `product_rating_aggregates`, rồi SET cache với TTL + jitter.
3. Nếu DB quá tải, có thể trả stale cache tối đa 30s (stale-while-revalidate).

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
2. Insert eligibility từng item với `ON CONFLICT DO NOTHING`.
3. Commit rồi ACK.
4. User có thể submit review cho item hợp lệ trong cửa sổ `expires_at`.

## 9. Event Workflow: AI Moderation -> State Transition

**Trigger:** `TEXT_MODERATED` / `MEDIA_MODERATED` từ `t.ai_agent.events`.

**Xử lý:** [AI Moderation Synchronizer](review_workers.md#4-ai-moderation-synchronizer)

**Trình tự:**
1. Idempotency guard theo `event_id`.
2. Lock review row.
3. Apply transition `PENDING_MODERATION -> APPROVED/REJECTED`.
4. Insert outbox `REVIEW_APPROVED` hoặc `REVIEW_REJECTED`.
5. Debezium publish event.
6. Aggregator worker cập nhật metrics và phát `REVIEW_METRICS_UPDATED`.

## 10. Event Workflow: REVIEW_METRICS_UPDATED -> Search/Notification

**Trigger:** Event nội bộ `REVIEW_METRICS_UPDATED` được phát từ aggregator.

**Trình tự liên service:**
1. Search consumer `search-review-metrics-sync` nhận event và `_update_by_query` các document SKU theo `product_id`.
2. Guard theo `event_sequence_id` (ưu tiên), fallback `updated_at` chỉ để tương thích ngược.
3. Notification service có thể consume các event review lifecycle (`REVIEW_APPROVED`, `REVIEW_REJECTED`, `SELLER_REPLY_CREATED`) để gửi thông báo.

## 11. Event Workflow: ACCOUNT_DELETED -> Author Anonymization

**Trigger:** `ACCOUNT_DELETED` từ `t.user.events`.

**Xử lý:** [Privacy Anonymization Consumer](review_workers.md#8-privacy-anonymization-consumer)

**Trình tự:**
1. Consumer nhận `user_id`, `deleted_at`.
2. Update `reviews.user_name='Người dùng đã ẩn danh'`, `is_author_anonymized=true`, `anonymized_at`.
3. Invalidate cache page theo danh sách product bị ảnh hưởng (`INCR review:prod_ver:{product_id}`).
4. Broadcast L1 invalidation.

## 12. Ma Trận Nhất Quán (Consistency Matrix)

| Luồng | Source of Truth | Read-Your-Own-Writes | Cửa sổ eventual consistency | Cơ chế hội tụ |
|---|---|---|---|---|
| Create review | PostgreSQL (`reviews`, `review_eligibilities`) | Có ở API response | 1-3s để event xuất hiện downstream | CDC outbox + consumer retries |
| AI moderation | PostgreSQL state + outbox | Có trên read DB ngay sau commit | 1-10s cho Search/Notification | Kafka + idempotent consumers |
| Helpful vote | Redis delta (ngắn hạn), PostgreSQL (dài hạn) | Có ở API action result | <= 30s cho DB aggregate | Lua atomic + sync worker 30s |
| Seller reply | PostgreSQL + outbox | Có sau commit | 1-5s cho cache/list downstream | Versioned cache invalidation |
| Account deletion anonymize | PostgreSQL (`reviews`) | Không yêu cầu RYOW cho user đã xóa | <= 60s để mọi page cache hội tụ | Batch anonymize + version bump per product |

## 13. Kịch bản lỗi và tự phục hồi

1. **Duplicate event:** idempotency table chặn xử lý lặp.
2. **Out-of-order event:** guard bằng `event_sequence_id`/`state_version` trước khi apply.
3. **Connector lag tăng cao:** cảnh báo theo lag SLO, tăng tài nguyên Kafka Connect hoặc giảm batch producer.
4. **Crash giữa chừng helpful sync:** phục hồi từ `review:processing_deltas`.
5. **Poison message:** đẩy DLQ sau ngưỡng retry, không block toàn bộ partition.
