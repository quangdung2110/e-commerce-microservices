# Auth Service - Events & Messaging Schema

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis)](auth_caching_schema.md)
*   [Database Schema](auth_db_design.md)
*   [Events Structure & Kafka Topics](auth_events.md)

---
Tài liệu cung cấp danh mục cấu trúc các thông điệp sự kiện mà Auth Service sẽ Gửi đi (Publish) cho các dịch vụ khác sử dụng, và Tiêu thụ (Consume) từ thế giới bên ngoài vào.

## 1. Events Auth Service Public Ra Bắt Cầu

Broker khuyên dùng: **Kafka**. Được tích hợp với Debezium CDC (Change Data Capture) để đảm bảo Outbox Pattern hoạt động tin cậy mà không tốn tài nguyên Polling.
Tất cả event được ném lên Topic: `t.auth.events` (Có thể Partitions chia theo `user_id` để được gửi theo thứ tự).


### 1.1 `USER_OTP_REQUESTED`
Được bắn ra khi hệ thống phát hiện có hành vi yêu cầu mã OTP từ một User
*   **Người nghe**: `Notification Service` (Gửi OTP qua Email)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_OTP_REQUEST",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "customer@gmail.com",
    "otp_type": "REGISTER_VERIFICATION" | "PASSWORD_RESET" | "LOGIN_2FA"
  }
}
```

### 1.2 `PASSWORD_RESET_REQUESTED`
Được bắn ra khi hệ thống phát hiện có hành vi yêu cầu mã OTP từ một User
*   **Người nghe**: `Notification Service` (Gửi Link Reset Password qua Email)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_OTP_REQUEST",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "customer@gmail.com",
    "otp_type": "PASSWORD_RESET"
  }
}
```

### 1.3 `USER_VERIFIED_SUCCESS`
Được bắn ra ngay sau khi User xác thực thành công (điền OTP Email hoặc đăng ký qua SSO hoàn tất). Đây là event quan trọng nhất trigger luồng sinh profile rỗng ở User Service.
*   **Người nghe**: `User Service` (Tạo bảng thông tin cá nhân), `Notification Service` (Bắn Mail "Welcome you!").
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_VERIFIED_SUCCESS",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "customer@gmail.com",
    "roles": ["USER"]
  }
}
```

### 1.3 `USER_DELETED` (GDPR / Xóa vĩnh viễn)
Được bắn ra khi tài khoản bị xóa vĩnh viễn (yêu cầu GDPR hoặc Admin xóa tài khoản). User Service sẽ dọn sạch PII.
*   **Người nghe**: `User Service`
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_DELETED",
  "occurred_at": "2026-04-21T10:12:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "reason": "GDPR_REQUEST"
  }
}
```

### 1.4 `TOKEN_THEFT_DETECTED` (Xử Lý Trừng Phạt Đánh Cắp Hàng Loạt)
Sự kiện bắn ra từ Luồng Grace Period (Xem Workflow) vào thời khắc phát hiện một cái Refresh Token HẾT HẠN ÂN HẠN 10s lại bị ai đó nộp để ăn cắp. Auth Service đã tự Ban sạch các DB Token của user đó, nhưng nó bắn thông báo này để cả hệ thống chung tay.
*   **Người nghe**: `API Gateway` (Bổ sung Denylist cho tất cả request nào khác đến)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "TOKEN_THEFT_DETECTED",
  "occurred_at": "2026-04-21T10:10:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "theft_refresh_token_id": "xyz-uuid-bị_móc_túi",
    "action_taken": "ALL_TOKENS_REVOKED"
  }
}
```

### 1.6 `USER_EMAIL_CHANGED` (Email thay đổi)
Được bắn ra khi user thay đổi email thành công. Notification Service cần nhận được event này để thông báo đẩy **In-app** cho khách hàng biết
*   **Người nghe**: `Notification Service`
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_EMAIL_CHANGED",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "old_email": "old@gmail.com",
    "new_email": "new@gmail.com"
  }
}
```

### 1.7 `USER_PASSWORD_CHANGED` (Mật khẩu thay đổi)
Được bắn ra khi user tự nguyện thay đổi mật khẩu thành công (khác với `PASSWORD_RESET_REQUESTED` - link reset qua email). Notification Service nhận event này để gửi thông báo đẩy **In-app** cho biết rằng họ đã đổi mật khẩu thành công

| Event | Trigger | Người nghe | Kênh thông báo |
|:------|:--------|:-----------|:---------------|
| `USER_PASSWORD_CHANGED` | User chủ động đổi pass | Notification Service | **In-app (Push)** - Thông báo khách đã đổi mật khẩu |
| `PASSWORD_RESET_REQUESTED` | User yêu cầu reset (quên pass) | Notification Service | **Email** - Gửi link reset |

*   **Người nghe**: `Notification Service` (Gửi In-app notification cảnh báo)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_PASSWORD_CHANGED",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "device_info": "Chrome on Windows - IP: 192.168.1.1",
    "changed_at": "2026-04-21T10:00:00Z"
  }
}
```

### 1.8 `USER_ACCOUNT_LOCKED` (Tài khoản bị tạm khóa do sai mật khẩu)
Được bắn ra khi tài khoản user bị khóa tạm thời do đăng nhập sai mật khẩu quá nhiều lần (>= 5 lần). Event này được gửi cho Notification Service để thông báo qua **Email** cho user biết tài khoản đã bị khóa.

> **Lưu ý:** Kênh thông báo là **Email** vì user không thể đăng nhập để xem In-app notification khi tài khoản bị khóa.

*   **Người nghe**: `Notification Service` (Gửi Email thông báo)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_ACCOUNT_LOCKED",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "reason": "FAILED_LOGIN_ATTEMPTS",
    "locked_until": "2026-04-21T10:30:00Z"
  }
}
```

### 1.9 `USER_UNLOCKED` (Tài khoản được tự động mở khóa)
Được bắn ra khi tài khoản user được tự động mở khóa sau 30 phút (Worker auto-unlock) hoặc do Admin unlock thủ công. Event này được gửi cho Notification Service để thông báo qua **Email** cho user biết tài khoản đã được mở khóa.

> **Lưu ý:** Kênh thông báo là **Email** vì user không thể đăng nhập để xem In-app notification khi tài khoản bị khóa.

*   **Người nghe**: `Notification Service` (Gửi Email thông báo)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_UNLOCKED",
  "occurred_at": "2026-04-21T10:30:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "unlock_type": "AUTO" | "ADMIN",
    "unlocked_by": "SYSTEM" | "550e8400-e29b-41d4-a716-446655440001"
  }
}
```

### 1.10 `JWKS_UPDATED` (Cache Invalidation)
Sự kiện đặc biệt dùng để thông báo cho API Gateway xóa cache local của JWKS ngay lập tức khi có sự thay đổi về Key (Rotation hoặc Admin manual trigger).
*   **Kênh truyền tải**: **Redis Pub/Sub** (Channel: `auth:jwks:update`). Không dùng Kafka để đảm bảo tốc độ microsecond và giảm tải cho Kafka cluster với các event mang tính chất signal.
*   **Người nghe**: `API Gateway` (Subscriber)
*   **Payload Schema**:
```json
{
  "event": "JWKS_UPDATED",
  "timestamp": 1714567890
}
```

## 2. Events Auth Service Tiêu Thụ

Lắng nghe trên Topic: `t.admin.events` hoặc tổ chức qua gRPC trực tiếp tùy mức độ khẩn cấp. Rất khuyên dùng Publish-Subscribe qua Kafka để Auth Service dễ Scale, khi Admin Ban user, 30 pods Auth có thể cùng phối hợp.

### 2.1 `ADMIN_FORCE_LOGOUT` / `USER_BANNED`
Khi Backend Dashboard gọi Ban User, sẽ phát Event này đi. Auth Service Consumer cần đọc, làm thao tác: Revoke all của ID này trong PostgreSQL + Append tất cả `jti` của access token còn sống văng vào Redis Denylist + Gửi thông báo cho user qua **Email**.

> **Lưu ý:** Kênh thông báo là **Email** vì user bị Ban không thể đăng nhập để xem In-app notification.

*   **Hành động**:
    1. `UPDATE users SET status = 'BANNED' WHERE id = $1`
    2. `UPDATE refresh_tokens SET revoked_at=NOW() WHERE user_id=$1` (Ghi vội vào DB)
    3. Push Redis `auth:revoked_before:{user_id}` = NOW()
    4. Push Redis Denylist tất cả `jti` đang sống
    5. Gửi Kafka Event `USER_BANNED_NOTIFICATION` cho Notification Service (qua **Email**)
*   **Payload Expected**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_BANNED",
  "occurred_at": "2026-04-21T10:15:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "reason": "VIOLATION"
  }
}
```

### 2.2 `USER_BANNED_NOTIFICATION` (Gửi Email thông báo Ban)
Event này được Auth Service gửi sang Notification Service sau khi đã cập nhật trạng thái BANNED thành công. Mục đích là thông báo qua **Email** cho user biết tài khoản đã bị cấm vĩnh viễn.

*   **Topic**: `t.auth.events`
*   **Người nghe**: `Notification Service` (Gửi Email thông báo)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_BANNED_NOTIFICATION",
  "occurred_at": "2026-04-21T10:15:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "reason": "VIOLATION",
    "banned_by": "550e8400-e29b-41d4-a716-446655440002",
    "banned_at": "2026-04-21T10:15:00Z"
  }
}
```
