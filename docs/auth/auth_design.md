# Auth Service - Architecture Design

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](auth_workflow.md)
*   [Database Schema (Cold Store)](auth_database.md)
*   [Caching Schema (Redis)](auth_caching.md)
*   [Events Structure](auth_events.md)
*   [Background Workers & Jobs](auth_workers.md)

---
## 1. Tổng quan (Overview)
Auth Service là một Microservice nền tảng (Core Service) chịu trách nhiệm về **Xác thực (Authentication)**, **Cấp quyền (Authorization - Token Issuance)** và **Quản lý phiên đăng nhập (Session Management)** cho toàn bộ hệ thống E-commerce.

*(Lưu ý: Auth Service **KHÔNG** quản lý thông tin hồ sơ người dùng (User Profile, Avatar, Địa chỉ, Penalty...). Những nhiệm vụ này thuộc về **User Service**).*

Nhiệm vụ cốt lõi của Auth Service:
1.  **Identity Management**: Quản lý định danh đăng nhập (Email, Phone, Username) liên kết với UUID của người dùng.
2.  **Credential Management**: Quản lý và bảo mật mật khẩu (Bcrypt/Argon2), MFA (TOTP), và SSO (Google, Facebook, Apple).
3.  **Token Management**: Cấp phát, kiểm tra và thu hồi Access Token (JWT) & Refresh Token.
4.  **Session & Device Tracking**: Theo dõi các thiết bị, địa chỉ IP đang đăng nhập, cho phép người dùng "Đăng xuất thiết bị khác".
5.  **Security & Rate Limiting**: Chống Brute-force, chặn IP đăng nhập lạ, gửi cảnh báo (Suspicious Login).

## 2. Kiến trúc tổng thể (Architecture)
Toàn bộ hệ thống giao tiếp với Auth Service theo 2 luồng chính:
*   **External (Từ API Gateway)**: Các API Endpoints để Client đăng ký, đăng nhập, quên mật khẩu, Refresh Token,...
*   **Internal (gRPC với API Gateway / Các Services khác)**: API Gateway thường gọi gRPC tới Auth Service để `ValidateToken` đối với mỗi Request đi qua, nhằm xác định UserID và Roles trước khi trỏ xuống các service nghiệp vụ (Cart, Order, Product).
*   **Internal (Events/Kafka)**: Emit các events (như `USER_REGISTERED`) để **User Service** tự động tạo Profile, hoặc **Notification Service** gửi Welcome Email.

## 3. Các thành phần kỹ thuật chi tiết
Tài liệu của Auth Service được chia thành các phần chuyên biệt để dễ dàng theo dõi và triển khai:

| Component | File | Mô tả |
| :--- | :--- | :--- |
| **Cấu trúc CSDL (PostgreSQL)** | [`auth_database.md`](auth_database.md) | Bảng `identities`, `credentials`, `sso_providers`, `refresh_tokens`, `device_sessions`. |
| **Caching & Rate Limits (Redis)**| [`auth_caching.md`](auth_caching.md) | Quản lý block IP, Brute-force count, Redis Token Blacklist, OTP Cache. |
| **Workflows & APIs** | [`auth_workflow.md`](auth_workflow.md) | Luồng Đăng nhập, Đăng ký, Multi-Factor Auth (MFA), OAuth2 (SSO), Token Validation. |
| **Kafka Events** | [`auth_events.md`](auth_events.md) | Giao tiếp bất đồng bộ: User Created, Password Changed, Suspicious Login, etc. |
| **Background Workers** | [`auth_workers.md`](auth_workers.md) | Các Job dọn dẹp Token hết hạn, Expired Sessions, đồng bộ Audit Log. |
