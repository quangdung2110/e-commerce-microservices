---
doc_id: auth-adr-006
service: auth
status: accepted
date: 2026-10-09
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/domain/service/account_deletion_service.go
  - go/services/auth-service/internal/domain/service/email_change_service.go
  - go/services/auth-service/internal/domain/service/password_service.go
  - go/services/auth-service/test/unit/service/post_commit_failure_test.go
---

# ADR-006 — Phát event trước khi ghi revocation marker

## Status

`Accepted`. Bổ sung cho [ADR-005](./ADR-005-event-publication-reliability.md) (không thay
thế): ADR-005 vẫn quy định direct publish, retry có giới hạn và reconciliation; ADR này
chốt thứ tự các bước sau commit.

## Context

Xóa account, đổi email và đổi/reset mật khẩu đều commit PostgreSQL trước rồi mới làm hai
việc sau commit: ghi marker `revoked_before` vào Redis (Redis không tham gia transaction)
và phát event. Bản đầu gọi marker trước và `return err` ngay khi Redis lỗi, nên event không
bao giờ được phát. Các thao tác này không thể lặp lại:

- Xóa account: hàng đã bị anonymize, request thứ hai nhận `409 ACCOUNT_NOT_ACTIVE`. Không
  còn đường nào phát `USER_DELETED`, nên User, Notification, Review, Chat và Cart giữ PII
  của người đã yêu cầu xóa. Không có reconciliation nào phát lại.
- Đổi email: OTP đã bị consume, request thứ hai nhận `OTP_INVALID`. Chủ email cũ không bao
  giờ nhận cảnh báo `USER_EMAIL_CHANGED`.
- Đổi/reset mật khẩu: mật khẩu cũ (hoặc reset token) không còn dùng được. Reset token còn
  lại trong Redis cũng không được hủy, và `USER_PASSWORD_CHANGED` không được phát.

## Decision

Sau khi transaction PostgreSQL commit, Auth luôn hoàn tất mọi việc phải đi kèm thay đổi đã
commit **trước** khi báo lỗi marker cho caller:

1. Phát event (`USER_DELETED`, `USER_EMAIL_CHANGED`, `USER_PASSWORD_CHANGED`).
2. Hủy reset token còn tồn tại (đổi/reset mật khẩu).
3. Trả lỗi của bước thu hồi (marker Redis) nếu có.

Lỗi marker vẫn được trả cho caller (`DEPENDENCY_UNAVAILABLE`, HTTP 500, `retryable`),
giữ nguyên nguyên tắc fail-closed của [ADR-001](./ADR-001-dual-token-revocation.md) và
[ADR-004](./ADR-004-redis-security-state.md): Auth không báo thành công cho một lần thu hồi
chưa chắc có hiệu lực. Việc thu hồi vẫn nằm trong DB (refresh token đã `revoked_at`); chỉ
access token đã phát còn sống tới khi hết hạn (tối đa access TTL, 15 phút).

Ngoài phạm vi: Kafka lỗi sau khi retry cạn vẫn có thể làm mất event (ADR-005); ADR này chỉ
loại bỏ nguyên nhân mất event do Redis.

## Alternatives considered

- Giữ nguyên thứ tự (marker trước, event sau): đơn giản nhưng một lỗi Redis tạm thời gây mất
  event vĩnh viễn như mô tả ở Context.
- Trả thành công khi marker lỗi, chỉ ghi log: client không phải nhận 500 cho thay đổi đã
  commit, nhưng báo thành công cho một lần thu hồi chưa chắc có hiệu lực, trái fail-closed.
- Ghi marker trước khi commit: Redis lỗi thì dừng sạch trước khi đổi gì, nhưng mở một cửa sổ
  ngắn trong đó token cấp giữa lúc ghi marker và lúc commit không bị thu hồi (đặc biệt khi
  đăng nhập bằng mật khẩu cũ), và marker tồn tại dù commit thất bại.
- Chuyển sang transactional outbox: đóng hẳn khoảng trống dual write nhưng cần ADR thay
  ADR-005, thêm bảng và worker; chưa cần thiết khi nguyên nhân mất event chỉ là thứ tự.

## Consequences

- Một lần xóa account hoặc đổi mật khẩu có thể trả 500 dù thay đổi đã commit và event đã
  phát. Client không được coi 500 là "chưa làm gì": với đổi mật khẩu/email có thể gọi
  `DELETE /api/v1/auth/sessions` (logout all, idempotent) để ghi lại marker; với xóa account
  retry nhận `409` và không cần làm thêm gì.
- Consumer có thể nhận `USER_DELETED` khi marker chưa ghi; điều này vô hại vì consumer chỉ
  dọn dữ liệu và đã idempotent theo `user_id`.
- Log `error` "could not write the revocation marker" là tín hiệu cần alert: access token
  của tài khoản đó còn sống tới 15 phút.

## Migration impact

Chỉ đổi thứ tự trong `account_deletion_service.go`, `email_change_service.go` và
`password_service.go`. Không đổi contract HTTP/event, schema hay config. Cập nhật
[WF-AUTH-005/006/007](../workflows.md).

## Verification

`test/unit/service/post_commit_failure_test.go`: với `revocation.failWith` được đặt, mỗi
luồng vẫn commit thay đổi, vẫn phát event tương ứng (và hủy reset token), và vẫn trả
`DEPENDENCY_UNAVAILABLE`.
