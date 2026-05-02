# Auth Service - Events & Messaging Schema

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis & Caffeine)](auth_caching_schema.md)
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
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_OTP_REQUEST",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "u-1234",
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
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_OTP_REQUEST",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "u-1234",
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
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_VERIFIED_SUCCESS",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "u-1234",
    "email": "customer@gmail.com",
    "roles": ["USER"]
  }
}
```

### 1.3 `USER_DELETED` (GDPR / Xóa vĩnh viễn)
Được bắn ra khi tài khoản bị xóa vĩnh viễn (yêu cầu GDPR hoặc Admin xóa tài khoản). User Service sẽ dọn sạch PII.
*   **Người nghe**: `User Service`, `Notification Service` (dừng email)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_DELETED",
  "occurred_at": "2026-04-21T10:12:00Z",
  "payload": {
    "user_id": "u-1234",
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
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "TOKEN_THEFT_DETECTED",
  "occurred_at": "2026-04-21T10:10:00Z",
  "payload": {
    "user_id": "u-1234",
    "theft_refresh_token_id": "xyz-uuid-bị_móc_túi",
    "action_taken": "ALL_TOKENS_REVOKED"
  }
}
```

### 1.5 `JWKS_UPDATED` (Cache Invalidation)
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
Khi Backend Dashboard gọi Ban User, sẽ phát Event này đi. Auth Service Consumer cần đọc, làm thao tác: Revoke all của ID này trong PostgreSQL + Append tất cả `jti` của access token còn sống văng vào Redis Denylist.
*   **Hành động**: `UPDATE refresh_tokens SET revoked_at=NOW() WHERE user_id=$1;` (Ghi vội vào DB). Push Redis.
*   **Payload Expected**:
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "u-1234",
  "partition_key": "u-1234",
  "event_type": "USER_BANNED",
  "occurred_at": "2026-04-21T10:15:00Z",
  "payload": {
    "user_id": "u-admin-hay-customer",
    "reason": "VIOLATION"
  }
}
```
