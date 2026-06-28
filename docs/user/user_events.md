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
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_VERIFIED_SUCCESS",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "roles": ["USER"]
  }
}
```

**1.2. `USER_DELETED` (GDPR / Xóa vĩnh viễn)**
Event được consume từ Auth Service (`t.auth.events`). Sau khi xử lý cleanup PII, User Service sẽ republish event này lên `t.user.events` với tên `ACCOUNT_DELETED` để thông báo cho các service downstream (Review, Notification, etc.).

*   **Giá trị `reason` hợp lệ:**
    | Giá trị | Mô tả |
    |:--------|:------|
    | `GDPR_REQUEST` | User tự yêu cầu xóa tài khoản (quyền GDPR) |
    | `ADMIN_DELETION` | Admin xóa tài khoản người dùng |

*   **Payload Schema (từ Auth Service):**
```json
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_DELETED",
  "occurred_at": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted_at": "2026-04-21T10:00:00Z",
    "reason": "GDPR_REQUEST" | "ADMIN_DELETION",
    "retention_window_expires_at": "2026-07-21T10:00:00Z"
  }
}
```

*   **Luồng xử lý 2 giai đoạn (GDPR Compliance):**

> **Tại sao cần 2 giai đoạn?**
> Kafka/Message Queue không phải permanent storage (mặc định retention 7 ngày). Không thể dùng Kafka event để trigger hard delete sau 90 ngày. Giải pháp: lưu vào bảng `user_deletions` trong Database làm Event Store.

| Giai đoạn | Thời điểm | Hành động | Chi tiết |
|:----------|:-----------|:-----------|:---------|
| **Giai đoạn 1: Anonymize** | Ngay khi nhận event | Anonymize PII | Thay PII bằng placeholder để user không còn hiển thị trên hệ thống. Bản ghi vẫn tồn tại để đảm bảo referential integrity với các bảng khác. |
| **Giai đoạn 2: Hard Delete** | Sau `retention_window_expires_at` (90 ngày) | Hard Delete | Compliance Worker đọc từ bảng `user_deletions`, xóa vĩnh viễn dữ liệu còn lại. |

*   **Hành động khi nhận:**
    1. INSERT vào bảng `user_deletions`
    2. UPDATE `user_profiles`: anonymize PII (tên → "Deleted User", SĐT → null, etc.)
    3. DELETE `user_addresses` (hard delete vì Order Service đã snapshot)
    4. DELETE `user_preferences`
    5. COMMIT transaction
    6. Publish `ACCOUNT_DELETED` trực tiếp lên `t.user.events` qua Kafka producer

> **Lưu ý:** Email KHÔNG cần xóa ở đây vì User Service không lưu email (Auth là source of truth).

## 2. Events Published (Công bố cho thế giới biết)

Hệ thống User đẩy luồng sự kiện thông qua direct Kafka publishing (UserEventProducer) vào Topic của chính nó: **`t.user.events`**.

### 2.1. Bản đồ Subscription (Ai nghe event này?)

| Event | Consumers | Mục đích |
|:------|:---------|:---------|
| `USER_PROFILE_UPDATED` | Chat Service | Cập nhật projection `sender_name` trong messages |
| `USER_PREFERENCES_UPDATED` | Notification Service | Sync preferences để quyết định kênh gửi |
| `ACCOUNT_DELETED` | Review Service, Notification Service | Anonymize/Hide user-generated content khi tài khoản bị xóa |

**2.2. `USER_PROFILE_UPDATED`**
Sự kiện này cho phép các dịch vụ đồng bộ và lưu trữ cục bộ các thông tin cơ bản của người dùng (Tên, SĐT). Đặc biệt, Chat Service sẽ sử dụng sự kiện này để duy trì bản sao thông tin (Projection) nhằm hiển thị tên người gửi mà không cần gọi gRPC liên tục, giúp tối ưu hiệu năng và tính sẵn sàng.
```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_PROFILE_UPDATED",
  "timestamp": "2026-04-22T08:05:00Z",
  "payload": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
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
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "USER_PREFERENCES_UPDATED",
  "timestamp": "2026-04-22T08:06:00Z",
  "payload": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "language": "vi",
    "currency": "VND",
    "email_enabled": true,
    "in_app_enabled": true,
    "marketing_enabled": true
  }
}
```

**2.4. `ACCOUNT_DELETED`**
Được republish từ Auth Service sau khi User Service xử lý xóa PII. Event này thông báo cho các service downstream (Review, Notification, etc.) để anonymize/hide user-generated content.

> **Nguồn gốc event:** Auth Service phát `USER_DELETED` → User Service consume và republish lại với tên `ACCOUNT_DELETED`.

*   **Giá trị `reason` hợp lệ:**
    | Giá trị | Mô tả |
    |:--------|:------|
    | `GDPR_REQUEST` | User tự yêu cầu xóa tài khoản (quyền GDPR) |
    | `ADMIN_DELETION` | Admin xóa tài khoản người dùng |

```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "aggregate_type": "USER",
  "aggregate_id": "550e8400-e29b-41d4-a716-446655440000",
  "partition_key": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "ACCOUNT_DELETED",
  "timestamp": "2026-04-21T10:00:00Z",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted_at": "2026-04-21T10:00:00Z",
    "reason": "GDPR_REQUEST" | "ADMIN_DELETION",
    "retention_window_expires_at": "2026-07-21T10:00:00Z"
  }
}
```

*   **Hành động của downstream:**
    - `retention_window_expires_at` được giữ cho compliance pipeline.
    - Luồng anonymize/hide chạy ngay khi nhận event.

---
> [!NOTE]
> User Service không publish password hay bất kỳ credential mật mã nào (Vì nó vốn không sở hữu các field đó). Toàn bộ Auth Logic bị chắn tách biệt ở `t.auth.events`. Mảng dữ liệu User Events được xem là không quá nhạy cảm nếu so với Auth Token Leakage.
