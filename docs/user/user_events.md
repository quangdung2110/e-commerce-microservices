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

**1.1. `USER_VERIFIED_SUCCESS` (Hoặc `USER_REGISTERED_SUCCESS`)**
Kích hoạt tự động tạo hồ sơ profile ngay sau khi User ấn "Đăng ký" qua Captcha hợp lệ hoặc "Verify qua OTP Email" thành công (Tùy chính sách B2C của sàn Ecomm).
```json
// Topic: t.auth.events
{
  "event_id": "uuid-v4",
  "event_type": "USER_VERIFIED_SUCCESS",

**1.2. `USER_DELETED` (GDPR / Xóa vĩnh viễn)**
  "timestamp": "2026-04-22T08:00:00Z",
  "payload": {
// Topic: t.auth.events
{
  "event_id": "uuid-v4",
  "event_type": "USER_DELETED",
  "timestamp": "2026-04-22T08:30:00Z",
  "payload": {
    "user_id": "uuid-cua-user",
    "reason": "GDPR_REQUEST"
  }
}
```
    "user_id": "uuid-cua-user",  // Đồng bộ hoàn toàn với Auth schema user_id (mapped vào user_profiles.id)
    "email": "customer@gmail.com",
    "roles": ["USER"]
  }
}
```

### Từ Topic `t.loyalty.events` hoặc `t.order.events` (Sản xuất bởi hệ thống bán hàng)


## 2. Events Published (Công bố cho thế giới biết)

Hệ thống User đẩy luồng sự kiện thông qua CDC (Debezium Outbox pattern) vô Topic của chính nó: **`t.user.events`**.

**2.1. `USER_PROFILE_UPDATED`**
Công khai dữ liệu bị sửa đổi (Tên, Số điện thoại) để các dịch vụ khác như `Notification`, `Audit` hoặc bảng Data Lake biết mà đồng bộ. (Do dùng Giao thức gRPC để kéo profile cho Chat, nên không cần Notification Service hay Chat Service phải lặp lại kho dữ liệu User).
```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "event_sequence_id": 1493021, // Versioning tranh Out-of-order 
  "event_type": "USER_PROFILE_UPDATED",
  "timestamp": "2026-04-22T08:05:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "first_name": "Nguyen",
    "last_name": "Quang Dung",
    "phone_number": "0987654321", // Audit Service doc se ma hoa mask data
    "date_of_birth": "1997-10-19",
    "gender": "MALE",
    "avatar_url": "https://cdn.commerce.vn/avt/xyz.webp"
  }
}
```

**2.2. `USER_PREFERENCES_UPDATED`**
Thông báo khi người dùng thay đổi preferences để Notification/Marketing đồng bộ.
```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "event_sequence_id": 1493022,
  "event_type": "USER_PREFERENCES_UPDATED",
  "timestamp": "2026-04-22T08:06:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "language": "vi",
    "currency": "VND",
    "receive_promotions": true,
    "receive_order_updates": true
  }
}
```

**2.3. Sự Kiện `USER_ADDRESS_CREATED`, `USER_ADDRESS_UPDATED`, `USER_ADDRESS_DELETED`**
Ném lên không gian phòng khi hệ thống Shipping Logistcs/Fraud Detection cần Audit sự bất thường (vd: Đổi địa chỉ nhận hàng 10 lần liên tục trước flash sale để lách mã FreeShip).
```json
// Topic: t.user.events
{
  "event_id": "uuid-v4",
  "event_sequence_id": 1493022, 
  "event_type": "USER_ADDRESS_DELETED",
  "timestamp": "2026-04-22T08:10:00Z",
  "payload": {
    "id": "uuid-cua-user",
    "address_id": "uuid-address",
    "is_default_previous": true
  }
}
```

---
> [!NOTE] 
> User Service không publish password hay bất kỳ credential mật mã nào (Vì nó vốn không sở hữu các field đó). Toàn bộ Auth Logic bị chắn tách biệt ở `t.auth.events` và Audit Tracking. Mảng dữ liệu User Events được xem là không quá nhạy cảm nếu so với Auth Token Leakage.
