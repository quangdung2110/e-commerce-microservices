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

### 1.1 `USER_VERIFIED_SUCCESS`
Được bắn ra ngay sau khi User xác thực thành công (điền OTP Email hoặc đăng ký qua SSO hoàn tất). Đây là event quan trọng nhất trigger luồng sinh profile rỗng ở User Service.
*   **Người nghe**: `User Service` (Tạo bảng thông tin cá nhân), `Notification Service` (Bắn Mail "Welcome you!").
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "event_type": "USER_VERIFIED_SUCCESS",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "u-1234",
    "email": "customer@gmail.com",
    "roles": ["USER"]
  }
}
```

### 1.2 `USER_REGISTERED`
Được bắn ra ngay sau khi bản ghi Credential mới được Commit vào Transaction DB thông qua Outbox.
*   **Người nghe**: `Promotion Service` (Tặng phiếu quà tặng người mới).
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "event_type": "USER_REGISTERED",
  "occurred_at": "2026-04-21T10:01:00Z",
  "payload": {
    "user_id": "u-1234",
    "email": "customer@gmail.com",
    "roles": ["USER"],
    "registered_ip": "14.234.xx.yy"
  }
}
```

### 1.2.b `USER_DELETED` (GDPR / Xóa vĩnh viễn)
Được bắn ra khi tài khoản bị xóa vĩnh viễn (yêu cầu GDPR hoặc Admin xóa tài khoản). User Service sẽ dọn sạch PII.
*   **Người nghe**: `User Service`, `Notification Service` (dừng email), `Audit Service`.
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "event_type": "USER_DELETED",
  "occurred_at": "2026-04-21T10:12:00Z",
  "payload": {
    "user_id": "u-1234",
    "reason": "GDPR_REQUEST"
  }
}
```

### 1.3 `USER_LOGGED_IN` (Phục vụ Security Audit)
Được bắn ra ở Background khi cấp token thành công để cảnh báo thiết bị lạ.
*   **Người nghe**: `Audit Service` (Lưu lịch sử Login)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "event_type": "USER_LOGGED_IN",
  "occurred_at": "2026-04-21T10:05:00Z",
  "payload": {
    "user_id": "u-1234",
    "device_info": "Mozilla/5.0 (iPhone; CPU iPhone OS 14_6)",
    "device_id": "fingerprint-uuid-x",
    "ip_address": "14.234.xx.yy",
    "session_time": "..."
  }
}
```

### 1.3.b Các Event Bảo Mật Bổ Sung (Gửi cho Audit)
Những sự kiện này hỗ trợ Audit Service phân loại `security_level`:
*   `LOGIN_FAILED_BRUTE_FORCE`: Bắn ra khi phát hiện ai đó gọi `/login` sai quá 5 lần.
*   `USER_LOCKED`: Tài khoản bị tạm khóa.
*   `PASSWORD_RESET_FAILED`: Nhập OTP sai quá số lần ở luồng Quên mật khẩu.
*   `ADMIN_IMPERSONATED_USER`: Admin vừa tiến hành luồng mạo danh Customer.
(Các event này đều có chuẩn format chung chứa `event_id`, `occurred_at`, `event_type` và `payload.user_id`).

### 1.4 `TOKEN_THEFT_DETECTED` (Xử Lý Trừng Phạt Đánh Cắp Hàng Loạt)
Sự kiện bắn ra từ Luồng Grace Period (Xem Workflow) vào thời khắc phát hiện một cái Refresh Token HẾT HẠN ÂN HẠN 10s lại bị ai đó nộp để ăn cắp. Auth Service đã tự Ban sạch các DB Token của user đó, nhưng nó bắn thông báo này để cả hệ thống chung tay.
*   **Người nghe**: `API Gateway` (Bổ sung Denylist cho tất cả request nào khác đến)
*   **Payload Schema**:
```json
{
  "event_id": "uuid-v4",
  "event_type": "TOKEN_THEFT_DETECTED",
  "occurred_at": "2026-04-21T10:10:00Z",
  "payload": {
    "user_id": "u-1234",
    "theft_refresh_token_id": "xyz-uuid-bị_móc_túi",
    "action_taken": "ALL_TOKENS_REVOKED"
  }
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
  "event_type": "USER_BANNED",
  "occurred_at": "2026-04-21T10:15:00Z",
  "payload": {
    "user_id": "u-admin-hay-customer",
    "reason": "VIOLATION"
  }
}
```
