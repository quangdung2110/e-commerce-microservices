---
doc_id: auth-adr-009
service: auth
status: accepted
date: 2026-10-09
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/domain/service/authentication_service.go
  - go/services/auth-service/internal/domain/service/session_service.go
  - go/services/auth-service/internal/domain/service/maintenance_service.go
  - go/services/auth-service/test/unit/service/authentication_test.go
  - go/services/auth-service/test/integration/http_flow_test.go
---

# ADR-009 — Đăng nhập kiểm mật khẩu trước trạng thái; unlock inline phát `USER_UNLOCKED`

## Status

`Accepted`.

## Context

Hai điểm của luồng đăng nhập trái ý định đã ghi:

1. Bản đầu gọi `EnsureCanAuthenticate` trước khi verify mật khẩu. Ai cũng gửi được `POST /sessions` với một email bất kỳ và mật
   khẩu bất kỳ và biết email đó `PENDING_VERIFICATION` (`403`) hoặc đang `LOCKED` (`423`) mà không cần biết mật khẩu, trong khi
   email không tồn tại, `ACTIVE` hay sai mật khẩu đều là `401`. Đó là kênh liệt kê tài khoản, trái nguyên tắc "không lộ account"
   của các endpoint công khai khác (resend OTP, reset).
2. Login và refresh tự lift lock đã hết hạn nhưng cố tình không phát `USER_UNLOCKED` ("worker owns that event"). Nhưng sau khi
   login đã chuyển account về `ACTIVE`, worker `auto-unlock-account` (`ListExpiredLocks`) không còn thấy nó nên thông báo "tài
   khoản đã mở khóa" không bao giờ được gửi.

## Decision

- **Verify mật khẩu trước, rồi mới xét trạng thái.** Sai mật khẩu luôn là `401 INVALID_CREDENTIALS` với mọi trạng thái; sai mật khẩu với
  account không `ACTIVE` không đếm và không kéo dài lock. Đúng mật khẩu mới phân biệt: `PENDING_VERIFICATION` → `403
  ACCOUNT_NOT_VERIFIED`, lock còn hiệu lực → `423 ACCOUNT_LOCKED`. Lần sai chạm ngưỡng vẫn trả `423` kèm `Retry-After` và phát
  `USER_ACCOUNT_LOCKED` (đã nằm trong contract; người đoán mật khẩu 5 lần là người duy nhất thấy nó).
- **Bên nào ghi `LOCKED → ACTIVE` thì phát `USER_UNLOCKED`**, dù là worker, login hay refresh. Ghi account là compare-and-set
  ([ADR-008](./ADR-008-account-optimistic-concurrency.md)) nên đúng một bên thắng và thông báo đi ra một lần.

## Alternatives considered

- Giữ thứ tự cũ và coi `403`/`423` là chấp nhận được: thân thiện với người dùng hơn một chút (biết ngay vì sao không đăng nhập
  được) nhưng cho phép liệt kê email đã đăng ký từ bên ngoài, không cần mật khẩu.
- Trả `401` ở cả `403`/`423` kể cả khi đúng mật khẩu: kín nhất nhưng làm hỏng UX (chủ tài khoản không biết cần xác minh email hay chờ mở
  khóa) và đổi contract.
- Để worker luôn là bên duy nhất unlock (login chỉ từ chối cho tới khi worker chạy): thêm tới một phút chờ vô ích sau khi lock hết hạn.

## Consequences

- Một lần đăng nhập sai vào account `PENDING`/`LOCKED` tốn một lần verify Argon2 (như account khác), không còn trả lời sớm; không còn
  khác biệt thời gian giữa các trạng thái.
- Người biết mật khẩu nhưng chưa xác minh email vẫn thấy `403`; email lạ và mật khẩu sai không thấy gì khác nhau.
- Lần sai chạm ngưỡng trả `423` vẫn cho biết email đó có account với người đoán đủ 5 lần; đây là đánh đổi đã nằm trong contract
  (chủ tài khoản cần biết mình bị khóa), được giới hạn bởi rate limit theo IP và theo email.
- `SessionService` nhận thêm `Notifier` (refresh cũng phát `USER_UNLOCKED`).

## Migration impact

Không đổi schema. Hành vi HTTP: sai mật khẩu vào account `PENDING`/`LOCKED` đổi từ `403`/`423` sang `401`; đúng mật khẩu giữ nguyên.
Consumer `USER_UNLOCKED` (Notification) nhận event mà trước đây có thể không bao giờ tới.

## Verification

`authentication_test.go`: sai mật khẩu với account pending/locked là `401` và không đổi bộ đếm hay `locked_until`; login sau khi lock hết
hạn phát `USER_UNLOCKED` đúng một lần và worker không phát lại. `account_concurrency_test.go`: refresh unlock có thông báo, worker thua
login thì im lặng. `http_flow_test.go`: hai assertion `401` qua HTTP thật.
