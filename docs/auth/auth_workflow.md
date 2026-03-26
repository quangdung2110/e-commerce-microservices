# Auth Service - Workflows & APIs (REST & gRPC)

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Caching Schema](auth_caching.md)
*   [Events Structure](auth_events.md)

---
Tài liệu này trình bày các luồng API chính của Auth Service để giải quyết nghiệp vụ xác thực, bao gồm cả các Endpoint được gọi từ Client (Web/Mobile) và Endpoint gRPC nội bộ được gọi bởi API Gateway.

## 1. REST APIs Workflows (External)

### 1.1 Luồng Đăng ký (Native Registration Flow)
Thực hiện yêu cầu xác minh Số Điện Thoại / Email thông qua OTP trước khi vào hệ thống.

*   **`POST /api/v1/auth/register`**
    *   **Payload**: `{ "identity_type": "EMAIL", "identity_value": "user@gmail.com", "password": "..." }`
    *   **Xử lý**: Kiểm tra Email/Phone trùng lặp -> Hash Password (Argon2id) -> Khởi tạo mã OTP 6 số -> Lưu OTP vào Redis (5 mins TTL) -> Bypass qua Notification Service (hoặc bên thứ 3) để gửi SMS/Email.
    *   **Rate limit áp dụng**: Max 3 req / 15 mins (Cache Layer).
*   **`POST /api/v1/auth/register/verify`**
    *   **Payload**: `{ "identity_value": "user@gmail.com", "otp": "123456" }`
    *   **Xử lý**: Query Redis lấy OTP -> So sánh -> Xóa OTP Cache -> Ghi Data vào CSDL `auth_identities` (Set Verified = True) và `auth_credentials` -> Bắn Kafka Event `USER_REGISTERED` -> Khởi tạo AccessToken & Refresh Token -> Trả về Client.

### 1.2 Luồng Đăng Nhập Mật Khẩu (Password Login Flow)
Luồng này thực hiện kiểm tra bảo mật nhiều bước (Anti-Brute Force, Device Tracking) trước khi cấp Token.

*   **`POST /api/v1/auth/login`**
    *   **Payload**: `{ "identity_value": "...", "password": "...", "device_fingerprint": "...", "device_name": "..." }`
    *   **Bước 1**: Kiểm tra khóa (Lockout) ở Caching. Nếu đang bị Rate-Limit Lock khóa do sai Pass -> Trả 429.
    *   **Bước 2**: Fetch `auth_credentials` qua `identity_value`.
    *   **Bước 3**: Verify bằng BCrypt/Argon2.
        *   -> Nếu **SAI**: Tăng biến đếm Failed Attempts trên Redis. Nếu >= 5, set Redis Lock (Block 30m).
        *   -> Nếu **ĐÚNG**: Xoá counter sai.
    *   **Bước 4**: Kiểm tra cột `mfa_enabled` trong Database.
        *   -> Nếu MFA = `TRUE`: Cấp một `mfa_token` tạm thời (TTL 5 mins). Frontend cầm token này nhảy qua màn nhập OTP ứng dụng Authenticator.
    *   **Bước 5**: Gọi [Async Risk Worker](auth_workers.md) truyền Device/IP xuống để xác định Suspicious Login.
    *   **Bước 6 (Bật Session)**: Sinh UUID cho JTI (Refresh Token), lưu vào Database `auth_refresh_tokens`, lưu session vào `auth_device_sessions`.
    *   **Bước 7**: Sinh Access Token (với JTI tương ứng), trả cấp về cho Client (Cùng với Refresh Token lưu trong HTTP Only Cookie).

### 1.3 Luồng Đăng nhập OAuth2 / SSO (Google, Apple, Facebook)
Tối ưu hóa Onboarding (Giảm Friction đăng ký).

*   **`POST /api/v1/auth/oauth/{provider}/callback`** (Chỉ API Server Auth xử lý, không để Frontend xử lý Access Token tránh thay đổi Token giả).
    *   **Payload**: `code` (Authorization Code trả về từ Google/Apple OAuth window).
    *   **Xử lý**: 
        1. Gọi ngược lên Server Google xác minh lấy thông tin (`provider_uid`, `email`).
        2. Query `auth_sso_providers`.
        3. Nếu CHƯA CÓ: Tự động đăng ký (Auto-Register), insert vào `auth_identities`, set Random Password, insert `auth_sso_providers`, bắn Event `USER_REGISTERED`.
        4. Nếu ĐÃ CÓ (Hoặc email đã có native account): Merge account, tiếp tục quy trình Sinh Token.

### 1.4 Làm mới phiên (Refresh Token Rotation)
Access Token là ngắn hạn (VD: 15 Phút), Refresh Token là dài hạn (Ví dụ 7 Ngày).

*   **`POST /api/v1/auth/refresh`**
    *   **Input**: Refresh Token (Từ Header Cookie HTTPOnly).
    *   **Xử lý**:
        1. Decode Hash xác nhân RT đúng cấu trúc và chưa hết hạn.
        2. Query `auth_refresh_tokens`.
        3. Nếu `is_revoked == TRUE` hoặc `replaced_by_token != NULL`: Có khả năng RT đã bị hack và tái sử dụng (Replay Attack) -> Lập tức **REVOKE toàn bộ Family of Tokens** của user đó cảnh báo. (Xóa sạch Session, force bắt đăng nhập lại).
        4. Nếu Hợp lệ: Đẩy RT cũ thành `replaced_by_token`, sinh RT mới (xoay vòng RT).
        5. Sinh Access Token mới trả về cho Client.

### 1.5 Đăng Xuất (Logout) & Đăng Xuất Thiết Bị (Remote Revoke)
*   **`POST /api/v1/auth/logout`**
    *   **Header**: `Bearer {access_token}`
    *   **Action**: Thêm JTI của `access_token` vào Redis `[Token Blacklist]` với TTL là thời gian sống còn lại. Mark `auth_device_sessions.is_active = FALSE`. Đổi `auth_refresh_tokens.is_revoked = TRUE`.
*   **`DELETE /api/v1/auth/devices/{device_id}`** (Force Logout Thiết bị từ xa)
    *   Người dùng ngồi ở Mobile thấy có PC đăng nhập Account mình, bấm lệnh Remove PC.
    *   **Action**: Tìm Refresh Token của Device kia -> Revoke in DB -> Fetch tất cả Access Token Issue từ Refresh Token kia trong Log (nếu có lưu Mapping) hoặc đơn giản là thu hồi phiên.

---

## 2. API Nội Bộ (Internal gRPC Services)

Các dịch vụ dùng `gRPC` chuẩn để giao tiếp nội bộ trong vòng mạng Private Network.

### `AuthService.ValidateToken` (Bảo vệ API Gateway)
Hàm Validation siêu nhẹ, gọi hàng nghìn lần mỗi giây bất cứ khi nào có Request đi ngang Gateway (Kong/KrakenD/Envoy).

*   **Method**: `rpc ValidateToken(ValidateRequest) returns (ValidateResponse)`
*   **Request**: `access_token_string` (nguyên bản JWT).
*   **Logic**:
    1.  Cắt cấu trúc Header.Payload.Signature.
    2.  Kiểm tra Public Key / HMAC SHA256 Signature (Ngay tại RAM hoặc Local Cache, không I/O DB).
    3.  Kiểm tra Claim `exp` (Expire Time) xem còn sống không?
    4.  Extract Claim `jti`. Lệnh Redis: `EXISTS auth:blacklist:{jti}`.
    5.  -> NẾU CÓ TRONG BLACKLIST: Token bị thu hồi chủ động -> Check Fails (Return 401).
    6.  -> NẾU KHÔNG CÓ: Decode `user_id`, `roles`, push ngược ra cho Gateway. Gateway sẽ map dữ liệu này vào `X-User-Id` header đẩy xuống Carts, Orders,...
*   **Performance Goal**: Dưới `2ms` độ trễ P99 cho tác vụ này. Bắt buộc có Redis Node tốc độ cao tại cùng Host/VPC.
