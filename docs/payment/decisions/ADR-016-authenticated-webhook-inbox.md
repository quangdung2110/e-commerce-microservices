---
doc_id: payment-adr-016
service: payment
status: accepted
date: 2026-09-22
decision_owners:
  - Payment Service team
supersedes: null
refines:
  - payment-adr-004
  - payment-adr-015
evidence:
  - docs/payment/workflows.md
  - docs/payment/data.md
  - contracts/events/payment/internal/v1/asyncapi.yaml
---

# ADR-016 — Xác thực webhook tại ingress và xử lý bằng durable inbox có lease

## Trạng thái

`Accepted` — 2026-09-22. Thay thế phần xác thực bất đồng bộ của ADR-004; giữ nguyên
quyết định business processing bất đồng bộ qua Kafka và structured CloudEvents của
ADR-015.

## Bối cảnh

Thiết kế cũ trả `200` sau Kafka ack rồi mới dùng `Webhook.constructEvent` ở worker với
cửa sổ 300 giây. Kafka lag hoặc retry từ `webhook_logs` sau 300 giây làm một delivery
hợp lệ trở thành hết hạn. Hơn nữa, row `FAILED_PROCESSING` được đẩy lại nhưng lần xử
lý sau gặp unique `stripe_event_id` và bị coi là duplicate, nên không thể retry thật.
Ingress cũng nhận và lưu payload chưa xác thực, tạo bề mặt abuse không cần thiết.

## Quyết định

1. Ingress đọc exact raw body với hard limit 256 KiB, yêu cầu `Stripe-Signature`, rồi
   verify HMAC và `|received_at - signature_timestamp| <= 300s` bằng tối đa hai secret
   đang active. Verify dùng raw bytes và clock UTC đã đồng bộ NTP.
2. Signature/payload không hợp lệ trả `400` và không persist/produce. Sau verify,
   ingress encrypt + upsert durable inbox rồi publish Kafka. Inbox/KMS/Kafka lỗi trả
   `503`; chỉ delivery đã durable và Kafka ack (hoặc duplicate terminal) nhận `200`.
3. Ingress parse Stripe Event sau verify để lấy canonical `stripe_event_id`, type,
   `event.created`, API version và livemode. Internal message đổi nghĩa từ raw-untrusted
   thành **verified delivery** nhưng vẫn giữ raw bytes base64 để worker deserialize
   đúng version và phục vụ forensic. Message mang `verified_at`, `received_at` và
   `signing_secret_id` không nhạy cảm; không mang secret.
4. Worker không re-check recency. Nó kiểm tra integrity bằng `raw_body_sha256`, parse
   lại raw bytes và chỉ tin message từ topic/producer ACL của Payment ingress. Manual
   replay chỉ được lấy từ một inbox row đã từng verify thành công.
5. `webhook_logs` là durable inbox với state:
   `RECEIVED -> PROCESSING -> PROCESSED | IGNORED | RETRY_WAIT | DEAD`.
   Claim dùng atomic conditional update với `lease_owner`, `lease_token`,
   `lease_expires_at`; mọi commit của owner phải so fencing `lease_token`.
6. Chỉ `PROCESSED` và `IGNORED` là duplicate terminal. `RETRY_WAIT` và
   `PROCESSING` có lease hết hạn được claim lại. `DEAD` chỉ replay qua thao tác admin
   có `actor`, `cause_ref` và đưa về `RETRY_WAIT`.
7. Trước khi inbox row commit, Kafka redelivery là retry authority. Sau khi inbox row
   tồn tại, Webhook Retry Worker claim trực tiếp DB row và gọi cùng application use
   case; không re-produce cùng event vào Kafka.
8. Secret rotation giữ hai secret active. Delivery thất bại do cấu hình secret sai
   được Stripe retry với chữ ký/timestamp mới vì ingress trả non-2xx; không tồn tại
   `FAILED_SIGNATURE` durable row cho payload chưa xác thực.

## Hệ quả

Business transition vẫn async. DB outage làm ingress trả `503`, nên Stripe phải retry
delivery mới; hệ thống không nhận `200` trước durability. Ingress thêm HMAC/parse,
encryption, inbox write và Kafka publish nhưng không gọi Stripe/business FSM. Retry
inbox có state machine chứng minh được; raw chưa xác thực không vào Kafka/PostgreSQL.

## Xác minh

Test signature đúng/sai/hết hạn/hai secret; body bị đổi; payload quá 256 KiB; Kafka
timeout; backlog trên 300 giây vẫn xử lý vì đã verified tại ingress; worker crash sau
claim; stale owner bị fencing; `RETRY_WAIT` claim lại; duplicate terminal no-op; admin
replay `DEAD` có audit.
