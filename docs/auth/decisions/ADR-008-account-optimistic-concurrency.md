---
doc_id: auth-adr-008
service: auth
status: accepted
date: 2026-10-09
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/infrastructure/postgres/account_repo.go
  - go/services/auth-service/internal/domain/service/account_update.go
  - go/services/auth-service/test/unit/service/account_concurrency_test.go
  - go/services/auth-service/test/integration/account_repo_test.go
---

# ADR-008 — Ghi account bằng compare-and-set, tải lại khi thua

## Status

`Accepted`.

## Context

`AccountRepository.Update` ghi lại **cả hàng** `auth.accounts` (mật khẩu, email, trạng thái, bộ đếm lockout…) từ bản
service đã đọc trước đó, không điều kiện. Nhiều luồng đọc account rồi làm việc chậm trước khi lưu: đăng nhập verify Argon2id
(khoảng 100 ms) giữa lúc đọc và lúc lưu. Nếu trong khoảng đó một request khác commit, bản lưu cũ ghi đè nó:

- Đăng nhập (sai hoặc đúng mật khẩu) chồng lên đổi/reset mật khẩu ghi lại `password_hash` cũ: mật khẩu mà chủ tài khoản vừa
  đổi vì nghi bị lộ được khôi phục.
- Đăng nhập sai chồng lên xóa account đưa hàng `DELETED` về `ACTIVE` kèm email, sau khi `USER_DELETED` đã phát: account sống
  lại, PII quay về, còn các service downstream đã xóa.
- Hai lần đăng nhập sai chạy song song cùng đọc `failed_login_count = 0` và cùng ghi `1`, nên đoán mật khẩu song song lọt
  dưới ngưỡng khóa.
- Hai yêu cầu xóa chồng nhau lại đặt `hard_delete_after` mới.

## Decision

Mọi lần ghi `auth.accounts` là compare-and-set trên phiên bản đã đọc:

- `entity.Account.Revision` mang `updated_at` của hàng lúc đọc (hoặc lúc lưu gần nhất). `updated_at` đã được trigger đặt lại ở mọi
  `UPDATE`, nên không cần cột mới.
- `UPDATE ... WHERE id = $1 AND updated_at = $revision RETURNING updated_at`. Không khớp dòng nào thì repository phân biệt hàng
  đã mất (`ErrAccountNotFound`) với hàng đã đổi (`ErrConcurrentUpdate`); thành công thì cập nhật `Revision` của bản đang giữ.
- Service không gọi `Update` trần mà qua `updateAccount(ctx, repo, account, mutate)`: `mutate` biến đổi account và báo có cần lưu
  không; khi thua, hàm tải lại hàng hiện tại và áp dụng lại `mutate` (tối đa 4 lần), rồi trả `CONCURRENT_UPDATE` (retryable).
  `mutate` phải kiểm lại điều kiện của thay đổi trên bản mới.
- Điều kiện đã được xác minh bằng hành động chậm được ràng bằng guard: đổi mật khẩu và xóa account của chủ sở hữu chỉ lưu khi hash đã verify
  vẫn là hash hiện tại; đăng nhập đúng mật khẩu cũng vậy (mật khẩu vừa nhập không còn hợp lệ nếu đã đổi).
- Lỗi này gồm cả ba luồng mà OTP/token đã bị consume trước khi lưu (xác minh đăng ký, xác nhận đổi email, hoàn tất reset): chúng
  áp dụng lại thay vì thất bại, vì không lặp lại được.

Ngoài phạm vi: bảng `refresh_tokens` (đã có CAS riêng trong câu lệnh rotation), `jwt_keys` (ràng buộc `uq_jwt_keys_one_active`).

## Alternatives considered

- Cột `version` riêng: rõ ràng hơn nhưng cần migration và đồng bộ ở mọi nơi ghi; `updated_at` do trigger đã đủ (trigger đặt `NOW()` ở mọi
  `UPDATE`, nên hai commit khác nhau không có cùng giá trị trừ khi trùng tới micro giây).
- `SELECT ... FOR UPDATE` quanh cả luồng đọc-xác minh-ghi: chặn đúng nhưng giữ row lock suốt Argon2id (100 ms) và gọi Redis, làm
  nghẽn mọi request cùng account (kể cả kẻ tấn công cố tình spam một email để chặn chủ account đăng nhập).
- UPDATE nhỏ theo từng trường (tăng bộ đếm bằng SQL): đúng cho bộ đếm nhưng nhân đôi luật nghiệp vụ vào SQL và không bảo vệ các
  thay đổi nhiều trường (đổi mật khẩu cùng `ClearLockout`).
- Giữ nguyên ghi cả hàng: đơn giản nhưng giữ nguyên các lỗi ở Context.

## Consequences

- Một lần đăng nhập đúng ngay lúc mật khẩu bị đổi nhận `401 INVALID_CREDENTIALS` (như thể nó đến sau khi đổi). Đăng nhập sai không
  còn làm mất lần đếm của nhau và không thể làm account đã xóa sống lại.
- Thêm tối đa 3 lần đọc lại + ghi lại khi có đua; đường bình thường không tốn thêm truy vấn (chỉ thêm `AND updated_at = ...` và
  `RETURNING`).
- `Create` cũng trả về `Revision` của hàng mới để bản đó lưu được ngay.
- Ràng buộc kiến trúc mới: không thêm đường ghi cả hàng không kiểm phiên bản.

## Migration impact

Không đổi schema hay contract. Port `AccountRepository.Update` đổi hợp đồng (thêm `ErrConcurrentUpdate`); thêm mã `CONCURRENT_UPDATE`
(HTTP 500, retryable) vào error catalog vì contract public không có `409` cho các endpoint bị ảnh hưởng.

## Verification

`account_concurrency_test.go` (repository giả có compare-and-set và hook đua): mật khẩu không bị khôi phục, account đã xóa không sống
lại, đăng nhập sai chồng nhau đều được đếm, đổi mật khẩu/xác minh/đổi email còn thành công sau một lần thua, từ bỏ sau 4 lần.
`account_repo_test.go` (PostgreSQL thật): bản cũ không ghi được, không hồi sinh account đã xóa, `Revision` tiến lên và bản đã lưu lưu
lại được. Kiểm tra đột biến: bỏ compare-and-set khỏi bản giả làm 10 test đua fail.
