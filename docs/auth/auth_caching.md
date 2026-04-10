# Auth Service - Caching Schema & Rate Limiting (Redis)

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Workflows (API)](auth_workflow.md)

---
Redis trong Auth Service dùng cho 4 nhóm chính: rate limit, OTP/TOTP state, token/session revocation, và replay grace tracking.

## 1. Quy tắc chung
1.  Tất cả OTP/TOTP secret lưu dưới dạng hash, không lưu plaintext.
2.  Key chứa identity phải dùng `identity_value_normalized`.
3.  Tất cả counters phải có TTL rõ ràng để tránh tăng vô hạn.
4.  Dùng atomic script/Lua cho thao tác check-and-increment quan trọng.

## 2. Rate Limiting

### 2.1 OTP Request Rate Limit
*   **Key**: `auth:rl:otp:req:{action}:{identity_normalized}:{window}`
*   **Action**: `register`, `login`, `reset`
*   **Rule**:
    *   3 lần / 15 phút / identity
    *   5 lần / ngày / identity
*   **Response khi vượt ngưỡng**: HTTP 429

### 2.2 Login Request theo IP
*   **Key**: `auth:rl:login:ip:{client_ip}:{window}`
*   **Rule**: 30 lần/giờ/IP cho endpoint request OTP login.

### 2.3 OTP Verify Attempts
*   **Key**: `auth:rl:otp:verify:{action}:{identity_normalized}`
*   **Rule**: tối đa 5 lần sai/10 phút.
*   **Vượt ngưỡng**: set `auth:lockout:identity:{identity_normalized}` TTL 30 phút.

### 2.4 Forgot Password Request Limits
*   **Key**: `auth:rl:reset:req:{identity_normalized}:{window}`
*   **Rule**:
    *   3 lần/giờ/identity
    *   10 lần/ngày/identity

### 2.5 TOTP Verify Limits (Reset Flow)
*   **Key**: `auth:rl:reset:totp:{reset_session_id}`
*   **Rule**: tối đa 10 lần sai/15 phút.
*   **Vượt ngưỡng**: set state reset session sang `LOCKED`.

## 3. OTP/TOTP và Session tạm thời

### 3.1 OTP Register/Login/Reset
*   **Key**: `auth:otp:{action}:{identity_normalized}`
*   **Action**: `register`, `login`, `reset`
*   **Value**:
    ```json
    {
      "otp_hash": "sha256...",
      "attempts": 0,
      "max_attempts": 5,
      "session_id": "uuid",
      "created_at": 1710000000
    }
    ```
*   **TTL**: 5 phút.

### 3.2 Login OTP Session Binding
*   **Key**: `auth:otp:login:session:{otp_login_session_id}`
*   **Value**: `{user_id, identity_normalized, device_fingerprint_hash}`
*   **TTL**: 5 phút.

### 3.3 Reset MFA Challenge
*   **Key**: `auth:mfa:challenge:{reset_session_id}`
*   **Value**: `{challenge_token_hash, user_id, attempts}`
*   **TTL**: 5 phút.

### 3.4 Single-use Reset Token (gửi qua email link)
*   **Key**: `auth:reset:link:{reset_session_id}`
*   **Value**: `{token_hash, used=false}`
*   **TTL**: 10 phút.

## 4. Token & Session Revocation

### 4.1 Access Token Blacklist
*   **Key**: `auth:blacklist:{jti}`
*   **Value**: `1`
*   **TTL**: thời gian sống còn lại của access token.

### 4.2 Session Version
*   **Key**: `auth:session_version:{user_id}`
*   **Value**: integer tăng dần (`1`, `2`, `3`...)
*   **TTL**: không hết hạn.

## 5. Refresh Replay Grace Period (3 phút)

### 5.1 Grace State Key
*   **Key**: `auth:refresh:grace:{user_id}:{old_refresh_token_id}`
*   **Value**:
    ```json
    {
      "grace_expires_at": 1710000000,
      "grace_replay_used": false,
      "replay_incident_count": 1,
      "first_seen_ip": "1.2.3.4",
      "first_seen_device_id": "device-123"
    }
    ```
*   **TTL**: 3 phút.

### 5.2 Quy tắc xử lý
1.  Lần replay đầu tiên của previous token trong 3 phút: cho phép và set `grace_replay_used=true`.
2.  Mỗi replay phải tăng `replay_incident_count` ở cả Redis và DB.
3.  Replay tiếp theo trong cùng key hoặc replay sau khi key hết hạn: kích hoạt global logout.
4.  Khi Redis key hết hạn, API fallback sang DB (`grace_expires_at`, `grace_replay_used`, `replay_incident_count`) để tránh bypass.
5.  Khi global logout: xóa toàn bộ key grace liên quan user để tránh state bẩn.

## 6. Device Risk Cache
*   **Key**: `auth:devices:{user_id}`
*   **Type**: Set (danh sách device_id đã tin cậy gần đây)
*   **TTL**: 24 giờ
*   **Mục tiêu**: tăng tốc risk check, không thay thế dữ liệu chuẩn trong DB.

## 7. Metrics khuyến nghị
*   `auth_otp_request_total{action}`
*   `auth_otp_verify_failed_total{action}`
*   `auth_reset_totp_failed_total`
*   `auth_refresh_grace_used_total`
*   `auth_global_logout_triggered_total{reason}`
*   `auth_rate_limit_block_total{key_type}`
