# Review Service - Database Schema

**Liên kết nhanh (Related Documents):**
*   [Caching Schema](review_caching_schema.md)
*   [Events Structure](review_events.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

---
PostgreSQL là nguồn sự thật duy nhất của Review Service. Schema được chốt theo mục tiêu:

1. Chống race condition khi submit, moderation và report.
2. Chống drift giữa DB và event pipeline.
3. Hỗ trợ truy vấn read-heavy với index rõ ràng.

## 1. Bảng review_eligibilities

Quản lý quyền review theo rule 1-1-1-1 (order, product, sku, user).

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PK | Định danh eligibility |
| `user_id` | `BIGINT` | NOT NULL | Người mua |
| `order_id` | `BIGINT` | NOT NULL | Đơn hàng |
| `product_id` | `VARCHAR(255)` | NOT NULL | Sản phẩm |
| `sku_id` | `VARCHAR(255)` | NOT NULL | Biến thể đã mua |
| `variant_name` | `VARCHAR(255)` | NULLABLE | Snapshot phục vụ hiển thị danh sách chờ đánh giá |
| `status` | `VARCHAR(30)` | DEFAULT `PENDING` | `PENDING`, `PROCESSING`, `REVIEWED`, `EXPIRED`, `REJECTED_RETRY_PENDING`, `REJECTED_FINAL` |
| `attempt_count` | `SMALLINT` | DEFAULT 0 | Số lần submit, tối đa 2 |
| `expires_at` | `TIMESTAMP` | NOT NULL | Hạn review |
| `created_at` | `TIMESTAMP` | NOT NULL | Tạo từ `ORDER_COMPLETED` |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Ràng buộc bắt buộc:**
*   `UNIQUE(order_id, product_id, sku_id, user_id)`
*   `CHECK(attempt_count >= 0 AND attempt_count <= 2)`
*   Index `B-Tree(user_id, status)`

**Submit lock SQL chuẩn (chống oversubmit):**
```sql
UPDATE review_eligibilities
SET status = 'PROCESSING',
    attempt_count = attempt_count + 1,
    updated_at = NOW()
WHERE id = :eligibility_id
  AND status IN ('PENDING', 'REJECTED_RETRY_PENDING')
  AND attempt_count < 2
RETURNING attempt_count;
```

## 2. Bảng reviews

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PK | Định danh review |
| `eligibility_id` | `UUID` | FK, NOT NULL | Tham chiếu eligibility |
| `product_id` | `VARCHAR(255)` | NOT NULL | Sản phẩm |
| `sku_id` | `VARCHAR(255)` | NOT NULL | SKU |
| `variant_name` | `VARCHAR(255)` | NULLABLE | Snapshot biến thể đã mua |
| `user_id` | `BIGINT` | NOT NULL | Tác giả review (khóa tham chiếu nghiệp vụ) |
| `user_name` | `VARCHAR(100)` | NOT NULL | Snapshot tên tại thời điểm tạo review |
| `is_author_anonymized` | `BOOLEAN` | DEFAULT FALSE | Cờ đánh dấu đã ẩn danh theo policy bảo mật |
| `anonymized_at` | `TIMESTAMP` | NULLABLE | Thời điểm ẩn danh |
| `rating` | `SMALLINT` | NOT NULL | 1..5 |
| `content` | `TEXT` | NULLABLE | Nội dung text |
| `status` | `VARCHAR(30)` | DEFAULT `PENDING_MODERATION` | `PENDING_MODERATION`, `APPROVED`, `REJECTED`, `HIDDEN_BY_REPORT` |
| `moderation_reason_public` | `TEXT` | NULLABLE | Lý do hiển thị cho user |
| `moderation_reason_internal` | `JSONB` | NULLABLE | Chi tiết nội bộ phục vụ audit/model tuning |
| `helpful_count` | `INT` | DEFAULT 0 | Tổng vote hữu ích |
| `report_count` | `INT` | DEFAULT 0 | Tổng report |
| `has_media` | `BOOLEAN` | DEFAULT FALSE | Có media |
| `is_seller_replied` | `BOOLEAN` | DEFAULT FALSE | Có phản hồi từ seller |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Ràng buộc và index:**
*   `CHECK(rating >= 1 AND rating <= 5)`
*   `B-Tree(user_id)`
*   `B-Tree(product_id, status, created_at DESC)`
*   `B-Tree(product_id, status, helpful_count DESC, id DESC)`
*   `B-Tree(product_id, sku_id, status, created_at DESC)`
*   `B-Tree(product_id, sku_id, status, helpful_count DESC, id DESC)`
*   `B-Tree(product_id, rating, created_at DESC)`
*   Partial index media:
    *   `B-Tree(product_id, status, created_at DESC) WHERE has_media = TRUE`
    *   `B-Tree(product_id, status, helpful_count DESC, id DESC) WHERE has_media = TRUE`

## 3. Bảng review_media

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PK | |
| `review_id` | `UUID` | FK, NOT NULL | Review cha |
| `media_type` | `VARCHAR(10)` | NOT NULL | `IMAGE` hoặc `VIDEO` |
| `media_url` | `VARCHAR(255)` | NOT NULL | URL gốc |
| `thumbnail_url` | `VARCHAR(255)` | NULLABLE | Thumbnail |
| `sort_order` | `SMALLINT` | DEFAULT 0 | Thứ tự hiển thị |
| `created_at` | `TIMESTAMP` | NOT NULL | |

**Ràng buộc:**
*   FK `review_id` nên cấu hình `ON DELETE CASCADE`
*   Index `B-Tree(review_id)`

## 4. Bảng aggregate ratings

### 4.1. product_rating_aggregates

| Cột | Kiểu | Ràng buộc |
| :--- | :--- | :--- |
| `product_id` | `VARCHAR(255)` | PK |
| `average_rating` | `DECIMAL(3,2)` | DEFAULT 0.00 |
| `total_score` | `INT` | DEFAULT 0 |
| `total_reviews` | `INT` | DEFAULT 0 |
| `total_1_star` | `INT` | DEFAULT 0 |
| `total_2_star` | `INT` | DEFAULT 0 |
| `total_3_star` | `INT` | DEFAULT 0 |
| `total_4_star` | `INT` | DEFAULT 0 |
| `total_5_star` | `INT` | DEFAULT 0 |
| `total_with_media` | `INT` | DEFAULT 0 |
| `updated_at` | `TIMESTAMP` | NOT NULL |

### 4.2. sku_rating_aggregates

Giống cấu trúc product-level, khóa chính `PRIMARY KEY(product_id, sku_id)`.

## 5. Bảng review_seller_replies

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PK | |
| `review_id` | `UUID` | UNIQUE, FK | 1 review chỉ 1 reply đang active |
| `seller_id` | `UUID` | NOT NULL | Seller trả lời |
| `content` | `TEXT` | NOT NULL | Nội dung phản hồi |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Khuyến nghị API:** dùng upsert để tránh race click gửi trùng.

## 6. Bảng review_outbox (CDC-first)

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `event_id` | `UUID` | PK | ID event |
| `aggregate_type` | `VARCHAR(50)` | NOT NULL | `REVIEW`, `REVIEW_METRIC`, `SELLER_REPLY` |
| `aggregate_id` | `VARCHAR(255)` | NOT NULL | `review_id` hoặc `product_id` |
| `partition_key` | `VARCHAR(255)` | NOT NULL | Kafka message key, chuẩn là `product_id` |
| `event_type` | `VARCHAR(100)` | NOT NULL | Loại event |
| `event_schema_version` | `INT` | NOT NULL | Version payload schema |
| `state_version` | `BIGINT` | NOT NULL | Version state aggregate |
| `event_sequence_id` | `BIGINT` | NOT NULL | Sequence đơn điệu |
| `payload` | `JSONB` | NOT NULL | CloudEvents payload |
| `created_at` | `TIMESTAMP` | DEFAULT NOW() | |

**Lưu ý vận hành:**
*   Không có các cột `status`, `retry_count`, `next_retry_at`, `last_error`.
*   Retry/DLQ được quản trị ở tầng Kafka Connect và consumer downstream.
*   Khuyến nghị partition table theo `created_at` để retention hiệu quả.

## 7. Bảng review_reports

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PK | |
| `review_id` | `UUID` | FK, NOT NULL | Review bị report |
| `reporter_role` | `VARCHAR(20)` | NOT NULL | `USER` hoặc `SELLER` |
| `reporter_user_id` | `BIGINT` | NULLABLE | Dùng khi role=USER |
| `reporter_seller_id` | `UUID` | NULLABLE | Dùng khi role=SELLER |
| `reason_type` | `VARCHAR(50)` | NOT NULL | Loại vi phạm |
| `status` | `VARCHAR(30)` | DEFAULT `PENDING_REVIEW` | `PENDING_REVIEW`, `RESOLVED_REJECTED`, `RESOLVED_ACCEPTED` |
| `admin_decision_note` | `TEXT` | NULLABLE | Lý do xử lý của admin |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Semantics chuẩn:**
*   `RESOLVED_ACCEPTED` nghĩa là report hợp lệ, review bị ẩn (`HIDDEN_BY_REPORT`).
*   Không xóa cứng review trừ khi policy riêng yêu cầu legal takedown.

**Ràng buộc:**
*   `CHECK((reporter_role='USER' AND reporter_user_id IS NOT NULL AND reporter_seller_id IS NULL) OR (reporter_role='SELLER' AND reporter_seller_id IS NOT NULL AND reporter_user_id IS NULL))`
*   Partial unique indexes chống duplicate khi có NULL:
  *   `UNIQUE(review_id, reporter_user_id) WHERE reporter_role='USER'`
  *   `UNIQUE(review_id, reporter_seller_id) WHERE reporter_role='SELLER'`
*   Index `B-Tree(review_id, status)`

## 8. Bảng idempotency nội bộ

### 8.1. ai_moderation_idempotency

| Cột | Kiểu | Ràng buộc |
| :--- | :--- | :--- |
| `event_id` | `UUID` | PK |
| `processing_status` | `VARCHAR(20)` | NOT NULL (`PROCESSING`, `COMPLETED`, `SKIPPED`, `FAILED`) |
| `created_at` | `TIMESTAMP` | NOT NULL |
| `updated_at` | `TIMESTAMP` | NOT NULL |

### 8.2. rating_aggregator_idempotency

| Cột | Kiểu | Ràng buộc |
| :--- | :--- | :--- |
| `event_id` | `UUID` | PK |
| `created_at` | `TIMESTAMP` | NOT NULL |

**Retention khuyến nghị:** xóa record idempotency > 30 ngày bằng job hàng ngày.

---

## Design Decisions

1. Không dùng Elasticsearch cho truy vấn review core; PostgreSQL với composite/partial index đáp ứng tốt use case hiện tại.
2. AI moderation chạy async để không block luồng submit.
3. `user_name` là snapshot tại thời điểm tạo review; không đồng bộ rename theo `USER_PROFILE_UPDATED`.
4. Khi nhận `ACCOUNT_DELETED`, bắt buộc anonymize dữ liệu hiển thị tác giả review.
5. Tách `moderation_reason_public` và `moderation_reason_internal` để tránh rò rỉ tín hiệu nội bộ AI.
