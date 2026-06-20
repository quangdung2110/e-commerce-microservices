# Review Service - Events Structure

**Liên kết nhanh (Related Documents):**
*   [Database Schema](review_db_schema.md)
*   [Workers](review_workers.md)
*   [Workflows](review_workflow.md)

---


Tài liệu này định nghĩa contract sự kiện chính thức của Review Service theo hướng contract-first. Mục tiêu là để mọi service downstream (Search, Notification, Analytics, internal workers) xử lý nhất quán trong môi trường bất đồng bộ.

## 1. Kafka Topics Map

### 1.1. Topics Produce (Business)

| Topic Name | Partition Key | Event Types | Consumers | Ghi chú |
|:---|:---|:---|:---|:---|
| `t.review.events` | `product_id` (bắt buộc) | `REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_METRICS_UPDATED`, `ADMIN_REPLY_CREATED` | **Notification Service** (`REVIEW_CREATED`, `REVIEW_HIDDEN`, `ADMIN_REPLY_CREATED`), **Search Service** (`REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_METRICS_UPDATED`), **Analytics Service** (`REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_APPROVED`, `REVIEW_HIDDEN`), **Product Service** (đồng bộ read-model metrics sản phẩm qua `REVIEW_METRICS_UPDATED`) | Mọi event phải chứa `product_id` để giữ ordering theo sản phẩm. |

### 1.2. Topics Produce (Operational)

| Topic Name | Partition Key | Mục đích |
|:---|:---|:---|
| `t.review.retry` | `product_id` | Retry có kiểm soát cho lỗi tạm thời của Review consumers |
| `t.review.dlq` | `product_id` | Parking lot cho poison message / contract violation |

### 1.3. Topics Consume

| Topic Name | Consumer Group | Producer | Events | Mục đích |
|:---|:---|:---|:---|:---|
| `t.order.events` | `review-order-sync` | **Order Service** | `ORDER_COMPLETED` | Cấp quyền đánh giá theo từng item đã mua |
| `t.user.events` | `review-privacy-sync` | **User Service** | `ACCOUNT_DELETED` | Ẩn danh dữ liệu tác giả review khi tài khoản bị xóa |

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
  "source": "review-service",
  "payload": {}
}
```


## 3. Delivery Semantics

1. Delivery model là At-Least-Once. Consumer bắt buộc idempotent theo `event_id`.
2. Out-of-order được xử lý bằng **partition ordering** (key = `product_id`); Kafka bảo đảm thứ tự message trong cùng partition.
3. Không ACK message trước khi local transaction commit.
4. Retry policy mặc định cho consumer:
   - Retry tối đa 10 lần theo exponential backoff 1s -> 5m.
   - Quá ngưỡng chuyển `t.review.dlq`.
5. Retry Replayer Worker (`t.review.retry`):
   - Dùng cho các event tạm thời lỗi (ví dụ: DB timeout, network partition).
   - Replay tối đa 3 vòng (`retry_round > 3` -> chuyển `t.review.dlq`).
   - Giữ nguyên `event_id` để idempotency tự nhiên hoạt động đúng.

> **Phân biệt retry tier:**
> - Consumer level: xử lý lỗi tạm thời ngay, retry 10 lần rồi chuyển DLQ.
> - Retry Replayer: xử lý lỗi có thể phục hồi được, replay 3 vòng rồi chuyển DLQ.
> - DLQ Consumer: inspect và replay thủ công (không auto-retry).

## 4. Consumed Events Contract

### 4.1. ORDER_COMPLETED

**Topic:** `t.order.events` | **Consume từ:** Order Service | **Consumer Group:** `review-order-sync`

**Payload tối thiểu (subset Review cần):**
```json
{
  "order_id": 10001,
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
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
*   Insert `review_eligibilities` theo từng item bằng upsert idempotent.
*   `review_window_days` mặc định là 30 ngày (configurable).

### 4.2. ACCOUNT_DELETED

**Topic:** `t.user.events` | **Consume từ:** User Service (republish từ Auth `USER_DELETED`) | **Consumer Group:** `review-privacy-sync`

> **Nguồn gốc:** Auth Service phát `USER_DELETED` → User Service consume, xử lý cleanup PII, republish lại với tên `ACCOUNT_DELETED`.

*   **Giá trị `reason` hợp lệ:**
    | Giá trị | Mô tả |
    |:--------|:------|
    | `GDPR_REQUEST` | User tự yêu cầu xóa tài khoản (quyền GDPR) |
    | `ADMIN_DELETION` | Admin xóa tài khoản người dùng |

**Payload tối thiểu:**
```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "deleted_at": "2026-03-28T10:30:00Z",
  "reason": "GDPR_REQUEST" | "ADMIN_DELETION",
  "retention_window_expires_at": "2026-06-28T10:30:00Z"
}
```

**Hành động:**
*   Anonymize fields hiển thị tác giả review.
*   `retention_window_expires_at` được giữ cho compliance pipeline; luồng ẩn danh hiển thị chạy ngay khi nhận event.

## 5. Produced Events Contract

### 5.1. REVIEW_CREATED

**Topic:** `t.review.events` | **Produce cho:** Notification Service

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "variant_name": "Màu Đen, Size XXL",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "rating": 5,
  "content": "Sản phẩm tốt, giao hàng nhanh",
  "media_urls": ["s3://img1.jpg"],
  "created_at": "2026-03-28T09:00:00Z",
  "status": "APPROVED"
}
```

### 5.2. REVIEW_UPDATED

**Topic:** `t.review.events` | **Produce cho:** Notification Service (nếu user được notify)

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "fields_changed": ["content", "media_urls"],
  "updated_at": "2026-03-28T09:05:00Z"
}
```

### 5.3. REVIEW_HIDDEN

**Topic:** `t.review.events` | **Produce cho:** Notification Service

**Trigger:** Review bị ẩn do báo cáo vi phạm (report threshold reached).

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "hidden_reason": "REPORT_THRESHOLD_EXCEEDED",
  "hidden_at": "2026-03-28T09:10:00Z"
}
```

**Hành động khi nhận event:**
*   Rating Aggregator: trừ điểm/tổng số review khỏi `product_rating_aggregates`.
*   Notification: gửi thông báo cho user biết review bị ẩn.

### 5.4. REVIEW_METRICS_UPDATED

**Topic:** `t.review.events` | **Produce cho:** Search Service (re-index)

**Fields bắt buộc:** `product_id`, `average_rating`, `total_reviews`, `updated_at`.

```json
{
  "product_id": "prod-uuid-001",
  "average_rating": 4.75,
  "total_reviews": 1251,
  "total_with_media": 300,
  "updated_at": "2026-03-28T10:05:00Z"
}
```

**Rule bắt buộc cho downstream:**
- Ordering đảm bảo bằng partition key `product_id`; Search Service tự consume theo thứ tự trong partition mà không cần guard bằng sequence field.
- `updated_at` chỉ dùng cho observability/debug, không phải khóa ordering chính.

### 5.5. REVIEW_APPROVED

**Topic:** `t.review.events` | **Produce cho:** Notification Service

**Trigger:** Review được duyệt (auto-approve hoặc admin approve).

```json
{
  "review_id": "uuid",
  "product_id": "prod-uuid-001",
  "sku_id": "sku-123",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "rating": 5,
  "content": "Sản phẩm tốt, giao hàng nhanh",
  "media_urls": ["s3://img1.jpg"],
  "approved_at": "2026-03-28T09:01:00Z",
  "approval_method": "AUTO" | "ADMIN"
}
```

### 5.6. ADMIN_REPLY_CREATED

**Topic:** `t.review.events` | **Produce cho:** Notification Service (gửi thông báo cho review author)

```json
{
  "id": "uuid",
  "review_id": "uuid-review",
  "product_id": "prod-uuid-001",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "content": "platform xin lỗi vì trải nghiệm chưa tốt, chúng tôi đã cập nhật lô hàng mới.",
  "created_at": "2026-03-28T11:00:00Z"
}
```

**Rule định tuyến mặc định:** event này được dùng để notify review author (`user_id`).

**Lưu ý:** Field `id` tương ứng với primary key của bảng `review_admin_replies`.
