# Auth Service - Workflows & APIs (REST & gRPC)

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Caching Schema](auth_caching.md)
*   [Events Structure](auth_events.md)

---
Tài liệu mô tả đầy đủ luồng API Auth Service theo phạm vi hiện tại: đăng ký native, đăng nhập OTP email/số điện thoại, quên mật khẩu 2 bước, refresh token rotation có grace period, logout và validate token nội bộ.

## 1. REST APIs Workflows (External)

### 1.1 Luồng Đăng ký Native (EMAIL/PHONE + OTP)

#### `POST /api/v1/auth/register`
*   **Payload**:
    ```json
    {
      "identity_type": "EMAIL",
      "identity_value": "user@gmail.com",
      "password": "raw-password"
    }
    ```
*   **Xử lý**:
    1. Normalize `identity_value` (email lowercase, phone về E.164).
    2. Kiểm tra trùng identity trong `auth_identities`.
    3. Hash password bằng Argon2id.
    4. Tạo OTP 6 số, hash OTP trước khi lưu Redis (TTL 5 phút).
    5. Gửi OTP qua Notification Service đúng kênh EMAIL/PHONE.
*   **Rate limit**: 3 lần/15 phút và 5 lần/ngày theo identity.

#### `POST /api/v1/auth/register/verify`
*   **Payload**:
    ```json
    {
      "identity_type": "EMAIL",
      "identity_value": "user@gmail.com",
      "otp": "123456"
    }
    ```
*   **Xử lý**:
    1. Verify OTP hash trong Redis.
    2. Nếu đúng: xóa OTP key, insert `auth_identities` (`is_verified=true`) và `auth_credentials`.
    3. Tạo cặp access + refresh token đầu tiên.
    4. Emit event `USER_REGISTERED`.

### 1.2 Luồng Đăng nhập OTP (EMAIL/PHONE, không Social Login)

### 1.2.1 `POST /api/v1/auth/login/otp/request`
*   **Payload**:
    ```json
    {
      "identity_type": "PHONE",
      "identity_value": "+84981234567",
      "device_fingerprint": "fp_abc123",
      "device_name": "Chrome on Windows"
    }
    ```
*   **Xử lý**:
    1. Normalize identity và tìm user.
    2. Kiểm tra identity tồn tại, `is_verified=true`, `status=ACTIVE`, và không có key `auth:lockout:identity:{identity_normalized}`.
    3. Kiểm tra rate-limit theo thứ tự: identity -> IP -> device fingerprint. Chỉ cần một lớp vượt ngưỡng là từ chối.
    4. Tạo OTP login 6 số, hash trước khi lưu Redis (TTL 5 phút).
    5. Lưu `otp_login_session_id` trong Redis để ràng buộc anti-replay.
    6. Gửi OTP qua kênh tương ứng.
    7. Emit event `OTP_LOGIN_REQUESTED`.

### 1.2.2 `POST /api/v1/auth/login/otp/verify`
*   **Payload**:
    ```json
    {
      "identity_type": "PHONE",
      "identity_value": "+84981234567",
      "otp": "123456",
      "otp_login_session_id": "uuid",
      "device_fingerprint": "fp_abc123",
      "device_name": "Chrome on Windows"
    }
    ```
*   **Xử lý**:
    1. Verify session id khớp với identity.
    2. Verify OTP hash + TTL + số lần nhập sai.
    3. Nếu sai vượt ngưỡng -> lock tạm thời identity.
    4. Nếu đúng -> xóa OTP/session key.
    5. Tạo refresh token + access token, lưu `auth_refresh_tokens` và `auth_device_sessions`.
    6. Gọi async risk analyzer để kiểm tra đăng nhập bất thường.
    7. Emit `OTP_LOGIN_VERIFIED`, `USER_LOGGED_IN`.

### 1.3 Luồng Quên Mật Khẩu (2 bước OTP + TOTP)

### 1.3.1 `POST /api/v1/auth/forgot-password/request`
*   **Payload**:
    ```json
    {
      "identity_type": "EMAIL",
      "identity_value": "user@gmail.com"
    }
    ```
*   **Xử lý**:
    1. Normalize identity.
    2. Luôn trả response trung tính (không lộ account tồn tại hay không).
    3. Nếu user tồn tại/active: tạo `reset_session_id`, sinh OTP reset, lưu Redis (TTL 5 phút).
    4. Gửi OTP về đúng kênh identity.
    5. Emit `PASSWORD_RESET_REQUESTED`.
*   **Response**:
    *   Luôn trả response trung tính để tránh lộ user tồn tại.
    *   Luôn trả `reset_session_id` dạng opaque (với account không tồn tại thì trả placeholder session không thể verify thành công).

### 1.3.2 `POST /api/v1/auth/forgot-password/verify-otp`
*   **Payload**:
    ```json
    {
      "reset_session_id": "uuid",
      "otp": "123456"
    }
    ```
*   **Xử lý**:
    1. Verify OTP reset.
    2. Đánh dấu `otp_verified_at` cho reset session.
    3. Nếu user bật MFA (`mfa_enabled=true`): set state `MFA_PENDING`, tạo `mfa_challenge_token` TTL 5 phút.
    4. Nếu user chưa bật MFA: set `fallback_used=true` ngay tại reset session rồi chạy fallback đồng bộ (mục 1.3.4) ngay trong request hiện tại.
    5. Event sequencing:
        * luôn emit `PASSWORD_RESET_OTP_VERIFIED` sau OTP thành công;
        * nếu vào fallback path thì emit thêm `PASSWORD_RESET_FALLBACK_STARTED` sau khi risk check hoàn tất.

### 1.3.3 `POST /api/v1/auth/forgot-password/verify-totp`
*   **Payload**:
    ```json
    {
      "reset_session_id": "uuid",
      "mfa_challenge_token": "opaque-token",
      "totp_code": "123456"
    }
    ```
*   **Xử lý**:
    1. Validate challenge token và reset session state.
    2. Verify TOTP 6 số với cửa sổ lệch thời gian +-1 bước.
    3. Giới hạn số lần nhập sai TOTP (max 10/15 phút).
    4. Nếu đúng: đánh dấu `mfa_verified_at`, phát hành `reset_token` một lần (TTL 10-15 phút).
    5. Emit `PASSWORD_RESET_MFA_VERIFIED`.

### 1.3.4 Fallback khi user chưa bật MFA
Nếu `mfa_enabled=false`, không chặn cứng người dùng mà dùng fallback bảo mật:
1. OTP bắt buộc đã đúng.
2. Thực hiện risk check **đồng bộ tại endpoint verify-otp** theo IP/device/location.
3. Nếu risk cao: chuyển reset session sang `LOCKED`, không phát hành token, yêu cầu manual support verification.
4. Nếu risk chấp nhận được: phát hành `reset_token` một lần (TTL 10 phút) và gửi email link chứa `reset_token`.
5. Response của `verify-otp` trả trạng thái fallback-ready để client gọi endpoint complete với `reset_token`.
6. `PASSWORD_RESET_FALLBACK_STARTED` được emit ngay trong `verify-otp` theo quy tắc sequencing ở mục 1.3.2.

### 1.3.5 `POST /api/v1/auth/forgot-password/complete`
*   **Payload**:
    ```json
    {
      "reset_session_id": "uuid",
      "reset_token": "opaque-token",
      "new_password": "new-raw-password"
    }
    ```
*   **Xử lý**:
    1. Verify reset token one-time-use và chưa hết hạn (`used_at IS NULL` và `reset_token_expires_at > NOW()`).
    2. Hash mật khẩu mới.
    3. Update `auth_credentials.password_hash`, `password_changed_at`.
    4. Đánh dấu reset token đã dùng (`used_at = NOW()`, state `COMPLETED`).
    5. Revoke toàn bộ refresh token family và đóng toàn bộ device sessions của user.
    6. Tăng `session_version` để vô hiệu access token cũ.
    7. Emit `PASSWORD_RESET_COMPLETED`, `PASSWORD_CHANGED` và gửi cảnh báo bảo mật.

### 1.4 Làm mới phiên (Refresh Token Rotation + Grace Period 3 phút)
Access token ngắn hạn (ví dụ 15 phút), refresh token dài hạn (ví dụ 7 ngày).

#### `POST /api/v1/auth/refresh`
*   **Input**: Refresh token từ HTTPOnly Cookie.
*   **Xử lý**:
    1. Parse token -> hash -> tìm trong `auth_refresh_tokens`.
    2. Nếu token không tồn tại/hết hạn/revoked -> từ chối.
    3. Nếu token hợp lệ và chưa dùng -> rotation bình thường:
        * đánh dấu token cũ `replaced_by_token=<new_token_id>`
        * tạo token mới cùng `token_family_id`
        * trả access + refresh mới
    4. Nếu token là **previous token đã bị replace**:
        * nếu chưa có grace window: tạo grace 3 phút ở cả DB (`grace_expires_at`) và Redis, đồng thời emit `TOKEN_REPLAY_GRACE_STARTED`.
        * chỉ cho phép **đúng 1 lần** trong grace period 3 phút.
        * ghi nhận `grace_replay_used=true`, tăng `replay_incident_count` ở DB + Redis, emit `TOKEN_REPLAY_GRACE_USED`.
    5. Nếu replay lần tiếp theo trong grace hoặc replay sau khi grace hết:
        * xác định khả năng hijacking.
        * thực hiện **GLOBAL LOGOUT** toàn bộ thiết bị/user.
        * revoke toàn bộ refresh token family.
        * tăng `session_version` và emit `GLOBAL_LOGOUT_TRIGGERED`.
        * API xử lý ngay trong request hiện tại; worker chỉ là lớp dự phòng cho race condition/multi-node.

#### Quy tắc cứng cho replay
1.  Grace period chỉ áp dụng cho token liền trước (`previous refresh token`).
2.  Không áp dụng cho token cũ hơn hoặc token chain sai thứ tự.
3.  Sau khi grace replay đã dùng 1 lần, mọi replay tiếp theo đều kích hoạt global logout.
4.  Khi Redis grace key đã hết hạn, API phải fallback sang kiểm tra DB (`grace_expires_at`, `grace_replay_used`, `replay_incident_count`) để quyết định global logout.

### 1.5 Đăng xuất

#### `POST /api/v1/auth/logout`
*   Blacklist access token jti theo TTL còn lại.
*   Revoke refresh token hiện tại.
*   Đóng `auth_device_sessions` tương ứng.

#### `POST /api/v1/auth/logout-all`
*   Revoke toàn bộ token family của user.
*   Mark toàn bộ device sessions inactive.
*   Tăng `session_version` để invalidate access token đang sống.

#### `DELETE /api/v1/auth/devices/{device_id}`
*   Revoke token/session của thiết bị chỉ định.

---

## 2. API Nội bộ (Internal gRPC)

### `AuthService.ValidateToken`
*   **Method**: `rpc ValidateToken(ValidateRequest) returns (ValidateResponse)`
*   **Request**: `access_token_string`
*   **Logic**:
    1. Verify JWT signature + `exp` + `nbf`.
    2. Check blacklist key `auth:blacklist:{jti}`.
    3. Check `session_version` claim so với Redis current version.
    4. Trả `user_id`, `roles`, `permissions` cho API Gateway nếu hợp lệ.
*   **Performance Goal**: P99 < 2ms.

---

## 3. Error Contract chuẩn hóa
*   `400`: Sai format input/thiếu field.
*   `401`: Token không hợp lệ/hết hạn/OTP-TOTP sai.
*   `403`: Account bị khóa, account banned, reset session invalid state.
*   `409`: Token replay state conflict hoặc reset token đã dùng.
*   `429`: Quá giới hạn rate limit.
*   `5xx`: Lỗi hệ thống, bắt buộc có `error_id` để trace.
