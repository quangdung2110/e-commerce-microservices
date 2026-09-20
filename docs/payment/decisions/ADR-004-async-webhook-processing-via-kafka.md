---
doc_id: payment-adr-004
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/workflows.md
  - docs/payment/architecture.md
---

# ADR-004 — Webhook trả 200 ngay, xử lý bất đồng bộ qua `t.payment.webhooks.raw`

## Trạng thái

`Accepted` trong thiết kế; chưa xác minh implementation. Ngày quyết định gốc không
được ghi lại.

## Bối cảnh

Stripe gọi webhook sau khi user thanh toán/hủy. Stripe retry nếu response > 5s
(3–5 lần với exponential backoff), gây duplicate processing nếu xử lý đồng bộ
chậm. Nếu DB down đúng lúc webhook đến và endpoint xử lý trực tiếp, event có thể
mất vĩnh viễn. Webhook có thể duplicate, delay, out-of-order.

## Quyết định

Endpoint `POST /v1/payments/webhook/stripe` **không xử lý business logic**: nhận raw
body + headers, trả `200 OK` trong < 500ms sau Kafka ack, dump raw webhook vào topic
nội bộ `t.payment.webhooks.raw`. Key là `STRIPE:<candidate_event_id>` khi best-effort
extraction thành công, nếu không là `RAW:<sha256(raw_body)>`; value gồm `provider`,
`candidate_event_id?`, `raw_body`, `stripe_signature`, `received_at`. Candidate chưa
được tin cậy và chỉ trở thành `stripe_event_id` sau khi worker verify/parse thành công.

Webhook Processor Worker consume bất đồng bộ: verify `Stripe-Signature`
(HMAC-SHA256, cửa sổ 300s) → map Stripe event → FSM transition → dedup bằng
`INSERT INTO webhook_logs ... ON CONFLICT (provider, provider_transaction_id,
event_type) DO NOTHING` + check `stripe_event_id` → update DB (optimistic lock
`version`) + insert outbox trong một ACID transaction.

Quy tắc bắt buộc: endpoint trả 2xx nhanh; worker an toàn với duplicate/out-of-order
nhờ idempotency và DB unique constraint; lỗi tạm thời (lock DB, Stripe API
timeout) retry theo consumer/requeue có exponential backoff. Webhook
`FAILED_SIGNATURE`/`UNPROCESSED` được Auto-Retry Worker đẩy lại Kafka có backoff;
DLQ có Replay Worker thủ công.

## Phương án đã cân nhắc

- **Xử lý đồng bộ tại endpoint:** đơn giản nhưng response chậm gây Stripe retry
  (duplicate) và DB down làm mất event. Không chọn.
- **Ghi DB rồi xử lý sau (DB queue):** vẫn phụ thuộc DB availability tại thời
  điểm nhận. Không chọn.
- **Kafka buffer + worker idempotent:** Kafka at-least-once + idempotency worker →
  không mất, không trùng. Được chọn.

## Hệ quả

Tích cực: không mất event khi DB down; endpoint nhanh, Stripe không retry vô ích;
worker scale theo partition. Tiêu cực: thêm topic nội bộ và consumer; độ trễ xử
lý (SLO gợi ý 99% < 2 phút); phải giữ raw body nguyên vẹn để verify signature ở
worker; cần Kafka available để nhận webhook (nếu Kafka down, Stripe retry cover
phần nào).

## Ảnh hưởng migration

Cần topic `t.payment.webhooks.raw`, consumer group cho worker (chưa đặt tên), bảng
`webhook_logs` với unique constraint, Auto-Retry và Replay worker.

## Xác minh

Cần controller webhook chỉ produce Kafka, worker verify + dedup + transaction,
test duplicate `evt_xxx`, out-of-order, signature sai, DB down khi webhook đến,
metric webhook processing lag.
