---
doc_id: payment-adr-017
service: payment
status: accepted
date: 2026-09-22
decision_owners:
  - Payment Service team
refines:
  - payment-adr-006
  - payment-adr-012
evidence:
  - docs/payment/data.md
  - docs/payment/workflows.md
---

# ADR-017 — Command lease có fencing và giới hạn replay idempotency phía Stripe

## Trạng thái

`Accepted` — 2026-09-22.

## Bối cảnh

`payment_idempotency.IN_PROGRESS` cũ không lưu execution owner/lease nên DB fallback
không phân biệt request còn chạy với process đã chết. Provider key cũng không phải
cam kết vô thời hạn: create/refund replay quá muộn có thể tạo side effect mới nếu
Stripe đã prune key.

## Quyết định

1. Mỗi command slot `(action, idempotency_key)` có `lease_owner`, `lease_token`
   monotonic, `lease_expires_at`, `last_heartbeat_at`. Claim/takeover là một atomic
   SQL update. TX2 chỉ complete khi token còn khớp; stale owner không được commit.
2. Redis chỉ là fast pointer. PostgreSQL lease + unique constraints là authority khi
   Redis mất. `REQUEST_IN_PROGRESS` chỉ trả khi lease DB còn hiệu lực.
3. TX1 tạo/claim command, aggregate và ProviderOperation atomically. Sau crash, owner
   mới đọc operation hiện hữu; không mint resource/key mới.
   `provider_operations` có lease độc lập `lease_owner`, `lease_token`,
   `lease_expires_at`, `last_heartbeat_at`; operation do scheduler tạo không phụ thuộc
   vào sự tồn tại của command slot trong `payment_idempotency`.
4. Mỗi operation lưu `first_dispatched_at`, `last_dispatched_at`,
   `provider_request_id`, `safe_replay_until = first_dispatched_at + 23h`.
5. Trong `safe_replay_until`, recovery được replay đúng endpoint với cùng key và
   fingerprint. Sau mốc này:
   - `CREATE`: search PaymentIntent theo immutable `payment_transaction_id` metadata;
   - `REFUND`: list/retrieve refunds của đúng PaymentIntent rồi match
     `refund_transaction_id`/`refund_request_id` metadata;
   - `CANCEL`: retrieve PaymentIntent trước; canceled/succeeded được bind trực tiếp,
     chỉ gọi cancel nếu object hiện vẫn cancelable.
6. Sau horizon, đúng một object khớp thì bind; zero hoặc nhiều object đều chuyển
   `MANUAL_REVIEW_REQUIRED`. **Cấm blind create/refund lại.** Không có object không
   phải bằng chứng rằng request cũ chưa từng được thực thi.
7. Recovery dừng tự động ở `recovery_deadline_at` hoặc `max_attempts`, mở review case;
   không để operation `REQUESTED/UNKNOWN` vô hạn. Với CREATE,
   `recovery_deadline_at = payment.expires_at + 24h`.
8. Provider idempotency key có namespace Payment quản lý:
   `pay:create:{transaction_id}`, `pay:cancel:{transaction_id}`,
   `pay:refund:{refund_transaction_id}`. Raw caller key không truyền trực tiếp sang
   Stripe; command key chỉ dùng trace/fingerprint.
9. Nếu Stripe xác định từ chối CREATE, operation chuyển `PROVIDER_REJECTED`; Payment
   không bịa transition `INITIATING → FAILED`, mà giữ `INITIATING`, set
   `requires_manual_review`, mở case `PROVIDER_CREATE_REJECTED` và complete command
   bằng stored `503 PROVIDER_REQUEST_REJECTED`, `retryable=false`. Replay không gọi
   provider lại.

## Hệ quả

Command concurrency vẫn đúng khi Redis outage và stale process không thể complete.
Recovery muộn fail closed thay vì có nguy cơ charge/refund kép. Refund vẫn đúng một
operation trọn đời/order.

## Xác minh

Test concurrent claim, lease expiry/takeover, stale-token TX2, crash tại mọi điểm
TX1/call/TX2, Redis outage, replay trước horizon, recovery sau horizon với zero/one/
multiple candidates, và khẳng định không có provider create/refund thứ hai.
