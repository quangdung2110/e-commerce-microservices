---
doc_id: payment-adr-006
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/architecture.md
  - docs/payment/data.md
---

# ADR-006 — Optimistic lock mặc định cho transition, Redis lock cho side effect, có fallback khi Redis sập

## Trạng thái

`Accepted` trong thiết kế; chưa xác minh implementation. Ngày quyết định gốc không
được ghi lại.

## Bối cảnh

Payment cần xử lý race condition và idempotency mạnh: webhook Stripe có thể ghi
đè nhau, frontend và webhook có thể tác động cùng transaction, cronjob nhiều
instance có thể quét trùng, và lệnh gọi Stripe (tạo PaymentIntent/Refund) là side
effect ngoài không rollback được qua DB. Cần chọn giữa lock DB, lock Redis, hoặc
kết hợp — và hành vi khi Redis không khả dụng.

## Quyết định

Chiến lược kết hợp:

- **Optimistic Locking (cột `version`, JPA `@Version`)** là default guard cho
  **tất cả** state transition (ví dụ webhook Stripe ghi đè nhau). Không tốn
  overhead gọi sang Redis.
- **Distributed Lock (Redis Redisson)** được ưu tiên cho tác vụ có side effect
  ngoài không thể rollback qua DB: gọi Stripe tạo PaymentIntent/Refund, cronjob
  quét transaction. Naming `lock:payment:{action}:{txn_id}` (ví dụ
  `lock:payment:refund:txn_123`, `lock:payment:initiate:order_10001`,
  `lock:payment:refund_call:{txn_id}`, `lock:payment:refund_poll:{refund_transaction_id}`).
  TTL 15–30s để tránh lock treo vĩnh viễn khi worker crash.
- **Idempotency:** Redis `SETNX payment:initiate:{order_id}:{attempt_number}` (TTL 15 phút + 60s)
  cho initiate; `Idempotency-Key` header cho refund check trên Redis và DB
  `refund_transactions.idempotency_key` UNIQUE; bảng `payment_idempotency`
  `PRIMARY KEY (key, action)` lưu response.
- **Redis degradation/fallback:** API read/reconciliation không bị ảnh hưởng;
  Idempotency API (initiate/refund) fallback xuống insert + check unique constraint
  ở DB (`payment_idempotency`, `refund_transactions`); worker không lấy được lock
  thì **reject operation và đẩy vào retry cycle/DLQ** để bảo vệ tính toàn vẹn
  thay vì ignore lock.

## Phương án đã cân nhắc

- **Chỉ Redis lock cho mọi transition:** overhead mỗi transition và Redis trở
  thành single point of failure cho correctness. Không chọn.
- **Chỉ optimistic lock:** không ngăn được gọi Stripe hai lần song song (side
  effect đã xảy ra trước khi DB conflict). Không chọn.
- **Pessimistic row lock:** giữ lock lâu trong khi gọi Stripe. Không chọn.
- **Kết hợp có fallback:** DB là safety cuối; Redis chỉ giảm side effect trùng.
  Được chọn.

## Hệ quả

Tích cực: correctness không phụ thuộc Redis; chi phí thấp cho transition thường;
side effect Stripe được bảo vệ. Tiêu cực: hai cơ chế cần hiểu đúng; lock TTL phải
lớn hơn Stripe latency; conflict optimistic lock cần requeue/retry ở consumer;
Redis sập làm worker chậm (retry/DLQ) chứ không sai.

## Ảnh hưởng migration

Cần cột `version` trên bảng transaction (DB design cũ chưa khai báo — xem
`README.md` §9), Redisson client, `payment_idempotency` với PK `(key, action)`.

## Xác minh

Cần entity `@Version`, Redisson lock wrapper với naming/TTL, test hai webhook đồng
thời, test Redis outage với initiate/refund fallback DB, test worker reject khi
không lấy được lock, metric lock conflict rate.
