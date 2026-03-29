# Review Service - Events Structure

**Liên kết nhanh (Related Documents):**
*   [Database Schema](review_db_schema.md)
*   [Caching Schema](review_caching_schema.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

---
Tài liệu này định nghĩa contract sự kiện chính thức của Review Service theo hướng contract-first. Mục tiêu là để mọi service downstream (Search, Notification, Analytics, internal workers) xử lý nhất quán trong môi trường bất đồng bộ.

## 1. Kafka Topics Map

### 1.1. Topics Produce

| Topic Name | Partition Key | Event Types | Consumers chính | Ghi chú |
|:---|:---|:---|:---|:---|
| `t.review.events` | `product_id` (bắt buộc) | `REVIEW_CREATED`, `REVIEW_APPROVED`, `REVIEW_REJECTED`, `REVIEW_HIDDEN`, `REVIEW_METRICS_UPDATED`, `SELLER_REPLY_CREATED` | AI Agent, Search, Notification, internal workers | Mọi event phải chứa `product_id` để giữ ordering theo sản phẩm. |

### 1.2. Topics Consume

| Topic Name | Consumer Group | Producer | Events | Mục đích |
|:---|:---|:---|:---|:---|
| `t.order.events` | `review-order-sync` | Order Service | `ORDER_COMPLETED` | Cấp quyền đánh giá theo từng item đã mua |
| `t.ai_agent.events` | `review-moderation` | AI Agent Service | `TEXT_MODERATED`, `MEDIA_MODERATED` | Đồng bộ kết quả kiểm duyệt |
| `t.user.events` | `review-privacy-sync` | User Service | `ACCOUNT_DELETED` | Ẩn danh dữ liệu tác giả review khi tài khoản bị xóa |

> **Policy đã chốt:** Review Service không consume `USER_PROFILE_UPDATED`. Trường `user_name` là snapshot tại thời điểm tạo review và chỉ thay đổi trong luồng anonymization.

## 2. CloudEvents Envelope (Bắt buộc)

```json
{
  "event_id": "uuid-v7",
  "event_type": "REVIEW_CREATED",
  "event_schema_version": 1,
  "aggregate_type": "REVIEW",
  "aggregate_id": "review-uuid",
  "correlation_id": "req-or-workflow-id",
  "timestamp": "2026-03-28T10:00:00Z",
  "source": "review-service",
  "state_version": 1,
  "event_sequence_id": 1743156000000,
  "payload": {}
}
```

**Giải thích trường bắt buộc:**
*   `event_schema_version`: version của schema payload.
*   `state_version`: version trạng thái aggregate tại thời điểm phát event.
*   `event_sequence_id`: sequence đơn điệu để guard out-of-order ở downstream.

## 3. Delivery Semantics

1. Delivery model là At-Least-Once. Consumer bắt buộc idempotent theo `event_id`.
2. Out-of-order phải xử lý bằng `event_sequence_id` hoặc `state_version`.
3. Không ACK trước khi local transaction commit.
4. Retry policy mặc định:
   - Retry tối đa 10 lần theo exponential backoff 1s -> 5m.
   - Quá ngưỡng chuyển DLQ theo policy của consumer/connector.

## 4. Consumed Events Contract

### 4.1. ORDER_COMPLETED

**Topic:** `t.order.events`

**Payload tối thiểu (subset Review cần):**
```json
{
  "order_id": 10001,
  "user_id": 999,
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

**Hành động:** Insert `review_eligibilities` theo từng item bằng `ON CONFLICT DO NOTHING`.

### 4.2. TEXT_MODERATED / MEDIA_MODERATED

**Topic:** `t.ai_agent.events`

**Payload tối thiểu:**
```json
{
  "review_id": "uuid",
  "is_safe": true,
  "reason": null,
  "toxic_score": 0.05
}
```

**Hành động:**
*   `is_safe=true` -> update `reviews.status='APPROVED'`, emit `REVIEW_APPROVED`.
*   `is_safe=false` -> update `reviews.status='REJECTED'`, emit `REVIEW_REJECTED` với `can_retry`, `attempt_left`.

### 4.3. ACCOUNT_DELETED

**Topic:** `t.user.events`

**Payload tối thiểu:**
```json
{
  "user_id": 999,
  "deleted_at": "2026-03-28T10:30:00Z",
  "reason": "USER_REQUEST"
}
```

**Hành động:** Anonymize review author fields (`user_name`, flags ẩn danh) theo policy bảo mật dữ liệu.

## 5. Produced Events Contract

### 5.1. REVIEW_CREATED

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "variant_name": "Màu Đen, Size XXL",
  "user_id": 12345,
  "rating": 5,
  "content": "Sản phẩm tốt, giao hàng nhanh",
  "media_urls": ["s3://img1.jpg"],
  "created_at": "2026-03-28T09:00:00Z",
  "status": "PENDING_MODERATION"
}
```

### 5.2. REVIEW_APPROVED

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "user_id": 12345,
  "rating": 5,
  "has_media": true,
  "created_at": "2026-03-28T09:00:00Z",
  "previous_status": "PENDING_MODERATION"
}
```

### 5.3. REVIEW_REJECTED

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "user_id": 12345,
  "rating": 5,
  "has_media": true,
  "reason": "Chứa nội dung không phù hợp",
  "previous_status": "PENDING_MODERATION",
  "can_retry": true,
  "attempt_left": 1,
  "created_at": "2026-03-28T09:00:00Z"
}
```

**Rule bắt buộc:** Worker aggregate chỉ trừ điểm khi `previous_status='APPROVED'`.

### 5.4. REVIEW_HIDDEN

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "user_id": 12345,
  "rating": 5,
  "has_media": true,
  "previous_status": "APPROVED",
  "reason": "REPORT_THRESHOLD_EXCEEDED",
  "created_at": "2026-03-28T09:00:00Z"
}
```

### 5.5. REVIEW_METRICS_UPDATED

```json
{
  "product_id": "prod-uuid-001",
  "average_rating": 4.75,
  "total_reviews": 1251,
  "total_with_media": 300,
  "state_version": 88,
  "event_sequence_id": 1743156000123,
  "updated_at": "2026-03-28T10:05:00Z"
}
```

**Rule bắt buộc cho downstream:**
- Guard out-of-order ưu tiên `event_sequence_id`.
- `updated_at` chỉ dùng cho observability/debug, không phải khóa ordering chính.

### 5.6. SELLER_REPLY_CREATED

```json
{
  "reply_id": "uuid",
  "review_id": "uuid-review",
  "product_id": "prod-uuid-001",
  "user_id": 12345,
  "seller_id": "uuid-seller",
  "created_at": "2026-03-28T11:00:00Z"
}
```

## 6. Consumer Requirements

1. Mỗi consumer phải có bảng idempotency riêng theo `event_id`.
2. Không dùng chung bảng idempotency cho nhiều nghiệp vụ.
3. Mọi state transition phải guard bằng `event_sequence_id` hoặc `state_version`.
4. Với partial update downstream (ví dụ Search), khi gặp `document_missing_exception` phải retry/self-heal, không drop message.

## 7. Compatibility Rules

1. Bổ sung field mới trong payload phải backward compatible (không đổi semantics field cũ).
2. Khi thay đổi cấu trúc payload có breaking change, phải tăng `event_schema_version`.
3. Consumer phải bỏ qua field lạ (unknown fields) thay vì fail parse toàn bộ event.
