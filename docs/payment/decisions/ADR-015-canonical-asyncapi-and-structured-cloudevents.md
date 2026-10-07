---
doc_id: payment-adr-015
service: payment
status: accepted
date: 2026-09-20
decision_owners:
  - Payment Service team
  - Order Service team
supersedes: null
refines:
  - payment-adr-004
  - payment-adr-005
  - payment-adr-007
  - payment-adr-008
evidence:
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/events/payment/internal/v1/asyncapi.yaml
  - docs/payment/interfaces.md
---

# ADR-015 — Canonical AsyncAPI và CloudEvents structured JSON cho Payment

## Trạng thái

`Accepted` — 2026-09-20. Quyết định này giữ nguyên business semantics, topic và
partition key của ADR-004/005/007/008, đồng thời chốt wire representation và schema
máy đọc được. Phần internal message chưa xác thực/candidate key ở mục 6–7 và phương
án loại bỏ parse-ingress đã bị ADR-016 supersede: contract hiện chỉ mang verified
delivery. ADR-019 mở danh mục financial event từ 10 lên 15.

## Bối cảnh

Payment events v2 ban đầu gom mười event vào một schema có điều kiện. Schema đó
không bắt buộc các field riêng như `expires_at`, `error_code`, `reason_code`, dùng
CloudEvents `type` chưa có namespace/version và không có `dataschema`. Tài liệu còn
mô tả CloudEvents structured JSON nhưng lại đặt attributes trong outbox/Kafka
headers và chỉ đặt `data` trong Kafka value. Hai representation này không thể cùng
là wire contract canonical.

Topic nội bộ `t.payment.webhooks.raw` cũng chưa có contract máy đọc được. Việc đặt
raw HTTP body trực tiếp trong JSON string tạo nguy cơ normalize byte trước khi
`Stripe-Signature` được verify.

## Quyết định

1. `t.payment.events.v2` giữ nguyên tên vì contract và service đều còn `proposed`.
   Mỗi business alias `PAYMENT_*`/`REFUND_*`/`DISPUTE_*` có một Message Object và một JSON
   Schema Draft 2020-12 riêng. CloudEvents `type` canonical có reverse-DNS namespace
   và hậu tố `.v2`; alias viết hoa chỉ dùng cho domain/FSM và tiêu đề tài liệu.
2. Kafka value canonical là **toàn bộ CloudEvents 1.0 structured JSON envelope**.
   `outbox_events.payload` giữ nguyên envelope này. Kafka headers có thể mirror ID,
   type, correlation hoặc trace cho observability nhưng không phải authority và
   consumer không được yêu cầu chúng để hiểu message.
3. Envelope bắt buộc `specversion`, UUID `id`, `type`, `source=/services/payment`,
   payment-scoped `subject`, `time`, `datacontenttype=application/json`, per-event
   `dataschema` và `data`. `correlationid`/`traceparent` là optional CloudEvents
   extensions ở top level.
4. Kafka key của financial facts bằng chính xác `data.order_id`; ordering theo
   order, còn correctness guard bằng `data.financial_state_version` monotonic theo
   `payment_transaction_id`. Delivery at-least-once; consumer dedup theo
   `(source,id)` trong durable inbox cùng local mutation.
5. `data.schema_version=2` được giữ để tương thích tài liệu v2. ID, money và timestamp
   dùng cùng constraint với Payment OpenAPI. Schema cấm `client_secret`, Stripe
   object ID, payment-method data, `user_id` và raw provider payload.
6. Topic nội bộ `t.payment.webhooks.raw` có AsyncAPI v1 riêng. Theo ADR-016,
   `payment-api` chỉ phát delivery đã verify; exact bytes base64, hash và verified
   `stripe_event_id` là key/identity.
7. Không tạo Kafka DLQ mới. Durable inbox được commit trước publish; sau đó DB Retry
   Worker/review case là retry/dead mechanism. Kafka fail làm ingress trả `503` và
   duplicate non-terminal có thể publish lại trigger mà không tạo inbox thứ hai.
8. Payment không consume Order/Inventory/Delivery/Promotion events. Cancel/refund
   vẫn chỉ đến qua explicit HTTP command từ Order.

## Phương án đã cân nhắc

- **CloudEvents binary mode:** khớp mô tả outbox headers cũ nhưng trái event
  convention chung của repo và khiến schema message bị tách giữa Kafka headers/value.
  Không chọn.
- **Một union schema cho 15 event:** ít file hơn nhưng không khóa được field bắt
  buộc và dễ chấp nhận tổ hợp field sai. Không chọn.
- **Đổi sang `t.payment.events.v3`:** đúng nếu đã có deployed consumer, nhưng hiện
  contract/provider/consumer đều `proposed`, chưa có implementation. Không chọn;
  nếu trạng thái adoption thay đổi trước implementation thì phải mở migration ADR.
- **Parse payload chưa verify ở ingress:** không chọn. ADR-016 yêu cầu verify trên
  exact bytes trước, rồi mới parse envelope tối thiểu và persist/publish.

## Hệ quả

Tích cực: contract machine-readable theo từng event; producer không thể phát thiếu
field riêng; Order dispatch bằng exact type; wire/outbox/CDC thống nhất; raw signature
verification tái lập được. Tiêu cực: nhiều schema và fixture hơn; mọi tài liệu và
consumer phải dùng exact namespaced type; outbox payload lớn hơn data-only một lượng
nhỏ nhưng vẫn dưới giới hạn 64 KiB.

## Ảnh hưởng migration

Không dual-publish vì chưa có runtime. Khi implementation bắt đầu, migration đầu
tiên tạo `outbox_events.payload` chứa full envelope, Debezium chuyển value nguyên
vẹn và dùng `partition_key` làm Kafka key. Order consumer và Analytics phải
generate/validate từ Payment AsyncAPI v2 và không dispatch theo alias viết hoa.

## Xác minh

- AsyncAPI/JSON Schema parse và resolve toàn bộ `$ref`.
- Một valid fixture và các invalid fixture thiếu field riêng cho cả 15 event.
- Contract test khẳng định Kafka key = `data.order_id`, type ↔ dataschema chính xác,
  `client_secret` không validate và `amount_minor > 0`.
- Debezium integration test chứng minh Kafka value là full CloudEvent và connector
  restart chỉ tạo duplicate cùng `(source,id)`.
- Verified webhook test chứng minh ingress reject auth/malformed trước DB/Kafka,
  base64 decode đúng byte, hash khớp và key là verified `stripe_event_id`.
