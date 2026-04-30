# Review Service - Events Structure

**Liên kết nhanh (Related Documents):**
*   [Database Schema](review_db_schema.md)
*   [Caching Schema](review_caching_schema.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

---
Tài liệu này định nghĩa contract sự kiện chính thức của Review Service theo hướng contract-first. Mục tiêu là để mọi service downstream (Search, Notification, Seller, Analytics, internal workers) xử lý nhất quán trong môi trường bất đồng bộ.

## 0. Canonical Identity Rules

1. `user_id` của Review Service là định danh chuỗi dạng canonical `u-<digits>`.
2. Với luồng cũ còn gửi số (`999`), Review consumer phải normalize thành `u-999` trước khi ghi DB hoặc phát event mới.
3. `seller_id` là opaque string do Seller domain sở hữu; contract không ép format cụ thể (`s-...`, UUID, ...) nhưng downstream phải dùng nhất quán đúng giá trị chuỗi.

## 1. Kafka Topics Map

### 1.1. Topics Produce (Business)

| Topic Name | Partition Key | Event Types | Consumers chính | Ghi chú |
|:---|:---|:---|:---|:---|
| `t.review.events` | `product_id` (bắt buộc) | `REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_METRICS_UPDATED`, `SELLER_REPLY_CREATED` |  Notification (`REVIEW_CREATED`,`REVIEW_HIDDEN`,`SELLER_REPLY_CREATED`), Seller (`REVIEW_CREATED`,`REVIEW_HIDDEN`), internal workers | Mọi event phải chứa `product_id` để giữ ordering theo sản phẩm. |

### 1.2. Topics Produce (Operational)

| Topic Name | Partition Key | Mục đích |
|:---|:---|:---|
| `t.review.retry` | `product_id` | Retry có kiểm soát cho lỗi tạm thời của Review consumers |
| `t.review.dlq` | `product_id` | Parking lot cho poison message / contract violation |

### 1.3. Topics Consume

| Topic Name | Consumer Group | Producer | Events | Mục đích |
|:---|:---|:---|:---|:---|
| `t.order.events` | `review-order-sync` | Order Service | `ORDER_COMPLETED` | Cấp quyền đánh giá theo từng item đã mua |
| `t.user.events` | `review-privacy-sync` | User Service | `ACCOUNT_DELETED` | Ẩn danh dữ liệu tác giả review khi tài khoản bị xóa |

> **Policy đã chốt:** Review Service không consume `USER_PROFILE_UPDATED`. Trường `user_name` là snapshot tại thời điểm tạo review và chỉ thay đổi trong luồng anonymization.

## 2. CloudEvents Envelope (Bắt buộc)

```json
{
  "event_id": "uuid-v7",
  "event_type": "REVIEW_CREATED",
  "aggregate_type": "REVIEW",
  "aggregate_id": "review-uuid",
  "correlation_id": "req-or-workflow-id",
  "timestamp": "2026-03-28T10:00:00Z",
  "event_sequence_id": 1743156000001,
  "source": "review-service",
  "payload": {}
}
```

**Giải thích trường bắt buộc:**
*   `event_sequence_id`: sequence đơn điệu để guard out-of-order ở downstream.

**Quy tắc mirror fields cho tương thích ngược:**
*   Với `REVIEW_METRICS_UPDATED`, payload có thể mang thêm `event_sequence_id`/`state_version` để hỗ trợ consumer cũ.
*   Nếu có mirror fields trong payload, giá trị bắt buộc bằng đúng giá trị trên envelope.

## 3. Delivery Semantics

1. Delivery model là At-Least-Once. Consumer bắt buộc idempotent theo `event_id`.
2. Out-of-order phải xử lý bằng `event_sequence_id`
3. Không ACK trước khi local transaction commit.
4. Retry policy mặc định:
   - Retry tối đa 10 lần theo exponential backoff 1s -> 5m.
   - Quá ngưỡng chuyển `t.review.dlq` theo policy của consumer/connector.
5. Với lỗi phục hồi được, consumer có thể chuyển message sang `t.review.retry`; retry worker replay tối đa 3 vòng trước khi chuyển `t.review.dlq`.

## 4. Consumed Events Contract

### 4.1. ORDER_COMPLETED

**Topic:** `t.order.events`

**Payload tối thiểu (subset Review cần):**
```json
{
  "order_id": 10001,
  "user_id": "u-999",
  "status": "COMPLETED",
  "items": [
    {
      "product_id": "prod-001",
      "sku_id": "sku-001",
      "variant_name": "Màu Đen, Size L"
    }
  ],
  "completed_at": "2026-03-02T16:00:00Z"
}
```

**Hành động:**
*   Normalize `user_id` về canonical format `u-<digits>`.
*   Insert `review_eligibilities` theo từng item bằng upsert idempotent.
*   `review_window_days` mặc định là 30 ngày (configurable).

**Compatibility note:** nếu upstream tạm thời gửi `user_id` dạng số, Review consumer phải normalize trước khi xử lý.

### 4.2. ACCOUNT_DELETED

**Topic:** `t.user.events`

**Payload tối thiểu:**
```json
{
  "user_id": "u-999",
  "deleted_at": "2026-03-28T10:30:00Z",
  "reason": "USER_REQUEST",
  "retention_window_expires_at": "2026-06-28T10:30:00Z",
}
```

**Hành động:**
*   Normalize `user_id` nếu cần rồi anonymize fields hiển thị tác giả review.
*   `retention_window_expires_at` được giữ cho compliance pipeline; luồng ẩn danh hiển thị chạy ngay khi nhận event.

## 5. Produced Events Contract

### 5.1. REVIEW_CREATED

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "seller_id": "s-9001",
  "variant_name": "Màu Đen, Size XXL",
  "user_id": "u-12345",
  "rating": 5,
  "content": "Sản phẩm tốt, giao hàng nhanh",
  "media_urls": ["s3://img1.jpg"],
  "created_at": "2026-03-28T09:00:00Z",
  "status": "APPROVED"
}
```

### 5.2. REVIEW_UPDATED

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "seller_id": "s-9001",
  "user_id": "u-12345",
  "fields_changed": ["content", "media_urls"],
  "updated_at": "2026-03-28T09:05:00Z"
}
```

### 5.3. REVIEW_APPROVED

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "seller_id": "s-9001",
  "user_id": "u-12345",
  "rating": 5,
  "has_media": true,
  "approved_at": "2026-03-28T09:10:00Z"
}
```

### 5.4. REVIEW_HIDDEN

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "seller_id": "s-9001",
  "user_id": "u-12345",
  "rating": 5,
  "has_media": true,
  "reason": "REPORT_THRESHOLD_EXCEEDED",
  "created_at": "2026-03-28T09:00:00Z"
}
```

### 5.5. REVIEW_METRICS_UPDATED

**Fields bắt buộc:** `product_id`, `average_rating`, `total_reviews`, `state_version`, `event_sequence_id`, `updated_at`.

```json
{
  "product_id": "prod-uuid-001",
  "average_rating": 4.75,
  "total_reviews": 1251,
  "total_with_media": 300,
  "state_version": 1743156000123,
  "event_sequence_id": 1743156000123,
  "updated_at": "2026-03-28T10:05:00Z"
}
```

**Rule bắt buộc cho downstream:**
- Guard out-of-order ưu tiên `event_sequence_id`.
- `state_version` là version của aggregate theo `product_id`, bắt buộc tăng đơn điệu và mirror với `event_sequence_id`.
- `updated_at` chỉ dùng cho observability/debug, không phải khóa ordering chính.

### 5.6. SELLER_REPLY_CREATED

```json
{
  "reply_id": "uuid",
  "review_id": "uuid-review",
  "product_id": "prod-uuid-001",
  "user_id": "u-12345",
  "seller_id": "s-9001",
  "content": "Shop xin lỗi vì trải nghiệm chưa tốt, chúng tôi đã cập nhật lô hàng mới.",
  "created_at": "2026-03-28T11:00:00Z"
}
```

**Rule định tuyến mặc định:** event này được dùng để notify review author (`user_id`).

