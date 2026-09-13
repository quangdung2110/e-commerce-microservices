# Notification Service CloudEvents Schema

> **Auth delivery contract (target):** ví dụ `USER_OTP_REQUESTED` cũ bên dưới
> không còn là contract được phép dùng. Auth publish request tại
> [`contracts/events/auth/v1/asyncapi.yaml`](../../contracts/events/auth/v1/asyncapi.yaml)
> trên `t.auth.notification.events`, chỉ chứa `delivery_id` và metadata. Notification
> resolve email cùng OTP/reset URL qua
> [`Auth Internal Notification Delivery API`](../../contracts/http/auth/internal/v1/openapi.yaml)
> bằng mTLS/service identity. Không ghi log hoặc đưa email, OTP, reset token,
> reset URL hay password vào Kafka, DLQ hoặc log. Resolve có thể retry trong TTL
> và không consume OTP/reset token.

## 1. Auth security delivery events

Topic canonical: `t.auth.notification.events`. Notification consume request,
deduplicate CloudEvent `id`, gọi internal Auth API theo `delivery_id`, rồi gửi
email. Topic cũ `notification.high-priority.events` không phải nguồn contract
cho event Auth v1.

### 1.1 User preference và account-deletion inputs

Hai input này không phải delivery request. Notification consume
`USER_PREFERENCES_UPDATED` từ [User Events v1](../../contracts/events/user/v1/asyncapi.yaml)
trên `t.user.events` để upsert đúng ba flag `email_enabled`, `in_app_enabled` và
`marketing_enabled`. `updated_at` là guard chống event đến sai thứ tự.

Cleanup riêng tư dùng `USER_DELETED` từ
[Auth Events v1](../../contracts/events/auth/v1/asyncapi.yaml) trên
`t.auth.events`, không dùng `ACCOUNT_DELETED` do User republish. Consumer xóa hoặc
anonymize projection của mình ngay và giữ `hard_delete_after` nếu compliance
pipeline cần; không lookup email từ Auth.

### 1.2 Review notification inputs

Notification consume hai type từ [Review Events v1](../../contracts/events/review/v1/asyncapi.yaml)
trên `t.review.events`:

| CloudEvents type | Trigger | Dữ liệu được phép dùng |
|---|---|---|
| `com.ecommerce.review.reply-notification-requested.v1` | Admin tạo official reply đầu tiên | `review_id`, `product_id`, `recipient_user_id` |
| `com.ecommerce.review.removal-notification-requested.v1` | Review bị legal takedown | `review_id`, `product_id`, `recipient_user_id` |

Hai event không chứa reply content, `legal_case_id`, legal reason hay định danh admin.
Notification render thông báo in-app từ type, deduplicate theo CloudEvents `id` và bỏ
qua/ACK mọi Review type khác trên cùng topic. Một recipient đã nhận `USER_DELETED`
không được nhận Review notification trễ.

Review chỉ publish hai request này sau khi lấy subject lock của eligibility owner và
xác nhận owner không có HMAC deletion guard. Vì vậy một admin reply/takedown cho
subject mà Review đã xử lý `USER_DELETED` vẫn commit domain change nhưng không tạo
message Review mới; điều này không yêu cầu thêm notification table hay thay đổi payload.

### 1.3 User OTP Requested (Từ Auth Service — ví dụ canonical v1)
```json
{
  "specversion": "1.0",
  "id": "0198e09a-97e4-7d66-8a2c-997d792ae826",
  "source": "/services/auth",
  "type": "com.ecommerce.auth.user-otp-requested.v1",
  "subject": "accounts/0198e098-96f2-7ad3-91d8-570608021328",
  "time": "2026-08-27T12:40:00Z",
  "datacontenttype": "application/json",
  "dataschema": "/contracts/events/auth/v1/schemas/user-otp-requested.schema.json",
  "data": {
    "delivery_id": "0198e09a-96f2-7ad3-91d8-570608021328",
    "user_id": "0198e098-96f2-7ad3-91d8-570608021328",
    "purpose": "EMAIL_CHANGE_VERIFICATION",
    "requested_at": "2026-08-27T12:40:00Z",
    "expires_at": "2026-08-27T12:45:00Z"
  }
}
```

## 2. Low-Priority (Standard) Events

Sử dụng topic: `notification.low-priority.events`

### 2.1 Order Created (Từ Order Service)
Gửi thông báo xác nhận đơn hàng thành công. Fat Event chứa đủ tên sản phẩm và tổng tiền.

```json
{
  "specversion": "1.0",
  "id": "ord-event-999201",
  "source": "/order-service/orders",
  "type": "ORDER_CREATED",
  "time": "2026-04-27T14:30:15Z",
  "datacontenttype": "application/json",
  "data": {
    "order_id": "ORD-20260427-001",
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "customer_email": "user@example.com",
    "customer_name": "Nguyen Van A",
    "total_amount": 1500000.00,
    "currency": "VND",
    "items_summary": "iPhone 15 Pro Max và 2 sản phẩm khác",
    "payment_method": "STRIPE",
    "shipping_address": "123 Le Loi, Quận 1, TP. HCM"
  }
}
```

### 2.2 Payment Failed (Từ Payment Service)
Cảnh báo user nạp tiền/thanh toán gặp sự cố, kèm link thử lại.

```json
{
  "specversion": "1.0",
  "id": "pay-event-77382",
  "source": "/payment-service/transactions",
  "type": "PAYMENT_FAILED",
  "time": "2026-04-27T14:32:00Z",
  "datacontenttype": "application/json",
  "data": {
    "order_id": "ORD-20260427-001",
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "amount_failed": 1500000.00,
    "reason": "INSUFFICIENT_FUNDS",
    "retry_url": "https://ecommerce.com/checkout/retry/ORD-20260427-001"
  }
}
```

### 2.3 Promotion / Marketing (Từ Promotion Service)

Chưa có. Promotion Events v1 (`contracts/events/promotion/v1/asyncapi.yaml`) chỉ phát
`product-markdown-snapshot.v1` cho Product; không có `PROMOTION_FLASH_SALE`,
`VOUCHER_EXPIRING` hay coupon saved/redeemed event. Mục này được giữ làm placeholder;
marketing push cần contract mới do Promotion sở hữu trước khi Notification đăng ký consumer.

## 3. Chat Events

Sử dụng topic: `chat.message.received`

### 3.1 Chat Message Received (Từ Chat Service)
Thông báo khi có tin nhắn mới trong chat, dùng để gửi push/in-app notification khi user offline.

```json
{
  "specversion": "1.0",
  "id": "evt_01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "source": "/chat-service/messages",
  "type": "CHAT_MESSAGE_RECEIVED",
  "time": "2026-05-20T10:00:00Z",
  "datacontenttype": "application/json",
  "data": {
    "message_id": "msg_01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "conversation_id": "conv_abc123",
    "conversation_name": "Nhóm A",
    "conversation_type": "GROUP",
    "sender_id": "user_xyz",
    "sender_name": "Nguyen Van A",
    "message_type": "TEXT",
    "message_preview": "Xin chào mọi người",
    "receiver_ids": ["user_123", "user_456", "user_789"],
    "created_at": 1716000000000
  }
}
```

**Consumer Configuration:**
| Consumer Group | Topic | Priority | Mục đích & Kênh |
|:---|:---|:---|:---|
| `notification-chat-sync` | `chat.message.received` | Low | Push notification (FCM/APNs) + In-app notification |

**Logic xử lý:**
1. Nhận event `CHAT_MESSAGE_RECEIVED`
2. Check presence của từng receiver qua Redis
3. Nếu offline → gửi push notification
4. Sử dụng user preferences chung (opt-out, quiet hours)
5. Render message preview bằng template

## 4. Worker Processing Strategy
Dựa vào data từ event, Worker sẽ thực thi:
1. Lookup Template theo `type` (VD: `ORDER_CREATED`).
2. Regex replace hoặc sử dụng Handlebars (hoặc Jinja2) để thay thế data từ object `data` vào chuỗi Template.
Ví dụ Template Email: `"Xin chào {{customer_name}}, cảm ơn bạn đã đặt đơn hàng {{order_id}}!"`
3. Gửi thông qua các channel theo cấu hình Preference của user_id.
