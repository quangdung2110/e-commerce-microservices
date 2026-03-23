# Review Service - Định nghĩa Các Sự Kiện (Events Structure)

**Liên kết nhanh (Related Documents):**
*   [Database Schema (Cold Store)](review_db_schema.md)
*   [Caching Schema (Redis & Caffeine)](review_caching_schema.md)
*   [Events Structure](review_events.md)
*   [Background Workers & Jobs](review_workers.md)

---
Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện (Events) được phát đi (Emitted) và tiêu thụ (Consumed) từ Review Service thông qua Kafka.
Tuân thủ chuẩn CloudEvents chung của hệ thống.

### Bản Đồ Kafka Topics

**Topics Produce:**

| Topic Name | Partition Key | Consumers | Mô tả |
|:---|:---|:---|:---|
| `t.review.events` | `product_id` (Bắt buộc) | AI Agent, Search, Product, Notification, Internal Caches | Các sự kiện vòng đời đánh giá và thay đổi điểm số sản phẩm. *Lưu ý: Bắt buộc dùng `product_id` làm Partition Key để đảm bảo các event làm thay đổi sao (Rating, Metric) của cùng 1 sản phẩm được Consumer xử lý tuần tự, chống Race Condition khi UPDATE Aggregate DB và ElasticSearch.* |

**Topics Consume:**

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|:---|:---|:---|:---|:---|
| `t.order.events` | `review-order-sync` | Order Service | `ORDER_COMPLETED` | Cấp quyền đánh giá cho khách hàng |
| `t.ai_agent.events`| `review-moderation` | AI Agent Service| `TEXT_MODERATED`, `MEDIA_MODERATED` | Nhận kết quả kiểm duyệt AI |
| `t.user.events` | `review-user-sync` | User Service | `USER_PROFILE_UPDATED` | Cập nhật tên người dùng (Phi chuẩn hóa) |

---

## 1. Cấu trúc Message Header chung (CloudEvents Standard)
```json
{
  "event_id": "uuid-v7-1234",
  "event_type": "REVIEW_CREATED",
  "aggregate_type": "REVIEW",
  "aggregate_id": "review-uuid",
  "correlation_id": "req-123",
  "timestamp": "ISO 8601 UTC",
  "source": "review-service",
  "payload": { ... }
}
```

## 2. Các Sự Kiện Lắng Nghe (Consumed)

### 2.1. ORDER_COMPLETED (Từ `t.order.events`)
**Ngữ cảnh:** Một đơn hàng đã được giao thành công và hoàn tất vòng đời.
**Payload bên trong:** Chứa thông tin `order_id`, `user_id`, và mảng các sản phẩm `products` (gồm `product_id`, `sku_id`, `variant_name`).
**Hành động Review Service:** 
Thực thi vòng lặp Insert vào bảng `review_eligibilities` với trạng thái `PENDING` cấp quyền cho người dùng đánh giá các phân loại sản phẩm trong đơn.

### 2.2. TEXT_MODERATED / MEDIA_MODERATED (Từ AI Agent Service)
**Ngữ cảnh:** AI Agent phân tích xong nội dung Text hoặc Ảnh/Video của một đánh giá mới.
**Payload bên trong:** `{"review_id": "uuid", "is_safe": true, "reason": null, "toxic_score": 0.05}`
**Hành động Review Service:** 
Cập nhật cột `status` trong bảng `reviews` thành `APPROVED` hoặc `REJECTED`. 
- Nếu `APPROVED`: Sinh tiếp sự kiện `REVIEW_APPROVED` để đi tính toán điểm số tổng.
- Nếu `REJECTED`: Sinh sự kiện `REVIEW_REJECTED`. Chặn điểm số tổng, check biến số `attempt_count` để đổi `status` quyền xét duyệt thành `REJECTED_RETRY_PENDING` nếu còn lượt.

### 2.3. USER_PROFILE_UPDATED (Từ `t.user.events`)
**Ngữ cảnh:** Người dùng thay đổi Tên ở User Service.
**Hành động Review Service:** 
(Tùy chọn/Chạy chậm) Quét và cập nhật (UPDATE) lại các cột phi chuẩn hóa `user_name` trong bảng `reviews` của các đánh giá cũ để đồng bộ giao diện hiển thị.

## 3. Các Sự Kiện Gửi Đi (Produced)

### 3.1. REVIEW_CREATED
**Ngữ cảnh:** Khách hàng vừa gửi form đánh giá thành công. Trạng thái trong DB là `PENDING_MODERATION`.
**Consumer hưởng lợi:** 
- AI Agent Service: Lấy `content` và `media_urls` đi phân loại độc hại (Toxicity Check), NSFW.

**Cấu trúc Payload:**
```json
{
    "review_id": "uuid",
    "product_id": 9991238,
    "sku_id": "sku-123",
    "variant_name": "Màu: Đen, Size: XXL",
    "user_id": 12345,
    "rating": 5,
    "content": "Sản phẩm tốt, giao hàng nhanh",
    "media_urls": ["s3://img1.jpg"]
}
```

### 3.2. REVIEW_APPROVED
**Ngữ cảnh:** Đánh giá đã pass vòng kiểm duyệt AI và được public.
**Consumer hưởng lợi:** 
- Internal Consumer (Aggregate Worker): Tính toán lại trung bình sao và số lượng vote trong bảng `product_rating_aggregates`.
- Notification Service (Tùy chọn): Thông báo cho Seller biết có đánh giá mới.

**Cấu trúc Payload:**
```json
{
    "review_id": "uuid",
    "product_id": 9991238,
    "sku_id": "sku-123",
    "rating": 5,
    "has_media": true
}
```

### 3.3. REVIEW_REJECTED
**Ngữ cảnh:** Đánh giá vi phạm tiêu chuẩn cộng đồng (Bị AI hoặc Admin Reject).
**Consumer hưởng lợi:** 
- Internal Consumer (Aggregate Worker): Trừ điểm khỏi mốc tổng nếu bài bị Reject sau khi đã được phân loại là APPROVED.
- Notification Service: Gửi cảnh báo hệ thống cho khách hàng. Nếu `can_retry = true`, thông báo sẽ kèm theo "Lưu ý: Bạn chỉ có 1 cơ hội cuối cùng để đăng lại hình/bài đánh giá".
- User Service: Ghi nhận lịch sử vi phạm (Nếu vi phạm nhiều lần có thể kích hoạt `USER_PENALTY_APPLIED`).

**Cấu trúc Payload:**
```json
{
    "review_id": "uuid",
    "product_id": 9991238,
    "user_id": 12345,
    "reason": "Chứa nội dung không phù hợp",
    "previous_status": "PENDING_MODERATION", 
    "rating": 5,
    "has_media": true,
    "can_retry": true,
    "attempt_left": 1
}
```
*(Lưu ý: Trường `previous_status` **BẮT BUỘC** phải phản ánh đúng trạng thái trước khi bị từ chối. Đối với AI duyệt lần đầu, nó phải là `PENDING_MODERATION`. Chỉ khi Admin/Hậu kiểm gỡ đánh giá đang xuất hiện trên Web thì nó mới là `APPROVED`. Worker Tổng hợp điểm sẽ kiểm tra trường này để quyết định có chạy lệnh trừ điểm hay không).*

### 3.4. REVIEW_HIDDEN
**Ngữ cảnh:** Đánh giá bị ẩn tự động do hệ thống cộng đồng cắm cờ Report đạt ngưỡng.
**Consumer hưởng lợi:** 
- Internal Consumer (Aggregate Worker): Trừ điểm tạm thời khỏi mốc tổng cho đến khi Admin Review và phục hồi (nếu báo sai).

**Cấu trúc Payload:**
```json
{
    "review_id": "uuid",
    "product_id": 9991238,
    "rating": 5,
    "has_media": true,
    "previous_status": "APPROVED"
}
```

### 3.5. REVIEW_METRICS_UPDATED
**Ngữ cảnh:** Sau khi có bảng `product_rating_aggregates` thay đổi điểm số trung bình (Ví dụ: Từ 4.8 xuống 4.7 sao).
**Consumer hưởng lợi:**
- Search Service: Index lại điểm số trung bình vào Elasticsearch để User có thể Filter "Sản phẩm từ 4 sao trở lên".
- Product Service: Đồng bộ lại điểm số vào Cold DB nếu cần thiết cho các luồng quản trị.

**Cấu trúc Payload:**
```json
{
    "product_id": 9991238,
    "average_rating": 4.75,
    "total_reviews": 1251
}
```

### 3.6. SELLER_REPLY_CREATED
**Ngữ cảnh:** Người bán gửi phản hồi cho một review của khách.
**Consumer hưởng lợi:**
- Notification Service: Bắn Push Notification báo khách hàng "Shop đã trả lời đánh giá của bạn".

**Cấu trúc Payload:**
```json
{
    "reply_id": "uuid",
    "review_id": "uuid-review",
    "user_id": 12345, 
    "seller_id": "uuid-seller"
}
```
