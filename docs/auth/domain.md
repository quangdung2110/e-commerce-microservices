---
doc_id: auth-domain
service: auth
scope: current
status: implemented
owners:
  - Auth Service team
evidence:
  - go/services/auth-service/internal/domain/entity/account.go
  - go/services/auth-service/internal/domain/service
  - go/services/auth-service/internal/common/errors/errors.go
  - go/services/auth-service/internal/api/httpx/problem.go
  - go/services/auth-service/test/unit
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
| Revocation marker | Mốc thời gian (`revoked_before`) vô hiệu token cũ của một user | Denylist của một `jti` |
| JWT key | Cặp khóa ký/xác minh JWT, định danh bằng `kid` | Refresh-token key |
| OTP | Secret sống ngắn để xác minh email | Reset token opaque |
| Reset token | Secret opaque trong link email để reset password | OTP |
| Revision | Phiên bản của row account (`updated_at` lúc đọc) dùng để chặn ghi đè bản mới hơn | `Version` nghiệp vụ |

## 2. Domain objects

`Account` là aggregate chính: identity email, password hash, roles, trạng thái
và dữ liệu lock. `Session`/refresh-token family thuộc Account nhưng
có lifecycle độc lập để thu hồi theo thiết bị. `JwtKey` quản lý vòng đời key ký.
Profile chỉ được tham chiếu qua `user_id`; Auth không sở hữu profile.

## 3. Commands and queries

| Type | Capability | Actor | Aggregate/capability |
|---|---|---|---|
| Command | Register, verify OTP, gửi lại OTP | Anonymous user | Account |
| Command | Login, refresh, logout | Client | Account/Session |
| Command | Change/reset password | Account owner | Account/Session |
| Command | Request/confirm email change | Account owner | Account/Session |
| Command | Revoke one session / all sessions | Account owner | Session |
| Command | Delete account | Account owner/authorized admin | Account/Session |
| Command | Unlock account | System worker; hoặc login/refresh thành công khi `locked_until` đã qua; hoặc hoàn tất đổi/reset mật khẩu | Account |
| Query | List active sessions | Account owner | Session |
| Query | Read JWKS | Gateway/public verifier | JwtKey |
| Query | Resolve notification delivery / contact | Notification Service (internal) | Account, delivery state |

## 4. Business rules and invariants

| ID | Rule | Violation |
|---|---|---|
| AUTH-BR-001 | Chỉ `ACTIVE` account được login hoặc refresh. Trạng thái chỉ được nói cho người đã chứng minh mật khẩu | Sai mật khẩu: `INVALID_CREDENTIALS` (mọi trạng thái). Đúng mật khẩu: `PENDING_VERIFICATION` → `ACCOUNT_NOT_VERIFIED`, `LOCKED` chưa hết hạn → `ACCOUNT_LOCKED`. Refresh với account không `ACTIVE` → `REFRESH_TOKEN_INVALID`. Guard cấp token → `ACCOUNT_NOT_ACTIVE` |
| AUTH-BR-002 | Một refresh token chỉ được đổi thành công một lần; không có grace period | `REFRESH_TOKEN_INVALID` (token không tồn tại, hết hạn hoặc đã revoke) |
| AUTH-BR-003 | Password change/reset, email change hoặc delete account phải revoke phiên cũ: refresh token trong DB cùng marker `revoked_before` trong Redis | Refresh token luôn bị revoke trong DB; nếu ghi marker Redis lỗi thì trả `DEPENDENCY_UNAVAILABLE` (retryable) và access token đã phát còn sống tới khi hết hạn; thay đổi đã commit không rollback ([ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)) |
| AUTH-BR-004 | Đăng nhập sai (account `ACTIVE`) đủ ngưỡng thì lock; bộ đếm lưu bền theo account, reset sau khoảng yên lặng hoặc đăng nhập đúng | `ACCOUNT_LOCKED` (423) ở lần sai chạm ngưỡng; mặc định 5 lần, khóa 15 phút, reset bộ đếm sau 1 giờ |
| AUTH-BR-005 | OTP/reset token hết hạn hoặc đã dùng không được replay | `OTP_INVALID` / `RESET_TOKEN_INVALID` |
| AUTH-BR-006 | Private key không được trả qua JWKS | Security violation |
| AUTH-BR-007 | `DELETED` account phải anonymize email/password ngay và hard delete sau 90 ngày; `DELETED` là terminal, yêu cầu xóa lần hai không đổi retention window | `ACCOUNT_NOT_ACTIVE` (409, detail "already deleted") |
| AUTH-BR-008 | Đổi email yêu cầu mật khẩu hiện tại và OTP gắn với account cùng email mới; email mới phải unique toàn hệ thống và khác email hiện tại | `EMAIL_ALREADY_REGISTERED` / `OTP_INVALID` |
| AUTH-BR-009 | Không áp dụng giới hạn số session hoặc thiết bị đăng nhập trên mỗi account | Không từ chối login chỉ vì số lượng session |
| AUTH-BR-010 | Không lưu account từ một bản đã cũ: mọi lần ghi phải dựa trên phiên bản đã đọc, nếu không phải đọc lại và áp dụng lại thay đổi ([ADR-008](./decisions/ADR-008-account-optimistic-concurrency.md)) | `CONCURRENT_UPDATE` (500, retryable) khi thua 4 lần liên tiếp |
| AUTH-BR-011 | Các endpoint công khai không được cho biết một địa chỉ có account hay không (resend OTP và yêu cầu reset luôn `202`; vượt ngân sách theo account thì im lặng không gửi, ngân sách theo IP vẫn trả `429`) | — |

`AUTH-INV-001` đến `AUTH-INV-004` trong [README](./README.md) là các invariant ở cấp
service. Enforcement: `TokenService.Issue` (INV-001), `RefreshTokenRepository.Rotate`
(INV-002), `JwksService`/`Signer.PublicKeys` (INV-003), `Revoker` cùng `gateway-auth` (INV-004);
test ở `test/unit/service`, `test/integration`.

## 5. State machines

### Account

| State | Allowed transition | Trigger | Guard/side effect |
|---|---|---|---|
| `PENDING_VERIFICATION` | `ACTIVE` | Verify OTP hợp lệ | Phát `USER_VERIFIED_SUCCESS` |
| `ACTIVE` | `LOCKED` | Vượt ngưỡng failed login | Ghi `locked_until`, phát `USER_ACCOUNT_LOCKED` |
| `LOCKED` | `ACTIVE` | Hết `locked_until`, do worker `auto-unlock-account`, hoặc do login/refresh thành công đầu tiên sau đó | Xóa `locked_until` và bộ đếm; bên thắng compare-and-set phát `USER_UNLOCKED` (một lần) |
| `LOCKED` | `ACTIVE` | Hoàn tất đổi hoặc reset mật khẩu | `ClearLockout`; không phát `USER_UNLOCKED` (đã có `USER_PASSWORD_CHANGED`) |
| `PENDING_VERIFICATION`/`ACTIVE`/`LOCKED` | `DELETED` | User GDPR request hoặc admin deletion | Anonymize identity/credential, revoke session, phát `USER_DELETED` |
| `DELETED` | Không có | Terminal state | Compliance worker hard delete sau 90 ngày |

### Refresh token

`ACTIVE` → `REVOKED` khi logout, rotation, password change/reset, email change hoặc account deletion;
`ACTIVE` → `EXPIRED` khi qua `expires_at` (sliding) hoặc `absolute_expires_at`. Token
`REVOKED`/`EXPIRED` là terminal và không có grace period. Rotation tạo token `ACTIVE`
mới cùng `family_id`.

### JWT key

`ACTIVE` → `PHASE_OUT` khi rotation; `PHASE_OUT` → `RETIRED` sau `keys.retirement_delay`
(không ngắn hơn access TTL). Chỉ public portion của key `ACTIVE` và `PHASE_OUT` được công bố
qua JWKS.

## 6. Authorization rules

Anonymous actor chỉ đăng ký/login/refresh/reset theo rate limit. Account owner
chỉ xem/thu hồi session của chính mình. Account deletion do admin cần credential
`ADMIN` (từ `X-User-Roles` do Gateway inject); auto unlock chỉ do system worker hoặc đường
inline đã nêu. Mọi command phải kiểm tra ownership bằng `user_id` từ `X-User-Id` của Gateway,
không dựa vào client-supplied identity. Listener internal chỉ cho caller trong allowlist
`X-Service-Identity` (xem [ADR-007](./decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md)).

## 7. Domain errors

Mọi lỗi trả `application/problem+json` (RFC 9457) với trường `code` ổn định. Danh mục dưới đây
khớp `internal/common/errors/errors.go` và ánh xạ HTTP ở `internal/api/httpx/problem.go`.
Ma trận theo endpoint nằm ở [Interfaces §6](./interfaces.md#6-error-catalog-và-ma-trận-response).

| Error code | HTTP | Retryable | Điều kiện |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | No | Payload sai schema; password/confirm không khớp; policy password khi đăng ký hoặc reset (contract hai endpoint đó không có `422`); cursor sai |
| `UNAUTHORIZED` | 401 | No | Thiếu hoặc sai identity do Gateway inject (`X-User-Id`); thiếu `X-Service-Identity` ở listener internal |
| `INVALID_CREDENTIALS` | 401 | No | Email/mật khẩu sai (kể cả email không tồn tại, account bị khóa/chờ xác minh khi mật khẩu sai); mật khẩu hiện tại sai khi đổi mật khẩu/email/xóa account |
| `REFRESH_TOKEN_INVALID` | 401 | No | Refresh token vắng, không tồn tại, hết hạn, đã revoke hoặc account không còn `ACTIVE`; response xóa cookie |
| `FORBIDDEN` | 403 | No | Thiếu role `ADMIN` ở endpoint admin; `X-Service-Identity` ngoài allowlist |
| `ACCOUNT_NOT_VERIFIED` | 403 | No | Đăng nhập đúng mật khẩu khi account còn `PENDING_VERIFICATION` |
| `ACCOUNT_NOT_FOUND` | 404 | No | Admin xóa account không tồn tại; internal contact của account không tồn tại hoặc đã xóa |
| `SESSION_NOT_FOUND` | 404 | No | `session_id` không tồn tại hoặc không thuộc account |
| `NOT_FOUND` / `METHOD_NOT_ALLOWED` | 404 / 405 | No | Route hoặc method không có |
| `DELIVERY_NOT_FOUND` | 404 | No | `delivery_id` không tồn tại (internal) |
| `EMAIL_ALREADY_REGISTERED` | 409 | No | Email đã có account (đăng ký) hoặc đã được dùng/trùng email hiện tại (đổi email) |
| `ACCOUNT_NOT_ACTIVE` | 409 | No | Verify OTP khi account không còn `PENDING_VERIFICATION`; xác nhận đổi email khi không `ACTIVE`; xóa account đã `DELETED` (detail "already deleted") |
| `ACCOUNT_DELETED` | 409 | No | Chỉ là chốt chặn ở entity (`SetPasswordHash`/`ChangeEmail` trên account đã xóa); các luồng hiện tại chặn từ trước nên không tới được |
| `DELIVERY_EXPIRED` | 410 | No | Delivery state đã hết TTL (internal) |
| `OTP_INVALID` | 422 | No | OTP sai, hết hạn, đã dùng hoặc hết lượt thử; email không có account (verify) |
| `RESET_TOKEN_INVALID` | 422 | No | Reset token sai, hết hạn, đã dùng hoặc account không còn dùng được |
| `PASSWORD_POLICY_VIOLATION` | 422 | No | Mật khẩu mới vi phạm policy hoặc trùng mật khẩu hiện tại (đổi mật khẩu) |
| `ACCOUNT_LOCKED` | 423 | No | Lần sai chạm ngưỡng, hoặc đăng nhập đúng mật khẩu khi lock chưa hết; kèm `Retry-After` |
| `RATE_LIMITED` | 429 | Yes, after `Retry-After` | Vượt rate limit theo IP/identity; vượt giới hạn gửi lại OTP khi đổi email |
| `SIGNING_KEY_MISSING` | 500 | No | Chưa có signing key `ACTIVE` (fail closed cho tới khi worker tạo key) |
| `DEPENDENCY_UNAVAILABLE` | 500 | Yes | Redis (hoặc phụ thuộc bảo mật khác) không trả lời; contract public chưa định nghĩa `503` |
| `CONCURRENT_UPDATE` | 500 | Yes | Thua compare-and-set 4 lần liên tiếp (AUTH-BR-010); gần như không xảy ra |
| `INTERNAL_ERROR` | 500 | No | Lỗi không mong đợi (không lộ chi tiết) |

## 8. Business examples

Refresh token replay phải thất bại ngay cả khi hai request cùng gửi token; chỉ
một request được tạo token mới. Đổi mật khẩu hoặc xóa account thành công phải khiến
refresh token cũ không thể cấp token tiếp. Một account `LOCKED` không thể login
trước `locked_until` kể cả với mật khẩu đúng, nhưng chỉ người biết mật khẩu mới thấy `423`;
người khác thấy `401` như với email lạ. Một lần đăng nhập đang chạy khi chủ account vừa đổi mật khẩu
không được ghi mật khẩu cũ trở lại, và một lần đăng nhập sai chồng lên lệnh xóa account không được
đưa account trở lại `ACTIVE`.
