# Review Service - Database Schema

**Liên kết nhanh (Related Documents):**
*   [Events Structure](review_events.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

---
PostgreSQL là nguồn sự thật duy nhất của Review Service.

## 1. Bảng review_eligibilities

Quản lý quyền review theo rule 1-1-1-1 (order, product, sku, user).

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
| `id` | `UUID` | PK | Định danh eligibility |
| `user_id` | `VARCHAR(64)` | NOT NULL | Người mua (canonical) |
| `order_id` | `BIGINT` | NOT NULL | Đơn hàng |
| `product_id` | `VARCHAR(255)` | NOT NULL | Sản phẩm |
| `sku_id` | `VARCHAR(255)` | NOT NULL | Biến thể đã mua |
| `variant_name` | `VARCHAR(255)` | NULLABLE | Snapshot phục vụ hiển thị danh sách chờ đánh giá |
| `status` | `VARCHAR(30)` | DEFAULT `PENDING` | `PENDING`, `REVIEWED`, `EXPIRED`, `REJECTED_RETRY_PENDING`, `REJECTED_FINAL` |
| `attempt_count` | `SMALLINT` | DEFAULT 0 | Số lần submit review (create/edit), tối đa 2 |
| `expires_at` | `TIMESTAMP` | NOT NULL | Hạn review |
| `created_at` | `TIMESTAMP` | NOT NULL | Tạo từ `ORDER_COMPLETED` |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Ràng buộc bắt buộc:**
*   `UNIQUE(order_id, product_id, sku_id, user_id)`
*   `CHECK(attempt_count >= 0 AND attempt_count <= 2)`
*   `CHECK(expires_at > created_at)`
*   Index `B-Tree(user_id, status)`
*   Index `B-Tree(product_id, status)`

**Quy ước attempt_count (bắt buộc):**
*   `0 -> 1` khi user submit review lần đầu.
*   `1 -> 2` khi user chỉnh sửa review (duy nhất 1 lần).
*   Không tăng thêm khi chỉ đổi trạng thái cuối (`REJECTED_FINAL`).

**Trường hợp khách hàng chỉnh sửa review (duy nhất 1 lần):**
```sql
UPDATE review_eligibilities
SET status = 'REJECTED_FINAL',
    attempt_count = attempt_count + 1,
    updated_at = NOW()
WHERE id = :eligibility_id
  AND status IN ('REVIEWED')
  AND attempt_count == 1
RETURNING attempt_count, product_id, sku_id;
```

**Trường hợp chốt trạng thái sau khi hết quyền sửa:**
```sql
UPDATE review_eligibilities
SET status = 'REJECTED_FINAL',
    updated_at = NOW()
WHERE id = :eligibility_id
  AND status IN ('REJECTED_RETRY_PENDING')
  AND attempt_count == 2
RETURNING attempt_count, product_id, sku_id;
```

## 2. Bảng reviews

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
| `id` | `UUID` | PK | Định danh review |
| `eligibility_id` | `UUID` | UNIQUE, FK, NOT NULL | 1 eligibility chỉ sinh tối đa 1 review |
| `product_id` | `VARCHAR(255)` | NOT NULL | Sản phẩm |
| `sku_id` | `VARCHAR(255)` | NOT NULL | SKU |
| `variant_name` | `VARCHAR(255)` | NULLABLE | Snapshot biến thể đã mua |
| `user_id` | `VARCHAR(64)` | NOT NULL | Tác giả review (canonical) |
| `user_name` | `VARCHAR(100)` | NOT NULL | Snapshot tên tại thời điểm tạo review |
| `rating` | `SMALLINT` | NOT NULL | 1..5 |
| `content` | `TEXT` | NULLABLE | Nội dung text |
| `status` | `VARCHAR(30)` | DEFAULT `APPROVED` | `APPROVED`, `HIDDEN_BY_REPORT` |
| `report_count` | `INT` | DEFAULT 0 | Tổng report |
| `has_media` | `BOOLEAN` | DEFAULT FALSE | Có media |
| `is_admin_replied` | `BOOLEAN` | DEFAULT FALSE | Có phản hồi từ admin |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Ghi chú về trạng thái review:**
*   Review tự động được tạo với `status='APPROVED'`.
*   Trạng thái `HIDDEN_BY_REPORT` được set khi review bị ẩn do báo cáo vi phạm.
*   Các trạng thái reject (`REJECTED_*`) chỉ áp dụng cho `review_eligibilities`, không áp dụng cho `reviews`:
    *   Eligibility kiểm soát quyền sửa review của user.
    *   Review không cần biết lý do eligibility bị reject - chỉ cần biết review có hiển thị được hay không.

**Ràng buộc và index:**
*   `CHECK(rating >= 1 AND rating <= 5)`
*   `CHECK(report_count >= 0)`
*   `B-Tree(user_id)`
*   `B-Tree(product_id, sku_id, created_at DESC)`
*   `B-Tree(product_id, rating, created_at DESC)`
*   Partial index media:
  *   `B-Tree(product_id, status, created_at DESC) WHERE has_media = TRUE`

## 3. Bảng review_media

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
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

## 4. Bảng review_reports

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
| `id` | `UUID` | PK | |
| `review_id` | `UUID` | FK, NOT NULL | Review bị report |
| `reporter_id` | `VARCHAR(64)` | NOT NULL | Người thực hiện report |
| `reason` | `VARCHAR(50)` | NOT NULL | Lý do report (SPAM, OFFENSIVE, FAKE, OTHER) |
| `detail` | `TEXT` | NULLABLE | Chi tiết bổ sung |
| `status` | `VARCHAR(20)` | DEFAULT `PENDING` | `PENDING`, `REVIEWED`, `DISMISSED` |
| `created_at` | `TIMESTAMP` | NOT NULL | |

**Ràng buộc:**
*   `UNIQUE(review_id, reporter_id)` (Mỗi user chỉ report 1 review 1 lần)
*   FK `review_id` nên cấu hình `ON DELETE CASCADE`
*   Index `B-Tree(review_id, status)`

**Lưu ý:** Bảng này dùng để track chi tiết từng report. Counter `report_count` trên bảng `reviews` được tăng khi insert vào đây.

## 5. Bảng aggregate ratings

### 5.1. product_rating_aggregates

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
| `product_id` | `VARCHAR(255)` | PK | |
| `average_rating` | `DECIMAL(3,2)` | DEFAULT 0.00 | |
| `total_reviews` | `INT` | DEFAULT 0 | |
| `total_1_star` | `INT` | DEFAULT 0 | |
| `total_2_star` | `INT` | DEFAULT 0 | |
| `total_3_star` | `INT` | DEFAULT 0 | |
| `total_4_star` | `INT` | DEFAULT 0 | |
| `total_5_star` | `INT` | DEFAULT 0 | |
| `total_with_media` | `INT` | DEFAULT 0 | |
| `review_revision` | `BIGINT` | NOT NULL, tăng đơn điệu per `product_id` | Revision của aggregate; dùng để Search guard snapshot/event |
| `updated_at` | `TIMESTAMP` | NOT NULL | |
**Ràng buộc:**

- Mọi mutation hoặc reconciliation làm đổi aggregate phải tăng `review_revision`
  trong cùng transaction với giá trị aggregate mới và outbox event.
*   Aggregate không lưu `state_version`; idempotency phía producer được đơn giản hóa (chấp nhận khả năng over-count hiếm gặp do at-least-once, dựa vào micro-batch và monitoring aggregate drift).

## 6. Bảng review_admin_replies

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
| `id` | `UUID` | PK | |
| `review_id` | `UUID` | UNIQUE, FK, NOT NULL | 1 review chỉ 1 reply active |
| `content` | `TEXT` | NOT NULL | Nội dung phản hồi |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

## 7. Bảng review_outbox (CDC-first)

| Cột | Kiểu | Ràng buộc | Mô tả |
|:---|:---|:---|:---|
| `event_id` | `UUID` | PK | ID event |
| `event_type` | `VARCHAR(100)` | NOT NULL | Loại event |
| `aggregate_type` | `VARCHAR(50)` | NOT NULL | `REVIEW`, `REVIEW_METRIC`, `ADMIN_REPLY` |
| `aggregate_id` | `VARCHAR(255)` | NOT NULL | `review_id` hoặc `product_id` |
| `partition_key` | `VARCHAR(255)` | NOT NULL | Kafka message key, chuẩn là `product_id` |
| `payload` | `JSONB` | NOT NULL | Business payload; envelope metadata lấy từ các cột outbox |
| `created_at` | `TIMESTAMP` | DEFAULT NOW() | |


## 8. Idempotency tự nhiên qua Database

Review Service chỉ sử dụng **idempotency tự nhiên qua DB constraints** cho mọi nghiệp vụ ghi, không dùng bảng idempotency riêng.

### 8.1. Các ràng buộc đảm bảo idempotency

| Nghiệp vụ | Cơ chế Idempotency |
|-----------|-------------------|
| Tạo eligibility từ ORDER_COMPLETED | `UNIQUE(order_id, product_id, sku_id, user_id)` với `ON CONFLICT DO UPDATE` |
| Tạo review mới | `UNIQUE(eligibility_id)` trên bảng `reviews` |
| Admin reply | `UNIQUE(review_id)` trên bảng `review_admin_replies` |

### 8.2. Nguyên tắc áp dụng

1. **Event-driven idempotency:** Khi nhận event (ORDER_COMPLETED, USER_DELETED), consumer dựa vào unique constraints của DB để đảm bảo không ghi trùng.
2. **Consumer idempotency:** Consumer xử lý at-least-once delivery bằng cách ghi trước rồi check số row affected.


## 9. Data Retention & Privacy Policy

1. `USER_DELETED`: tài khoản bị xóa (Auth là producer)
2. Hard delete review chỉ cho case `LEGAL_TAKEDOWN` có audit trail và approval.
3. Legal takedown phải phát event bù (nếu ảnh hưởng aggregate) để downstream không drift.

---

## Design Decisions

1. Không dùng Elasticsearch cho truy vấn review core; PostgreSQL với composite/partial index đáp ứng tốt use case hiện tại.
2. `user_name` là snapshot tại thời điểm tạo review; không đồng bộ rename theo `USER_PROFILE_UPDATED`.
3. Khi nhận `USER_DELETED`, bắt buộc anonymize dữ liệu hiển thị tác giả review.
