# User Service - Events & Kafka Topics

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](user_workflows.md)
*   [Caching Schema (Redis)](user_caching_schema.md)
*   [Database Schema](user_db_design.md)
*   [Events Structure & Kafka Topics](user_events.md)

---
Tài liệu này định nghĩa cấu trúc dữ liệu Event Async và các Topic Message Queue (Kafka) mà User Service Công bố/tiêu thụ, cũng như mối gắn kết với Auth Service.

## 1. Events Consumed (Lắng nghe để cập nhật chính mình)

### Từ Topic `t.auth.events` (Sản xuất bởi Auth Service)

**1.1. `USER_VERIFIED_SUCCESS`
Kích hoạt tự động tạo hồ sơ profile ngay sau khi User ấn "Đăng ký" qua Captcha hợp lệ hoặc "Verify qua OTP Email" thành công (Tùy chính sách B2C của sàn Ecomm).

> **Lưu ý:** Event này chỉ chứa thông tin cần thiết để tạo profile (user_id, roles). Email KHÔNG được lưu trong User Service - Auth Service là nguồn chân lý cho email.

*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_VERIFIED_SUCCESS",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "u-1234",
    "roles": ["USER"]
  }
}
```

**1.2. `USER_DELETED` (GDPR / Xóa vĩnh viễn)**
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_DELETED",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "u-1234",
    "reason": "GDPR_REQUEST"
  }
}
```

## 2. Events Published (Công bố cho thế giới biết)

Hệ thống User đẩy luồng sự kiện thông qua CDC (Debezium Outbox pattern) vô Topic của chính nó: **`t.user.events`**.

### 2.1. Bản đồ Subscription (Ai nghe event này?)

| Event | Consumers | Mục đích |
|:------|:---------|:---------|
| `USER_PROFILE_UPDATED` | Chat Service | Cập nhật projection `sender_name` trong messages |
| `USER_PREFERENCES_UPDATED` | Notification Service | Sync preferences để quyết định kênh gửi |

**2.2. `USER_PROFILE_UPDATED`**
Sự kiện này cho phép các dịch vụ đồng bộ và lưu trữ cục bộ các thông tin cơ bản của người dùng (Tên, SĐT). Đặc biệt, Chat Service sẽ sử dụng sự kiện này để duy trì bản sao thông tin (Projection) nhằm hiển thị tên người gửi mà không cần gọi gRPC liên tục, giúp tối ưu hiệu năng và tính sẵn sàng.
```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "uuid-cua-user",
  "partition_key": "uuid-cua-user",
  "event_type": "USER_PROFILE_UPDATED",
  "timestamp": "2026-04-22T08:05:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "first_name": "Nguyen",
    "last_name": "Quang Dung",
    "phone_number": "0987654321",
    "date_of_birth": "1997-10-19",
    "gender": "MALE"
  }
}
```

**2.3. `USER_PREFERENCES_UPDATED`**
Thông báo khi người dùng thay đổi preferences để Notification đồng bộ. Sự kiện này được Notification Service tiêu thụ để cập nhật bản sao `notification_user_preferences`.
```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "uuid-cua-user",
  "partition_key": "uuid-cua-user",
  "event_type": "USER_PREFERENCES_UPDATED",
  "timestamp": "2026-04-22T08:06:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "language": "vi",
    "currency": "VND",
    "email_enabled": true,
    "in_app_enabled": true,
    "marketing_enabled": true
  }
}
```

---
> [!NOTE] 
> User Service không publish password hay bất kỳ credential mật mã nào (Vì nó vốn không sở hữu các field đó). Toàn bộ Auth Logic bị chắn tách biệt ở `t.auth.events`. Mảng dữ liệu User Events được xem là không quá nhạy cảm nếu so với Auth Token Leakage.
