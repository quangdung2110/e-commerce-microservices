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

`Accepted` cho việc tách business processing qua Kafka. Phần publish payload chưa
xác thực rồi verify ở worker đã bị
[ADR-016](./ADR-016-authenticated-webhook-inbox.md) supersede; wire representation
được ADR-015/016 refine. Chưa xác minh implementation.

## Bối cảnh

Stripe gọi webhook sau khi user thanh toán/hủy. Stripe retry nếu response > 5s
(3–5 lần với exponential backoff), gây duplicate processing nếu xử lý đồng bộ
chậm. Nếu DB down đúng lúc webhook đến và endpoint xử lý trực tiếp, event có thể
mất vĩnh viễn. Webhook có thể duplicate, delay, out-of-order.

## Quyết định

Endpoint **không xử lý business FSM**, nhưng verify exact bytes/signature trong 300
giây, persist encrypted durable inbox rồi publish verified internal CloudEvent key
`STRIPE:<stripe_event_id>`. Chỉ sau inbox + Kafka ack mới trả `200`; auth/payload
lỗi trả `400`, dependency lỗi trả `503`.

Webhook Processor claim inbox bằng DB lease/fencing, kiểm hash, parse/map Stripe
event → checkpoint/FSM/journal/outbox trong một transaction. Retry claim DB row trực
tiếp, không re-publish và không re-verify timestamp cũ.

Quy tắc hiện hành: endpoint chỉ trả 2xx nhanh sau verified inbox + Kafka ack; worker
an toàn với duplicate/out-of-order nhờ inbox lease, unique và checkpoint. Sau inbox
commit, DB Retry Worker/review case là retry/dead authority. Không có Kafka DLQ riêng.

## Phương án đã cân nhắc

- **Xử lý business FSM đồng bộ tại endpoint:** response chậm, không chọn.
- **Verified durable inbox + Kafka trigger + worker idempotent:** được ADR-016 chọn;
  DB unavailable trả `503`, không giả vờ đã nhận.

## Hệ quả

Tích cực: không mất event sau `200`; endpoint không chạy business FSM; worker scale
theo partition/DB queue. Tiêu cực: ingress phụ thuộc DB+KMS+Kafka và trả `503` khi
không durable; thêm topic, inbox và consumer; có độ trễ xử lý.

## Ảnh hưởng migration

Cần topic `t.payment.webhooks.raw`, consumer group `payment-webhook-processor`, bảng
`webhook_logs` với unique `stripe_event_id`, Auto-Retry Worker và review tooling.

## Xác minh

Cần controller webhook verify + durable inbox + produce Kafka; worker hash-check,
claim/dedup + transaction,
test duplicate `evt_xxx`, out-of-order, signature sai, DB down khi webhook đến,
metric webhook processing lag.
