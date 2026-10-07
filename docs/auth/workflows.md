---
doc_id: auth-workflows
service: auth
scope: target
status: proposed
owners:
  - Auth Service team
evidence: []
verified_against_commit: null
---

# Auth Service — Workflows

> Các workflow dưới đây là thiết kế mục tiêu. Contract chi tiết và bằng chứng
> implementation được bổ sung trước khi đánh dấu `verified`.

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-AUTH-001` | Register and verify account | API/OTP | Anonymous client | proposed |
| `WF-AUTH-002` | Login and lockout | API | Anonymous client | proposed |
| `WF-AUTH-003` | Refresh-token rotation | API | Client | proposed |
| `WF-AUTH-004` | Logout and session revocation | API | Account owner | proposed |
| `WF-AUTH-005` | Change or reset password | API/email link | Owner/anonymous client | proposed |
| `WF-AUTH-006` | Delete account and hard delete | API/Schedule | Account owner, admin, worker | proposed |
| `WF-AUTH-007` | Change email | API/OTP | Account owner | proposed |
| `WF-AUTH-008` | JWT key rotation and retirement | Schedule | Worker | proposed |

## WF-AUTH-001 — Register and verify account

**Goal:** tạo account `PENDING_VERIFICATION`, gửi OTP và chuyển thành `ACTIVE`
khi OTP hợp lệ. **Preconditions:** email/identity chưa được dùng và request qua
rate limit. **Happy path:** persist account; store a hashed, TTL-bound OTP;
create `delivery_id`, request Notification qua Kafka; Notification resolve nội dung
qua mTLS internal API; verify OTP atomically; transition account; emit
`USER_VERIFIED_SUCCESS`. Duplicate verify phải không tạo nhiều profile/event.
OTP hết hạn, sai hoặc đã dùng trả lỗi không retry. Reliability của notification/
event sau DB commit là gap được theo dõi tại ADR-005.

## WF-AUTH-002 — Login and lockout

**Goal:** cấp token pair cho account `ACTIVE` có credential hợp lệ. Service kiểm
tra rate limit và account state, verify password hash, tạo access JWT và opaque
refresh token hash trong một session family. Login sai tăng counter theo email
và IP; vượt threshold chuyển account sang `LOCKED` và phát
`USER_ACCOUNT_LOCKED`. Login thành công reset counter. `LOCKED`, `DELETED` và
`PENDING_VERIFICATION` không được nhận token. Metrics cần có kết quả login,
lockout và rate-limit rejection. Auth không áp dụng giới hạn số session hoặc thiết
bị đăng nhập của một account.

## WF-AUTH-003 — Refresh-token rotation

**Goal:** đổi một refresh token hợp lệ lấy access token mới và rotate refresh
cookie. Browser gửi `auth_refresh` tự động; service hash input, atomically kiểm
tra `expires_at`/`revoked_at`, revoke record cũ, tạo record mới cùng `family_id`,
ký access token và trả pair. Replay, token revoked/hết hạn và hai refresh đồng
thời phải không cấp nhiều pair. Không có grace period hoặc cache để trả lại kết
quả cũ. Timeout phía client không cho phép retry mù quáng bằng cùng token; client
cần login lại theo contract.

## WF-AUTH-004 — Logout and session revocation

**Goal:** vô hiệu session hiện tại hoặc session được chọn. Logout một thiết bị
revoke refresh token/session và đặt `auth:revocation:denylist:{jti}` với TTL
không vượt quá thời gian còn lại của access token. Logout all hoặc đổi password
revoke refresh session và đặt `auth:revocation:user:{sub}:revoked_before`;
Gateway từ chối token có `iat` không mới hơn mốc này. Tên key theo
[Gateway runtime contract](../gateway/runtime-contract.md#6-quy-ước-xác-thực);
chi tiết namespace ở [Data Design](./data.md). Operation phải idempotent: một session đã revoked
không được tạo lỗi làm lộ thông tin.

## WF-AUTH-005 — Change or reset password

**Goal:** cập nhật password và bảo vệ account sau sự kiện bảo mật. Change password
yêu cầu identity hiện tại; reset password dùng secret hash có TTL. Completion
phải dùng reset token đúng một lần, cập nhật password, xóa token/challenge và
revoke phiên cũ theo policy. Phát `PASSWORD_RESET_REQUESTED` khi cần thông báo
reset; event chỉ có `delivery_id`, Notification resolve opaque reset URL qua mTLS
internal API. Phát `USER_PASSWORD_CHANGED` sau thay đổi thành công. Redis unavailable phải
có hành vi fail-closed trừ khi ADR nêu rõ khác.

## WF-AUTH-006 — Delete account and hard delete

**Goal:** xử lý yêu cầu GDPR hoặc admin deletion mà không giữ PII sau khi xóa.
Request được xác thực là account owner hoặc `ADMIN`. Trong transaction, Auth
revoke toàn bộ refresh session, đặt revoke marker, chuyển account sang `DELETED`,
xóa email/password hash, đặt `deleted_at`, `hard_delete_after` đúng 90 ngày sau
và `deletion_reason`. Sau commit, service phát `USER_DELETED` cho downstream.

`DELETED` là terminal state, không hỗ trợ restore. Compliance worker tìm account
đến hạn theo `idx_accounts_hard_delete` và hard delete record; cascade xóa role
và refresh token còn lại. Workflow phải idempotent: account đã `DELETED` không
được làm thay đổi lại PII hoặc kéo dài retention window.

## WF-AUTH-007 — Change email

**Goal:** chỉ đổi email sau khi chủ sở hữu chứng minh biết mật khẩu hiện tại và sở hữu email mới.

1. Client gọi `POST /api/v1/auth/email-change-requests` với `current_password` và `new_email`.
2. Auth kiểm tra access token do Gateway chuyển tiếp, xác thực mật khẩu hiện tại và kiểm tra email mới chưa được sử dụng.
3. Auth tạo OTP có TTL trong Redis, ràng buộc với `account_id` và `new_email`; tạo `delivery_id`, rồi phát `USER_OTP_REQUESTED`. Notification resolve email/OTP qua mTLS internal API thay vì đọc secret từ Kafka.
4. Client gọi `POST /api/v1/auth/email-changes/confirmations` với `new_email` và OTP.
5. Auth consume OTP một lần theo thao tác atomic, cập nhật `email` và `email_normalized`, thu hồi toàn bộ refresh token, cập nhật `revoked_before`, rồi phát `USER_EMAIL_CHANGED`.
6. Response xóa cookie refresh token. Client đăng nhập lại bằng email mới.

**Xử lý lỗi:** email đã có account trả `409 EMAIL_ALREADY_REGISTERED`; OTP hết hạn, sai hoặc đã dùng trả lỗi domain tương ứng. Counter thử/gửi lại OTP nằm ở Redis và tuân thủ giới hạn đã chốt.

## WF-AUTH-008 — JWT key rotation and retirement

**Goal:** đổi key ký mà token đang phát hành vẫn xác minh được. Worker tạo key
mới, chuyển key cũ `ACTIVE` sang `PHASE_OUT`, publish/invalidate JWKS, rồi chỉ
retire key cũ sau maximum access-token lifetime. Gateway phải refresh khi gặp
`kid` mới; Redis Pub/Sub chỉ là optimization. Worker dùng lock, idempotency,
timeout/retry và metric/alert để tránh hai active key ngoài chính sách.

## Required test scenarios

Mỗi workflow cần test happy path, validation boundary, duplicate input/event,
timeout, retry, concurrent request, cache/broker failure và recovery. Các test
và implementation evidence hiện chưa được xác minh.
