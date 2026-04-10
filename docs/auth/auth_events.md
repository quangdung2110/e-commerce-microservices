# Auth Service - Events Structure & Messaging (Kafka)

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Workflows (API)](auth_workflow.md)

---
Auth Service phát sự kiện qua Kafka theo Transactional Outbox. Mọi event đều có `event_id` duy nhất, timestamp mili-giây và payload version để chống breaking change.

## 1. Chuẩn envelope event

```json
{
    "event_id": "uuid",
    "event_type": "EVENT_NAME",
    "event_version": 1,
    "timestamp_ms": 1710000000123,
    "source": "auth-service",
    "trace_id": "req-uuid",
    "data": {}
}
```

*   **Topic chính**: `t.auth.events`
*   **Partition key khuyến nghị**: `user_id`

## 2. Events Produced

### 2.1 Identity & Login

#### `USER_REGISTERED`
*   **Routing key**: `user.registered`
*   **data**: `{user_id, identity_type, identity_value_masked}`
*   **Consumers**: User Service, Notification Service, Promotion Service.

#### `OTP_LOGIN_REQUESTED`
*   **Routing key**: `auth.otp_login_requested`
*   **data**: `{user_id, identity_type, channel, request_ip}`
*   **Consumers**: Analytics, Fraud Service.

#### `OTP_LOGIN_VERIFIED`
*   **Routing key**: `auth.otp_login_verified`
*   **data**: `{user_id, device_id, ip_address, login_session_id}`
*   **Consumers**: Analytics, Device Intelligence, Notification (optional).

#### `USER_LOGGED_IN`
*   **Routing key**: `user.logged_in`
*   **data**: `{user_id, device_id, ip_address, auth_method:"OTP"}`

### 2.2 Password Reset Lifecycle

#### `PASSWORD_RESET_REQUESTED`
*   **Routing key**: `auth.password_reset_requested`
*   **data**: `{user_id, reset_session_id, identity_type}`

#### `PASSWORD_RESET_OTP_VERIFIED`
*   **Routing key**: `auth.password_reset_otp_verified`
*   **data**: `{user_id, reset_session_id}`

#### `PASSWORD_RESET_FALLBACK_STARTED`
*   **Routing key**: `auth.password_reset_fallback_started`
*   **data**: `{user_id, reset_session_id, reason:"MFA_NOT_ENABLED"}`

#### `PASSWORD_RESET_MFA_VERIFIED`
*   **Routing key**: `auth.password_reset_mfa_verified`
*   **data**: `{user_id, reset_session_id, method:"TOTP"}`

#### `PASSWORD_RESET_COMPLETED`
*   **Routing key**: `auth.password_reset_completed`
*   **data**: `{user_id, reset_session_id, fallback_used, forced_logout_all:true}`
*   **Consumers**: Notification Service (cảnh báo đổi mật khẩu), Security Monitoring.

### 2.3 Token Replay & Security

#### `TOKEN_REPLAY_GRACE_STARTED`
*   **Routing key**: `auth.token_replay_grace_started`
*   **data**: `{user_id, refresh_token_id, grace_expires_at}`

#### `TOKEN_REPLAY_GRACE_USED`
*   **Routing key**: `auth.token_replay_grace_used`
*   **data**: `{user_id, refresh_token_id, replay_count:1}`

#### `GLOBAL_LOGOUT_TRIGGERED`
*   **Routing key**: `auth.global_logout_triggered`
*   **data**: `{user_id, reason:"REFRESH_REPLAY", impacted_devices_count}`
*   **Consumers**: Notification Service, Security Ops, Analytics.

#### `SUSPICIOUS_LOGIN_DETECTED`
*   **Routing key**: `auth.suspicious_login`
*   **data**: `{user_id, risk_reasons, current_ip, previous_ip, score}`

### 2.4 Security State Changed

#### `PASSWORD_CHANGED`
*   **Routing key**: `auth.password_changed`
*   **data**: `{user_id, changed_by:"USER_RESET"|"USER_ACTION"|"ADMIN"}`

#### `MFA_ENABLED` / `MFA_DISABLED`
*   **Routing key**: `auth.mfa_changed`
*   **data**: `{user_id, action, actor}`

## 3. Events Consumed

### 3.1 `ACCOUNT_BANNED`
*   **Source**: Admin/User service
*   **Topic**: `t.user.events`
*   **Auth action**:
    1. Revoke toàn bộ refresh tokens của user.
    2. Close toàn bộ device sessions.
    3. Bump `session_version`.
    4. Emit `GLOBAL_LOGOUT_TRIGGERED` với reason `ACCOUNT_BANNED`.

### 3.2 `ACCOUNT_DELETED`
*   **Source**: User service
*   **Topic**: `t.user.events`
*   **Auth action**: revoke sessions/tokens + xóa dữ liệu theo retention policy.

### 3.3 `FORCE_LOGOUT_DEVICE`
*   **Source**: User Service (trang quản lý thiết bị)
*   **Topic**: `t.user.commands`
*   **Auth action**: revoke đúng refresh token/device session mục tiêu.

## 4. Delivery Guarantees
1.  Outbox insert cùng transaction với thay đổi domain state.
2.  Relay worker retry với backoff; quá ngưỡng đưa vào DLQ.
3.  Consumer phải idempotent theo `event_id`.
4.  Payload không chứa bí mật (không gửi OTP, không gửi token raw).

