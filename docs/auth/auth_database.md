# Auth Service - Database Schema Design

**Liên kết nhanh:**
*   [Kiến trúc tổng thể](auth_design.md)
*   [Workflows (API)](auth_workflow.md)
*   [Caching Schema](auth_caching.md)

---
Auth Service đóng vai trò là "Cửa ngõ định danh". Do đó, CSDL (PostgreSQL) phải tách biệt hoàn toàn hồ sơ người dùng (User Profile, Address) và chỉ giữ lại những thông tin phục vụ cho quá trình **Xác thực (Authentication)** và **Phiên đăng nhập (Session)**.

## 1. Bảng `auth_identities` (Định danh)
Lưu trữ các cách thức mà người dùng có thể dùng để đăng nhập hệ thống (Email, Số điện thoại, Username).

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX. Đây là Global User ID xuyên suốt hệ thống (Sẽ nằm trên bảng `users` của **User Service**).
*   `identity_type`: VARCHAR(20) NOT NULL. Enum: `EMAIL`, `PHONE`, `USERNAME`.
*   `identity_value`: VARCHAR(255) NOT NULL, UNIQUE. (VD: `nguyenvana@gmail.com` hoặc `+84981234567`).
*   `is_verified`: BOOLEAN DEFAULT FALSE (Email/SĐT đã confirm qua OTP chưa).
*   `status`: VARCHAR(20) DEFAULT 'ACTIVE'. Enum: `ACTIVE`, `LOCKED` (Bị khóa đăng nhập tạm thời do sai gán nhiều báo cáo).
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

## 2. Bảng `auth_credentials` (Chứng chỉ)
Chứa mật khẩu và thông tin xác thực 2 lớp (MFA). Tách bảng để bảo mật dữ liệu, giới hạn quyền select của các team data.

*   `user_id`: UUID PRIMARY KEY (1-1 với Global User ID).
*   `password_hash`: VARCHAR(255). Lưu hash của mật khẩu (sử dụng thuật toán **Argon2id** hoặc **Bcrypt**). Nullable nếu User chỉ đăng nhập SSO.
*   `mfa_enabled`: BOOLEAN DEFAULT FALSE.
*   `mfa_secret`: VARCHAR(100). Khóa bí mật dùng cho Google Authenticator (TOTP).
*   `mfa_backup_codes`: JSONB. Mảng các mã dự phòng (plaintext đã hash hoặc raw) khi mất thiết bị.
*   `password_changed_at`: TIMESTAMPTZ. Dùng để invalidate tất cả sessions cũ nếu user đổi mật khẩu.
*   `failed_login_attempts`: INT DEFAULT 0. Đếm số lần sai pass liên tiếp (kết hợp với Redis để khoá).
*   `lockout_until`: TIMESTAMPTZ. Thời điểm mở khóa nếu bị brute-force.
*   `updated_at`: TIMESTAMPTZ

## 3. Bảng `auth_sso_providers` (OAuth2/SSO)
Dùng cho tính năng Đăng nhập bằng Google, Facebook, Apple.

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX
*   `provider`: VARCHAR(20) NOT NULL. Enum: `GOOGLE`, `FACEBOOK`, `APPLE`.
*   `provider_uid`: VARCHAR(255) NOT NULL. ID định danh trả về từ provider. (VD: `10111222333`).
*   `provider_email`: VARCHAR(255). Email từ provider (có thể khác với identity_value).
*   `access_token`: TEXT (Tùy chọn lưu token của Google nếu cần gọi API hộ User, thường bỏ qua).
*   `created_at`: TIMESTAMPTZ
*   > Ràng buộc: `UNIQUE(provider, provider_uid)`

## 4. Bảng `auth_refresh_tokens`
Quản lý trạng thái của Refresh Token (Family of Tokens). Để thực hiện xoay vòng token (Token Rotation) chống lộ (Token Hijacking).

*   `id`: UUID PRIMARY KEY (Bản thân ID này là Refresh Token ID (JTI), chuỗi mã hóa trả về cho client sẽ wrap ID này).
*   `user_id`: UUID NOT NULL, INDEX
*   `client_id`: VARCHAR(100). (Web, Mobile App iOS, Mobile App Android).
*   `token_hash`: VARCHAR(255) UNIQUE. Hash SHA256 của Refresh Token nguyên bản. Chống đánh cắp database dùng để gen token.
*   `expires_at`: TIMESTAMPTZ. Thời điểm bắt buộc hết hạn hoàn toàn.
*   `is_revoked`: BOOLEAN DEFAULT FALSE. Bị thu hồi thủ công (Đăng xuất hoặc bị quản trị khóa).
*   `replaced_by_token`: UUID. Khóa trỏ tới Refresh Token mới khi thực hiện xoay vòng. Giúp phát hiện nếu token cũ bị sử dụng lại (Báo hiệu hacker).
*   `created_at`: TIMESTAMPTZ

## 5. Bảng `auth_device_sessions` (Quản lý thiết bị đăng nhập)
Cho phép user xem "Các thiết bị đã đăng nhập" và "Đăng xuất thiết bị khác".

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX
*   `refresh_token_id`: UUID (FK -> `auth_refresh_tokens.id`). Khi token này chết thì session cũng tính như chết.
*   `device_id`: VARCHAR(255). Fingerprint của máy hoặc UUID sinh phía Client.
*   `device_name`: VARCHAR(255). (Ví dụ: `iPhone 14 Pro Max`, `Chrome on Windows`).
*   `ip_address`: VARCHAR(45). Lấy từ Header `X-Forwarded-For`.
*   `last_active_at`: TIMESTAMPTZ.
*   `is_active`: BOOLEAN DEFAULT TRUE.
*   `created_at`: TIMESTAMPTZ

## 6. Lời bình thiết kế
*   **Tại sao không lưu Profile ở đây?** Các dịch vụ khác (Order, Bill) chỉ quan tâm User tên gì, địa chỉ ở đâu. Auth chỉ giải quyết câu hỏi: "Anh là ai, cung cấp bằng chứng (Password/OTP), và cho anh cái giấy thông hành (JWT)". Việc quản lý Profile hoàn toàn được Delegate (ủy quyền) sang hệ thống khác, đảm bảo Auth có khả năng Scale độc lập với High Throughput (vì mỗi API Request Gateway đều gọi Validate Token vào Auth).
*   **Tại sao Refresh Token lại lưu Token Hash?** Nếu hacker dump được DB Auth Service, họ vẫn không thể cầm chuỗi Hash đó ném lên header để giả mạo việc Refresh. Hashing RT tương đương việc Hashing Password.
