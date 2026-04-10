# Auth Service - Architecture Design

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](auth_workflow.md)
*   [Database Schema (Cold Store)](auth_database.md)
*   [Caching Schema (Redis)](auth_caching.md)
*   [Events Structure](auth_events.md)
*   [Background Workers & Jobs](auth_workers.md)

---
## 1. Tổng quan (Overview)
Auth Service là một Core Microservice chịu trách nhiệm về **Authentication**, **Token Issuance**, **Session Management** và **Security Controls** cho toàn bộ hệ thống E-commerce.

Auth Service **KHÔNG** quản lý dữ liệu hồ sơ người dùng (profile, địa chỉ, avatar). Các dữ liệu đó thuộc về User Service.

Trong phiên bản thiết kế hiện tại:
1.  **Đăng nhập chính thức bằng OTP qua EMAIL hoặc PHONE**.
2.  **Không triển khai Social Login (Google/Facebook/Apple)** trong phạm vi hiện tại.
3.  **Quên mật khẩu bắt buộc qua xác thực nhiều bước** (OTP + TOTP), có fallback an toàn cho user chưa bật MFA.
4.  **Refresh token rotation có grace period 3 phút** để xử lý replay hợp lệ ngắn hạn và chặn tấn công token replay.

## 2. Mục tiêu bảo mật cốt lõi
1.  **Zero-Trust Input**: Không tin bất kỳ claim nhạy cảm do client tự gửi (device trust, quyền bypass MFA, version session).
2.  **One-time Secret Usage**: OTP, reset session token, refresh token chain đều phải one-time hoặc bounded-use.
3.  **Replay Detection**: Phát hiện và phản ứng tự động với refresh token replay.
4.  **Global Session Revocation**: Khi phát hiện replay nguy hiểm, đăng xuất toàn bộ thiết bị.
5.  **Auditability**: Mọi security action quan trọng phải có audit trail để điều tra.

## 3. Kiến trúc tổng thể (Architecture)
Hệ thống giao tiếp với Auth Service theo 3 luồng:
*   **External (API Gateway -> Auth REST)**: Register, OTP Login, Forgot Password, Refresh, Logout.
*   **Internal Sync (gRPC)**: API Gateway gọi `ValidateToken` cho mọi request cần xác thực.
*   **Internal Async (Kafka)**: Auth phát sự kiện bảo mật/đăng nhập để Notification, Analytics, Fraud Service xử lý.

## 4. Domain Boundaries
1.  **Identity Management**: Email/Phone/Username gắn với Global `user_id`.
2.  **Credential Management**: Password hash, MFA secret, backup code hash, lockout state.
3.  **Token Management**: Access JWT + Refresh reference token (hash lưu DB).
4.  **Session Management**: Quản lý thiết bị, trạng thái active/revoked, global logout.
5.  **Security Controls**: Rate limit, lockout, OTP/TOTP verification limits, risk signals.

## 5. In-Scope và Out-of-Scope
### 5.1 In-Scope
*   Native account (EMAIL/PHONE) với OTP verification.
*   Đăng nhập OTP (passwordless trong pha hiện tại).
*   Quên mật khẩu xác thực 2 bước: OTP + Google Authenticator TOTP 6 số.
*   Fallback khi user chưa bật MFA: OTP + reset link một lần qua email + risk check.
*   Refresh token rotation với grace period 3 phút và global logout toàn thiết bị khi vi phạm.

### 5.2 Out-of-Scope (Pha hiện tại)
*   Social Login OAuth2: Google/Facebook/Apple.
*   Federation/SSO enterprise (SAML/OIDC enterprise IdP).

## 6. Security Baseline
1.  **Hash Password**: Argon2id (hoặc BCrypt với cost chuẩn hóa).
2.  **Hash OTP trước khi lưu Redis**: không lưu plaintext OTP.
3.  **Encrypt MFA Secret tại storage**: dùng KMS/Vault.
4.  **MFA Backup Codes**: chỉ lưu hash, không lưu raw.
5.  **Token Signing Key Rotation**: hỗ trợ kid và vòng đời key định kỳ.
6.  **Rate Limit đa tầng**: theo identity, IP, device fingerprint.
7.  **Audit bắt buộc**: login, reset password, replay grace, global logout, MFA changes.

## 7. Các thành phần tài liệu kỹ thuật

| Component | File | Mô tả |
| :--- | :--- | :--- |
| **Cấu trúc CSDL (PostgreSQL)** | [auth_database.md](auth_database.md) | Identities, credentials, refresh token chain, reset session, device session, audit log. |
| **Caching & Rate Limits (Redis)** | [auth_caching.md](auth_caching.md) | OTP/TOTP state, rate limits, lockout, replay grace keys, token blacklist. |
| **Workflows & APIs** | [auth_workflow.md](auth_workflow.md) | Luồng register, OTP login, forgot password 2-step, refresh rotation, logout, validate token. |
| **Kafka Events** | [auth_events.md](auth_events.md) | Event contract cho login/reset/replay/global logout và lệnh inbound từ service khác. |
| **Background Workers** | [auth_workers.md](auth_workers.md) | Outbox relay, cleanup jobs, risk analyzer, replay escalation, audit archive. |

## 8. Thuật ngữ chuẩn hóa
1.  **Token Family**: Toàn bộ refresh token active của một user trên tất cả thiết bị/client trong vòng đời hiện tại.
2.  **Grace Replay (3 phút)**: Cửa sổ tạm chấp nhận đúng 1 lần gửi lại refresh token liền trước.
3.  **Global Logout**: Revoke toàn bộ refresh token family + invalidate access token còn sống bằng session version/blacklist.
4.  **Reset Session**: phiên tạm cho flow quên mật khẩu, chỉ dùng 1 lần và có TTL ngắn.
