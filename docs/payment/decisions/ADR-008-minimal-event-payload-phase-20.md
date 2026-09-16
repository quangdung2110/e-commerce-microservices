---
doc_id: payment-adr-008
service: payment
status: superseded
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/interfaces.md
---

# ADR-008 — Payload event tối thiểu cho Order consumer (Phase 20, superseded)

## Trạng thái

Quyết định lịch sử này bị thay thế bởi [Payment events v2](../../../contracts/events/payment/v2/asyncapi.yaml)
và [`interfaces.md`](../interfaces.md). V2 bắt buộc financial identity/version và VND
money object, đồng thời **cấm** `client_secret` khỏi Kafka/outbox/audit. File chỉ lưu lại
bối cảnh migration; không được dùng làm source contract hoặc implementation.

## Bối cảnh

Payload `PAYMENT_*`/`REFUND_*` trước Phase 20 mang nhiều field Order không cần để
route workflow hay commit FSM (`user_id`, `currency`, `provider`,
`payment_method_type`, `provider_transaction_id`, `provider_charge_id`,
`provider_refund_id`, `payment_event_sequence_id`, `refund_event_sequence_id`,
`error_message`, `reason_message`). Field thừa làm contract nặng, lộ chi tiết
provider ra ngoài và tạo coupling không cần thiết.

## Quyết định lịch sử (không còn áp dụng)

Kafka payload chỉ giữ field Order cần để route workflow/commit FSM; Payment
Service vẫn giữ đầy đủ field ở internal log/audit.

| Event | Payload giữ lại | Lý do |
|---|---|---|
| `PAYMENT_INITIATED` | `payment_transaction_id`, `order_id`, `amount`, `client_secret`, `expires_at` | **Đã thay thế:** V2 không gửi secret trong event; API response đã authenticated mang secret. |
| `PAYMENT_SUCCESS` | `payment_transaction_id`, `order_id`, `amount`, `paid_at` | idempotency, route, verify amount, audit |
| `PAYMENT_FAILED` | `payment_transaction_id`, `order_id`, `error_code`, `failed_at` | Notification map `error_code` qua i18n |
| `PAYMENT_EXPIRED` | `payment_transaction_id`, `order_id`, `expired_at` | audit timeline |
| `REFUND_INITIATED` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `refund_amount`, `reason`, `stripe_reason`, `initiated_at` | Analytics cross-ref/categorize |
| `REFUND_PROCESSING` | `refund_transaction_id`, `order_id`, `processing_at` | Order đã lock amount từ workflow |
| `REFUND_SUCCESS` | `refund_transaction_id`, `order_id`, `refund_amount`, `refunded_at` | idempotency, route, allocation, audit |
| `REFUND_FAILED` | `refund_transaction_id`, `order_id`, `error_code`, `failed_at` | categorize |
| `REFUND_REVERSED` | `refund_transaction_id`, `order_id`, `reason_code`, `reversed_at` | categorize |

Bỏ: `user_id`, `currency` (luôn VND), `provider` (luôn STRIPE),
`payment_method_type`, `provider_transaction_id` (chỉ cần cho refund call nội bộ),
`provider_charge_id`, `provider_refund_id` (Payment internal audit),
`payment_event_sequence_id`, `refund_event_sequence_id` (Order không guard theo),
`error_message`, `reason_message` (Notification map từ code qua i18n key).

## Phương án đã cân nhắc

- **Giữ payload đầy đủ:** consumer tiện nhưng contract nặng, lộ provider detail,
  coupling cao. Không chọn.
- **Payload tối thiểu + internal audit đầy đủ:** contract gọn, Payment vẫn có dữ
  liệu để debug/dispute. Được chọn.

## Hệ quả

Tích cực: contract nhỏ, ít thay đổi khi provider detail đổi; Order không phụ
thuộc sequence của Payment. Tiêu cực: Analytics phải join thêm qua `order_id`;
Notification phải có i18n mapping cho `error_code`/`reason_code`; consumer cần
`event_id` để dedup thay vì sequence.

## Migration đã được thay thế

V1 không được publish chung topic với V2. Producer/consumer mới chỉ dùng
`t.payment.events.v2`, `data.schema_version=2` và schema AsyncAPI. Migration thực tế cần
dual-publish/consumer cutover được phê duyệt riêng; không được re-enable V1 vì schema cũ
thiếu `currency`/financial version và lộ `client_secret`.

## Xác minh

Cần provider/consumer test Payment events v2, đặc biệt absence of `client_secret`,
attempt/transaction identity, VND money and monotonic financial version.
