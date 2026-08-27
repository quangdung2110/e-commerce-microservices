---
doc_id: auth-domain
service: auth
scope: target
status: proposed
owners:
  - Auth Service team
evidence: []
verified_against_commit: null
---

# Auth Service — Domain Model

> File này trả lời: “Auth Service đang bảo vệ những quy tắc nghiệp vụ nào?”

## 1. Ubiquitous language

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Account | Danh tính dùng để xác thực, có credential và trạng thái | User profile |
| Session | Một phiên đăng nhập, đại diện bằng refresh-token family | Access token |
| Refresh token | Opaque credential dùng một lần để đổi token pair | JWT access token |
| Access token | JWT sống ngắn dùng cho request tới API | Refresh token |
| `jti` | Định danh duy nhất của một access token | `user_id` |
| Revocation marker | Mốc thời gian vô hiệu token cũ của một user | Denylist của một `jti` |
| JWT key | Cặp khóa ký/xác minh JWT, định danh bằng `kid` | Refresh-token key |
| OTP | Secret sống ngắn để xác minh email | Reset token opaque |
| Reset token | Secret opaque trong link email để reset password | OTP |

## 2. Domain objects

`Account` là aggregate chính: identity email, password hash, roles, trạng thái
và dữ liệu lock. `Session`/refresh-token family thuộc Account nhưng
có lifecycle độc lập để thu hồi theo thiết bị. `JwtKey` quản lý vòng đời key ký.
Profile chỉ được tham chiếu qua `user_id`; Auth không sở hữu profile.

## 3. Commands and queries

| Type | Capability | Actor | Aggregate/capability |
|---|---|---|---|
| Command | Register, verify OTP | Anonymous user | Account |
| Command | Login, refresh, logout | Client | Account/Session |
| Command | Change/reset password | Account owner | Account/Session |
| Command | Request/confirm email change | Account owner | Account/Session |
| Command | Revoke one session | Account owner | Session |
| Command | Delete account | Account owner/authorized admin | Account/Session |
| Command | Unlock account | System worker | Account |
| Query | List active sessions | Account owner | Session |
| Query | Read JWKS | Gateway/public verifier | JwtKey |

## 4. Business rules and invariants

| ID | Rule | Violation |
|---|---|---|
| AUTH-BR-001 | Chỉ `ACTIVE` account được login hoặc refresh | `ACCOUNT_NOT_ACTIVE` |
| AUTH-BR-002 | Một refresh token chỉ được đổi thành công một lần | `REFRESH_TOKEN_REVOKED` |
| AUTH-BR-003 | Password change/reset, email change hoặc delete account phải revoke phiên cũ theo policy | `SESSION_REVOCATION_FAILED` hoặc recovery required |
| AUTH-BR-004 | Đăng nhập sai vượt ngưỡng phải lock theo policy | `ACCOUNT_LOCKED` |
| AUTH-BR-005 | OTP/reset token hết hạn hoặc đã dùng không được replay | `OTP_INVALID` / `RESET_TOKEN_INVALID` |
| AUTH-BR-006 | Private key không được trả qua JWKS | Security violation |
| AUTH-BR-007 | `DELETED` account phải anonymize email/password ngay và hard delete sau 90 ngày | `ACCOUNT_DELETED` |
| AUTH-BR-008 | Đổi email yêu cầu mật khẩu hiện tại và OTP gắn với account cùng email mới; email mới phải unique toàn hệ thống | `EMAIL_ALREADY_REGISTERED` / `OTP_INVALID` |
| AUTH-BR-009 | Không áp dụng giới hạn số session hoặc thiết bị đăng nhập trên mỗi account | Không từ chối login chỉ vì số lượng session |

`AUTH-INV-001` đến `AUTH-INV-004` trong [README](./README.md) là các invariant
ở cấp service. Nơi enforcement và test cụ thể chưa được xác minh.

## 5. State machines

### Account

| State | Allowed transition | Trigger | Guard/side effect |
|---|---|---|---|
| `PENDING_VERIFICATION` | `ACTIVE` | Verify OTP hợp lệ | Phát identity-verified event |
| `ACTIVE` | `LOCKED` | Vượt ngưỡng failed login | Ghi `locked_until`, phát lock event |
| `LOCKED` | `ACTIVE` | Hết `locked_until` | Worker hệ thống reset counter liên quan |
| `PENDING_VERIFICATION`/`ACTIVE`/`LOCKED` | `DELETED` | User GDPR request hoặc admin deletion | Anonymize identity/credential, revoke session, phát `USER_DELETED` |
| `DELETED` | Không có | Terminal state | Compliance worker hard delete sau 90 ngày |

### Refresh token

`ACTIVE` → `REVOKED` khi logout, rotation, password change/reset, email change hoặc account deletion;
`ACTIVE` → `EXPIRED` khi qua `expires_at`. Token `REVOKED`/`EXPIRED` là terminal
và không có grace period. Rotation tạo token `ACTIVE` mới cùng `family_id`.

### JWT key

`ACTIVE` → `PHASE_OUT` khi rotation; `PHASE_OUT` → `RETIRED` sau khi mọi access
token có thể tham chiếu key đã hết hạn. Chỉ public portion của key chưa retired
được công bố qua JWKS theo policy cần chốt.

## 6. Authorization rules

Anonymous actor chỉ đăng ký/login/refresh/reset theo rate limit. Account owner
chỉ xem/thu hồi session của chính mình. Account deletion do admin cần credential
`ADMIN`; auto unlock chỉ do system worker. Mọi command phải kiểm tra ownership bằng
`user_id`, không dựa vào client-supplied identity.

## 7. Domain errors

| Error code | Điều kiện | Retryable | Interface mapping |
|---|---|---|---|
| `INVALID_CREDENTIALS` | Password/email không hợp lệ | No | Generic authentication failure |
| `ACCOUNT_NOT_VERIFIED` | Account còn `PENDING_VERIFICATION` | No | Authorization failure |
| `ACCOUNT_LOCKED` | Lock chưa hết hạn | No | Authentication failure có metadata an toàn |
| `REFRESH_TOKEN_INVALID` | Token không tồn tại/hết hạn/revoked | No | Refresh failure |
| `OTP_INVALID` | OTP sai, hết hạn hoặc đã dùng | No | Verification failure |
| `RATE_LIMITED` | Vượt rate limit | Yes, after delay | Throttling response |

## 8. Business examples

Refresh token replay phải thất bại ngay cả khi hai request cùng gửi token; chỉ
một request được tạo token mới. Đổi mật khẩu hoặc xóa account thành công phải khiến
refresh token cũ không thể cấp token tiếp. Một account `LOCKED` không thể login
trước `locked_until`, nhưng worker có thể đưa nó về `ACTIVE` theo policy.
