---
doc_id: auth-workflows
service: auth
scope: current
status: implemented
owners:
  - Auth Service team
evidence:
  - go/services/auth-service/internal/domain/service
  - go/services/auth-service/internal/workers
  - go/services/auth-service/test/unit/service
  - go/services/auth-service/test/integration/http_flow_test.go
verified_against_commit: null
---

# Auth Service — Workflows

> Các workflow dưới đây khớp code ở `internal/domain/service`. Mã lỗi và HTTP status nằm
> ở [Domain §7](./domain.md#7-domain-errors).

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-AUTH-001` | Register and verify account | API/OTP | Anonymous client | implemented |
| `WF-AUTH-002` | Login and lockout | API | Anonymous client | implemented |
| `WF-AUTH-003` | Refresh-token rotation | API | Client | implemented |
| `WF-AUTH-004` | Logout and session revocation | API | Account owner | implemented |
| `WF-AUTH-005` | Change or reset password | API/email link | Owner/anonymous client | implemented |
| `WF-AUTH-006` | Delete account and hard delete | API/Schedule | Account owner, admin, worker | implemented |
| `WF-AUTH-007` | Change email | API/OTP | Account owner | implemented |
| `WF-AUTH-008` | JWT key rotation and retirement | Schedule | Worker | implemented |
| `WF-AUTH-009` | Resolve notification delivery and contact | Internal API | Notification Service | implemented |
| `WF-AUTH-010` | Unlock expired locks and clean up sessions | Schedule | Worker | implemented |

## WF-AUTH-001 — Register and verify account

**Goal:** tạo account `PENDING_VERIFICATION`, gửi OTP và chuyển thành `ACTIVE`
khi OTP hợp lệ. **Preconditions:** email/identity chưa được dùng và request qua
rate limit. **Happy path:** persist account; store a hashed, TTL-bound OTP (5 phút, tối đa 5
lần thử);
create `delivery_id` (delivery state trong Redis), request Notification qua Kafka;
Notification resolve nội dung qua internal API ([WF-AUTH-009](#wf-auth-009--resolve-notification-delivery-and-contact));
verify OTP atomically; transition account; emit `USER_VERIFIED_SUCCESS`. Duplicate
verify phải không tạo nhiều profile/event: lần hai gặp account không còn
`PENDING_VERIFICATION` nên nhận `409 ACCOUNT_NOT_ACTIVE`. OTP hết hạn, sai hoặc đã dùng trả
`422 OTP_INVALID`, không retry. Vi phạm policy mật khẩu khi đăng ký là `400 VALIDATION_FAILED`
(contract đăng ký không có `422`).

**Gửi lại OTP:** `POST /email-verification-requests` luôn trả `202` để không lộ địa chỉ có
account chờ xác minh hay không. Mỗi account được gửi lại tối đa 3 lần trong 15 phút; vượt ngân
sách này thì bỏ qua âm thầm (không gửi, vẫn `202`) chứ không trả `429`, vì `429` chỉ xuất hiện với
địa chỉ có account chờ xác minh. Throttle quan sát được duy nhất là theo IP. Reliability của
notification/event sau DB commit theo [ADR-005](./decisions/ADR-005-event-publication-reliability.md).

## WF-AUTH-002 — Login and lockout

**Goal:** cấp token pair cho account `ACTIVE` có credential hợp lệ.

1. Rate limit theo IP (router) và theo email chuẩn hóa (`login:account`, 10/5 phút).
2. Tìm account theo email chuẩn hóa. Không có → chạy một lần verify giả cho cùng thời gian xử lý
   rồi trả `401 INVALID_CREDENTIALS`.
3. **Verify mật khẩu trước khi nhìn trạng thái** (Argon2id). Sai mật khẩu luôn là
   `401 INVALID_CREDENTIALS`, bất kể account đang `PENDING_VERIFICATION`, `LOCKED` hay `ACTIVE`,
   nên `403`/`423` không lộ địa chỉ nào đã đăng ký ([ADR-009](./decisions/ADR-009-login-password-before-state.md)).
4. Sai mật khẩu với account `ACTIVE`: tăng `failed_login_count` bền vững trong DB (reset về 0 nếu lần
   sai trước cách đây hơn 1 giờ); chạm ngưỡng (mặc định 5) thì chuyển `LOCKED` 15 phút, phát
   `USER_ACCOUNT_LOCKED` và trả `423 ACCOUNT_LOCKED` kèm `Retry-After`. Sai mật khẩu với account không
   `ACTIVE` (còn lock, chờ xác minh) không đếm và không kéo dài lock. Lock đã hết hạn được lift trước
   khi đếm (xem bước 5).
5. Đúng mật khẩu: lock đã hết hạn thì lift về `ACTIVE` và phát `USER_UNLOCKED` (bên thắng
   compare-and-set phát, một lần); `PENDING_VERIFICATION` → `403 ACCOUNT_NOT_VERIFIED`; lock còn hiệu lực
   → `423 ACCOUNT_LOCKED`; bộ đếm sai được reset. Mọi thay đổi account ở bước 4–5 là compare-and-set
   ([ADR-008](./decisions/ADR-008-account-optimistic-concurrency.md)); nếu mật khẩu đổi trong lúc đang verify thì
   mật khẩu vừa nhập không còn hợp lệ (`401`).
6. Tạo session family mới (refresh token chỉ lưu SHA-256, sliding 7 ngày, tuyệt đối 30 ngày) và ký access
   token RS256 (15 phút). Nếu ký lỗi thì session vừa tạo bị revoke.

`DELETED` không thể đăng nhập (email đã bị xóa). Auth không áp dụng giới hạn số session hoặc thiết bị đăng
nhập của một account. Metrics (kết quả login, lockout, rate-limit rejection) chưa được export; hiện chỉ có
log cấu trúc.

## WF-AUTH-003 — Refresh-token rotation

**Goal:** đổi một refresh token hợp lệ lấy access token mới và rotate refresh
cookie. Browser gửi `auth_refresh` tự động; service hash input, atomically kiểm
tra `expires_at`/`absolute_expires_at`/`revoked_at`, revoke record cũ, tạo record mới cùng
`family_id` trong một câu lệnh SQL, rồi tải account: lock đã hết hạn được lift (và `USER_UNLOCKED`
phát một lần), account không `ACTIVE` thì token vừa tạo bị revoke và trả `401 REFRESH_TOKEN_INVALID`.
Replay, token revoked/hết hạn và hai refresh đồng thời phải không cấp nhiều pair. Không có grace period
hoặc cache để trả lại kết quả cũ. Timeout phía client không cho phép retry mù quáng bằng cùng token; client
cần login lại theo contract. Lỗi `REFRESH_TOKEN_INVALID` xóa cookie.

## WF-AUTH-004 — Logout and session revocation

**Goal:** vô hiệu session hiện tại hoặc session được chọn. Logout một thiết bị revoke
refresh token/session trong DB; nếu session đó là session hiện tại (so `X-User-Session-Id`, hoặc
cookie khi header vắng) thì đặt thêm `auth:revocation:denylist:{jti}` và xóa cookie. TTL
denylist là **access TTL + clock skew (15m30s)**, cận trên của thời gian còn lại của access token
chứ không phải đúng thời gian còn lại, vì Gateway chỉ chuyển tiếp `jti` mà không kèm `exp`
([Data §6](./data.md#6-transaction-concurrency-và-cache)). Logout all (hoặc đổi password, đổi email, xóa account)
revoke refresh session và đặt `auth:revocation:user:{sub}:revoked_before` = giây Unix hiện tại;
Gateway từ chối token có `iat` không mới hơn mốc này. Độ phân giải là một giây, nên token cấp
trong cùng giây với lần thu hồi cũng bị từ chối; đây là chủ ý (không thể phân biệt token cấp ngay trước
hay ngay sau lúc thu hồi, và bên an toàn là từ chối), và vì các thao tác đó đều buộc đăng nhập lại nên
hầu như không ai gặp. Tên key theo
[Gateway runtime contract](../gateway/runtime-contract.md#6-quy-ước-xác-thực);
chi tiết namespace ở [Data Design](./data.md). Operation phải idempotent: một session đã revoked
không được tạo lỗi làm lộ thông tin; session của account khác là `404 SESSION_NOT_FOUND`.

## WF-AUTH-005 — Change or reset password

**Goal:** cập nhật password và bảo vệ account sau sự kiện bảo mật.

**Đổi mật khẩu (`PUT /password`, đã đăng nhập):** kiểm tra format và độ mạnh (khác mật khẩu hiện tại,
không chứa email), verify mật khẩu hiện tại, rồi lưu hash mới bằng compare-and-set có guard "hash đã verify vẫn
là hash hiện tại".

**Reset:** `POST /password-reset-requests` luôn `202`; nếu account `ACTIVE`/`LOCKED` và chưa vượt ngân sách
theo account (5 / 15 phút; vượt thì im lặng, vẫn `202`), tạo reset token opaque (hash, TTL 15 phút) và phát
`PASSWORD_RESET_REQUESTED` chỉ với `delivery_id`; Notification resolve reset URL (token nằm trong fragment) qua
internal API. `POST /password-resets` consume token đúng một lần rồi áp dụng mật khẩu mới; policy mật khẩu vi
phạm là `400` (contract không có `422` cho reset).

**Phần chung sau khi lưu:** nếu account đang `LOCKED` thì lift lock (`ClearLockout`); revoke mọi refresh token
và ghi `revoked_before`; hủy mọi reset token còn lại của account; phát `USER_PASSWORD_CHANGED`. Một yêu cầu reset mới thay thế link cũ của cùng account (chỉ link mới nhất dùng được). Vì thay đổi đã
commit không lặp lại được, event và việc hủy reset token chạy trước khi báo lỗi marker Redis (nếu có)
([ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)). Redis unavailable là fail-closed
(`500 DEPENDENCY_UNAVAILABLE`, retryable).

## WF-AUTH-006 — Delete account and hard delete

**Goal:** xử lý yêu cầu GDPR hoặc admin deletion mà không giữ PII sau khi xóa.
Request được xác thực là account owner (kèm mật khẩu hiện tại) hoặc `ADMIN`. **Trong một transaction
PostgreSQL**, Auth revoke toàn bộ refresh session, chuyển account sang `DELETED`, xóa email/password hash,
đặt `deleted_at`, `hard_delete_after` đúng 90 ngày sau và `deletion_reason` (`GDPR_REQUEST` hoặc
`ADMIN_DELETION`). **Sau commit**, theo thứ tự: phát `USER_DELETED` cho downstream, rồi ghi marker
`revoked_before` vào Redis (Redis không tham gia transaction). Nếu ghi marker lỗi, request trả
`500 DEPENDENCY_UNAVAILABLE` nhưng account đã xóa và event đã phát; retry nhận `409` và không cần làm thêm
([ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)).

`DELETED` là terminal state, không hỗ trợ restore. Compliance worker `account-hard-delete` tìm account
đến hạn theo `idx_accounts_hard_delete` và hard delete record; cascade xóa role và refresh token còn lại.
Workflow idempotent: account đã `DELETED` không làm thay đổi lại PII hoặc kéo dài retention window (lần hai
là `409 ACCOUNT_NOT_ACTIVE`, kể cả khi hai yêu cầu chạy chồng nhau nhờ compare-and-set).

## WF-AUTH-007 — Change email

**Goal:** chỉ đổi email sau khi chủ sở hữu chứng minh biết mật khẩu hiện tại và sở hữu email mới.

1. Client gọi `POST /api/v1/auth/email-change-requests` với `current_password` và `new_email`.
2. Auth kiểm tra identity do Gateway chuyển tiếp, account `ACTIVE`, xác thực mật khẩu hiện tại và kiểm tra email
   mới chưa được sử dụng (kể cả khác email hiện tại).
3. Auth tạo OTP có TTL trong Redis, ràng buộc với `account_id` và `new_email`; tạo `delivery_id`, rồi phát
   `USER_OTP_REQUESTED`. Notification resolve email/OTP qua internal API thay vì đọc secret từ Kafka. Gửi lại tối đa 3
   lần / 15 phút (`429` khi vượt, vì caller đã đăng nhập).
4. Client gọi `POST /api/v1/auth/email-changes/confirmations` với `new_email` và OTP.
5. Auth consume OTP một lần theo thao tác atomic, cập nhật `email` và `email_normalized` (compare-and-set, lost race
   thì áp dụng lại trên bản mới), thu hồi toàn bộ refresh token, cập nhật `revoked_before`; cảnh báo
   `USER_EMAIL_CHANGED` tới email **cũ** được phát trước khi báo lỗi marker Redis nếu có
   ([ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)).
6. Response xóa cookie refresh token. Client đăng nhập lại bằng email mới.

**Xử lý lỗi:** email đã có account trả `409 EMAIL_ALREADY_REGISTERED`; OTP hết hạn, sai hoặc đã dùng trả
`422 OTP_INVALID`. Counter thử/gửi lại OTP nằm ở Redis và tuân thủ giới hạn đã chốt.

## WF-AUTH-008 — JWT key rotation and retirement

**Goal:** đổi key ký mà token đang phát hành vẫn xác minh được. Khi khởi động, Auth tạo key `ACTIVE` đầu tiên
nếu chưa có. Worker `key-rotation` (mỗi giờ, key quá 30 ngày) tạo key mới, chuyển key cũ `ACTIVE` sang
`PHASE_OUT`, rồi publish `{"type":"all"}` lên `auth:jwks:update` (mỗi lần tập key đổi); worker `key-retirement` chỉ retire key
`PHASE_OUT` sau `keys.retirement_delay` (24 giờ, không ngắn hơn access TTL). Gateway phải refresh khi gặp `kid`
mới; Redis Pub/Sub chỉ là optimization. Worker dùng Redis lock, timeout và log kết quả để tránh hai active key
ngoài chính sách (`uq_jwt_keys_one_active`).

## WF-AUTH-009 — Resolve notification delivery and contact

**Goal:** để Notification lấy email và nội dung cần gửi mà không đưa PII hay secret vào Kafka.
`POST /internal/v1/auth/notification-deliveries/{delivery_id}/content` trả email người nhận, template, hạn dùng và
dữ liệu render (OTP hoặc reset URL; `no-store`); idempotent trong TTL 30 phút và **không** consume OTP/reset token.
`delivery_id` không có → `404`, hết TTL → `410`. `GET /internal/v1/auth/users/{user_id}/notification-contact` trả
`email`, `email_verified`, `locale` (xem [Interfaces §3](./interfaces.md#3-event-phát-ra)).
Cả hai chỉ chạy trên listener 8081, qua allowlist `X-Service-Identity` và rate limit theo (caller, tài nguyên).

## WF-AUTH-010 — Unlock expired locks and clean up sessions

Ba worker nền (xem [Operations §5](./operations.md#5-worker-và-scheduled-job)): `auto-unlock-account` (mỗi phút) lift
lock hết hạn và phát `USER_UNLOCKED` nếu mình thắng compare-and-set (login/refresh có thể lift trước, khi đó worker bỏ qua);
`refresh-token-cleanup` (mỗi giờ) xóa refresh token đã hết hạn/revoke quá 7 ngày; `account-hard-delete` (mỗi 6 giờ) thực
thi bước cuối của WF-AUTH-006. Mỗi lượt chạy là singleton nhờ Redis lock và bị giới hạn bởi timeout.

## Required test scenarios

Mỗi workflow cần test happy path, validation boundary, duplicate input/event, timeout, retry, concurrent request,
cache/broker failure và recovery. Phủ hiện có (tên test ở `go/services/auth-service/test`): rotation đồng thời một
winner (`TestConcurrentRotationYieldsOneWinner`), lockout/unlock (`authentication_test.go`, `account_lifecycle_test.go`),
lỗi Redis sau commit (`post_commit_failure_test.go`), ghi đè bản cũ và đua giữa các lần lưu (`account_concurrency_test.go`,
`account_repo_test.go`), không lộ account qua resend/reset/login (`registration_test.go`, `password_test.go`,
`authentication_test.go`), toàn bộ WF-AUTH-001…007 qua HTTP thật (`http_flow_test.go`), listener internal
(`internal_api_test.go`). Chưa có failure-injection test cho Kafka và Redis Pub/Sub.
