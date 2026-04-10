# Auth Service - Database Schema Design

**Liên kết nhanh:**
*   [Kiến trúc tổng thể](auth_design.md)
*   [Workflows (API)](auth_workflow.md)
*   [Caching Schema](auth_caching.md)

---
CSDL Auth Service chỉ lưu dữ liệu cần cho xác thực, quản lý phiên, reset mật khẩu và audit bảo mật. Không lưu profile nghiệp vụ.

## 1. Bảng `auth_identities`
Lưu định danh đăng nhập (EMAIL/PHONE/USERNAME) của user.

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX
*   `identity_type`: VARCHAR(20) NOT NULL (`EMAIL`, `PHONE`, `USERNAME`)
*   `identity_value`: VARCHAR(255) NOT NULL
*   `identity_value_normalized`: VARCHAR(255) NOT NULL, UNIQUE
*   `is_verified`: BOOLEAN DEFAULT FALSE
*   `status`: VARCHAR(20) DEFAULT `ACTIVE` (`ACTIVE`, `LOCKED`, `DISABLED`)
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

## 2. Bảng `auth_credentials`
Lưu password hash, MFA state, lockout/security counters mức DB.

*   `user_id`: UUID PRIMARY KEY
*   `password_hash`: VARCHAR(255) NOT NULL
*   `mfa_enabled`: BOOLEAN DEFAULT FALSE
*   `mfa_secret_encrypted`: TEXT
*   `mfa_backup_codes_hash`: JSONB
*   `password_changed_at`: TIMESTAMPTZ
*   `failed_login_attempts`: INT DEFAULT 0
*   `lockout_until`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

## 3. Bảng `auth_refresh_tokens`
Quản lý refresh token chain/family, rotation và replay tracking.

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX
*   `token_family_id`: UUID NOT NULL, INDEX
*   `parent_token_id`: UUID NULL, INDEX
*   `client_id`: VARCHAR(100)
*   `device_id`: VARCHAR(255)
*   `token_hash`: VARCHAR(255) NOT NULL UNIQUE
*   `expires_at`: TIMESTAMPTZ NOT NULL
*   `is_revoked`: BOOLEAN DEFAULT FALSE
*   `replaced_by_token`: UUID NULL
*   `grace_expires_at`: TIMESTAMPTZ NULL
*   `grace_replay_used`: BOOLEAN DEFAULT FALSE
*   `replay_incident_count`: INT DEFAULT 0
*   `issued_ip`: VARCHAR(45)
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

## 4. Bảng `auth_device_sessions`
Cho phép truy vết phiên theo thiết bị và hỗ trợ remote logout.

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX
*   `refresh_token_id`: UUID NOT NULL REFERENCES `auth_refresh_tokens(id)`
*   `device_id`: VARCHAR(255) NOT NULL
*   `device_name`: VARCHAR(255)
*   `device_fingerprint_hash`: VARCHAR(255)
*   `ip_address`: VARCHAR(45)
*   `last_active_at`: TIMESTAMPTZ
*   `is_active`: BOOLEAN DEFAULT TRUE
*   `revoked_reason`: VARCHAR(50)
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

## 5. Bảng `auth_password_reset_sessions`
Theo dõi luồng quên mật khẩu nhiều bước, chống replay/reset token reuse.

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NOT NULL, INDEX
*   `identity_type`: VARCHAR(20) NOT NULL
*   `identity_value_normalized`: VARCHAR(255) NOT NULL
*   `state`: VARCHAR(30) NOT NULL
	*   `OTP_PENDING`, `OTP_VERIFIED`, `MFA_PENDING`, `MFA_VERIFIED`, `COMPLETED`, `EXPIRED`, `LOCKED`
*   `otp_verified_at`: TIMESTAMPTZ NULL
*   `mfa_verified_at`: TIMESTAMPTZ NULL
*   `fallback_used`: BOOLEAN DEFAULT FALSE
*   `reset_token_hash`: VARCHAR(255) NULL UNIQUE
*   `reset_token_expires_at`: TIMESTAMPTZ NULL
*   `used_at`: TIMESTAMPTZ NULL
*   `expires_at`: TIMESTAMPTZ NOT NULL
*   `risk_score`: NUMERIC(5,2) DEFAULT 0
*   `created_ip`: VARCHAR(45)
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

## 6. Bảng `auth_audit_events`
Audit trail cho điều tra bảo mật và compliance.

*   `id`: UUID PRIMARY KEY
*   `user_id`: UUID NULL, INDEX
*   `event_type`: VARCHAR(80) NOT NULL
*   `severity`: VARCHAR(20) NOT NULL (`INFO`, `WARN`, `CRITICAL`)
*   `request_id`: VARCHAR(64)
*   `ip_address`: VARCHAR(45)
*   `device_id`: VARCHAR(255)
*   `user_agent`: TEXT
*   `metadata`: JSONB
*   `created_at`: TIMESTAMPTZ

## 7. Bảng `auth_outbox_events`
Bảng outbox để đảm bảo event chỉ phát sau khi transaction nghiệp vụ đã commit.

*   `event_id`: UUID PRIMARY KEY
*   `aggregate_type`: VARCHAR(50) NOT NULL
*   `aggregate_id`: UUID NOT NULL
*   `event_type`: VARCHAR(80) NOT NULL
*   `payload`: JSONB NOT NULL
*   `status`: VARCHAR(20) NOT NULL DEFAULT `PENDING`
*   `retry_count`: INT DEFAULT 0
*   `max_retries`: INT DEFAULT 10
*   `next_retry_at`: TIMESTAMPTZ NULL
*   `error_message`: TEXT NULL
*   `created_at`: TIMESTAMPTZ
*   `published_at`: TIMESTAMPTZ NULL

## 8. Session Version Store (Redis-backed)
`session_version` dùng để invalidate access token hàng loạt.

*   Canonical value lưu ở Redis key `auth:session_version:{user_id}`.
*   Có thể snapshot về PostgreSQL nếu cần forensic dài hạn.

## 9. Indexing khuyến nghị
*   `idx_auth_identities_type_value` on (`identity_type`, `identity_value_normalized`)
*   `idx_auth_refresh_user_revoked` on (`user_id`, `is_revoked`)
*   `idx_auth_refresh_family` on (`token_family_id`, `created_at`)
*   `idx_auth_reset_state_exp` on (`state`, `expires_at`)
*   `idx_auth_sessions_user_active` on (`user_id`, `is_active`)
*   `idx_auth_audit_user_time` on (`user_id`, `created_at DESC`)
*   `idx_auth_outbox_status_retry` on (`status`, `next_retry_at`, `created_at`)

## 10. Thiết kế và ràng buộc quan trọng
1.  **Không dùng bảng SSO trong pha hiện tại**: Social login bị loại khỏi scope.
2.  **Refresh token chỉ lưu hash**: giảm rủi ro nếu lộ DB.
3.  **One-time reset token**: reset session chỉ được complete đúng 1 lần.
4.  **Global logout**: revoke toàn bộ `token_family_id` active của user và set `session_version` mới.
5.  **Retention Audit**: lưu tối thiểu 2 năm hoặc theo policy compliance.
