# Auth Service - Events Structure & Messaging (Kafka)

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Workflows (API)](auth_workflow.md)

---
Auth Service thiết kế theo kiến trúc Event-Driven Architecture (EDA), giao tiếp bất đồng bộ qua **Kafka**. Service hoạt động theo [Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html) để đảm bảo Event được bắn đi không bao giờ mất khi Transaction Database thành công hứa hẹn.

## 1. Events Produced (Phát hành)
Auth Service là Source of Truth về quá trình định danh, do đó nó chịu trách nhiệm phát ra những trạng thái quan trọng.

### 1.1 `USER_REGISTERED`
Được phát ra khi người dùng tạo tài khoản và hoàn tất xác thực (Ví dụ: Nhập đúng OTP).
*   **Topic**: `t.auth.events`
*   **Routing Key**: `user.registered`
*   **Payload**:
    ```json
    {
      "event_id": "uuid",
      "timestamp": 1709990000123,
      "event_type": "USER_REGISTERED",
      "data": {
        "user_id": "uuid-here",
        "identity_type": "EMAIL",
        "identity_value": "user@gmail.com",
        "sso_provider": null
      }
    }
    ```
*   **Consumers**:
    *   `User Service`: Lắng nghe để tạo bản ghi thông tin Profile (Tên rỗng, Avatar mặc định).
    *   `Notification Service`: Lắng nghe để gửi Email "Welcome to our Platform".
    *   `Promotion Service`: Lắng nghe để tự động attach "Voucher cho người dùng mới".

### 1.2 `USER_LOGGED_IN` (Tuỳ chọn cho Data/Security)
*   **Topic**: `t.auth.events`
*   **Routing Key**: `user.logged_in`
*   **Payload**: `{"user_id": "uuid", "device_id": "xyz", "ip": "1.2.3.4", "time": 170...}`
*   **Consumers**:
    *   `Data Analytics Service`: Push lên Data Warehouse để vẽ Chart DAU (Daily Active Users).
    *   `Fraud Detection (Rule Engine)`: Phân tích Risk Model dựa trên Location & Thời gian.

### 1.3 `SUSPICIOUS_LOGIN_DETECTED` 
Khi Engine phát hiện đăng nhập từ IP ngoại quốc hoặc thiết bị chưa từng thấy.
*   **Topic**: `t.auth.events`
*   **Routing Key**: `auth.suspicious_login`
*   **Consumers**: `Notification Service` (Gửi email khẩn cấp chứa thiết bị, hệ điều hành, trình duyệt, location).

### 1.4 `PASSWORD_CHANGED` / `MFA_ENABLED`
Khi bảo mật Auth Token thay đổi, thông báo cấp thiết cần gửi cho người dùng.
*   **Topic**: `t.auth.events`
*   **Routing Key**: `auth.security_status_changed`
*   **Consumers**: 
    *   `Notification Service`: Gửi cảnh báo "Mật khẩu vừa bị thay đổi. Nếu không phải bạn..."

---

## 2. Events Consumed (Lắng nghe)
Auth lắng nghe lệnh từ các hệ thống Admin hoặc từ User Profile để xử lý Access.

### 2.1 `ACCOUNT_BANNED` / `ACCOUNT_DELETED`
*   **Nguồn (Source)**: `User Service` hoặc `Admin Service`.
*   **Topic**: `t.user.events`
*   **Hành động của Auth Service**:
    *   Xoá cứng (Hard delete) cấu hình `auth_refresh_tokens` và `auth_device_sessions`.
    *   Push ID của User/Token xuống Redis `[Token Blacklist]` với TTL dài hạn để chặn ngay tắp lự khỏi Gateway.

### 2.2 `FORCE_LOGOUT_DEVICE`
*   **Nguồn**: `User Service` (Từ trang Profile quản lý thiết bị).
*   **Topic**: `t.user.commands`
*   **Hành động**: Revoke Refresh Token được chỉ định và thêm vào Blacklist.

