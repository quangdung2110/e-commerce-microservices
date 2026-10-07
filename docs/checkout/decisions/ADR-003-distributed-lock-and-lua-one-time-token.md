---
doc_id: checkout-adr-003
service: checkout
status: superseded
superseded_by: checkout-adr-011
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/data.md
  - docs/checkout/architecture.md
---

# ADR-003 — Distributed lock kết hợp Lua one-time-use; thứ tự lock → hash check → Lua

## Trạng thái

`Superseded` bởi [ADR-011](./ADR-011-token-keyed-session-and-recovery.md) ngày 2026-09-20; nội dung dưới đây là lịch sử, không phải contract hiện hành. Trước đó: `Accepted` theo thiết kế cũ, chưa có implementation evidence.

## Bối cảnh

Submit phải bảo đảm mỗi user chỉ tạo đúng một order cho một checkout session, kể
cả khi user double-click, mở hai tab, hoặc client retry sau slow network. Preview
cũng cần chống bot mở đồng thời nhiều luồng với nhiều mã voucher gây race ghi đè
`pricing_hash` trên session. Đồng thời, việc kiểm tra giá stale ở Submit cần gọi
gRPC sang Product — thao tác tốn tài nguyên nếu bị lặp bởi nhiều request đồng
thời.

## Quyết định

- **Preview lock** `checkout:lock:preview:{user_id}` (`SETNX` + `EXPIRE`, TTL 5
  giây) cùng rate limit 10 req/phút: chỉ một luồng Preview/user tại một thời điểm.
- **Submit lock** `checkout:lock:submit:{user_id}` (TTL 30 giây) cùng rate limit
  3 req/phút; xin lock thất bại → `423 Locked`.
- **Lua check-and-mark** trên `checkout:session:{user_id}`: check token, check
  `status == PREVIEW_READY`, `HSET status SUBMITTED` trong một script atomic; trả
  `1`/`-1 INVALID_TOKEN`/`-2 ALREADY_SUBMITTED`. Hai lệnh `HGET` rồi `HSET` rời
  nhau có thể cùng pass → duplicate order, nên bắt buộc Lua.
- **Dùng cả lock lẫn Lua (belt and suspenders):** Lua bảo đảm one-time-use; lock
  chặn duplicate/concurrent/slow-retry và là fallback khi Redis Lua fail; lock
  cũng bao bọc Price Staleness Check để không có request chèn vào khi đang check
  giá.
- **Tách lock khỏi trường `status`:** luồng là xin lock → gọi gRPC Product lấy giá
  → chạy Lua. Không có lock, nhiều request đồng thời sẽ tốn tài nguyên chạy nhiều
  lần gRPC dù Lua đã chặn. Tách rời giúp khi phát hiện giá đổi chỉ cần nhả lock
  và báo lỗi, session vẫn `PREVIEW_READY`, không phải chuyển sang `SUBMITTED`
  rồi rollback — tránh phức tạp và rủi ro của rollback trạng thái.
- **Thứ tự bắt buộc:** lock → status/degraded check → hash check (Read Validator)
  → Lua (Write Committer) → lưu payload → gọi Order → cache response → `DEL`
  lock. Nhờ tách bạch Read Validator và Write Committer, hệ thống không “treo
  phiên” kể cả khi mạng nội bộ chập chờn.
- **Không rollback** `SUBMITTED` trong mọi trường hợp (conflict, timeout).

## Phương án đã cân nhắc

- **Chỉ Lua:** đủ cho one-time-use nhưng không chặn gRPC lặp và không có fallback
  khi Lua fail; không chọn làm lớp duy nhất.
- **Chỉ lock:** không atomic với status; lock hết TTL giữa chừng vẫn có thể
  duplicate; không chọn.
- **Chuyển `SUBMITTED` trước rồi check giá, rollback nếu lệch:** phải rollback
  trạng thái, phức tạp và rủi ro; không chọn.
- **Lock + Lua theo thứ tự lock → hash → Lua (chọn).**

## Hệ quả

- Tích cực: mỗi user đúng một order cho một session; hash lệch không làm hỏng
  session; không rollback.
- Tiêu cực: mọi Submit chạm Redis nhiều lần; lock TTL 30 giây có thể chặn user
  hợp lệ nếu lock không được `DEL` sau exception cho tới khi TTL hết; Lua fail chỉ
  còn lock bảo vệ.
- Constraint: mọi nhánh thoát khỏi Submit đều phải nhả lock; không đổi thứ tự
  các bước.

## Ảnh hưởng migration

Chưa có code. Frontend phải xử lý `423`/`429` bằng cách chờ, không spam.

## Xác minh

Khi có code: concurrency test hai submit đồng thời chỉ một order; hash lệch → Lua
không chạy, status vẫn `PREVIEW_READY`, lock được nhả; Lua trả `-1`/`-2`; lock
TTL tự hết; Redis Lua fail nhưng lock vẫn chặn.
