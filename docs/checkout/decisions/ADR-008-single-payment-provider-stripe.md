---
doc_id: checkout-adr-008
service: checkout
status: accepted
date: null
decision_owners:
  - Checkout Service team
supersedes: checkout-multi-provider-payment-method
evidence:
  - docs/checkout/interfaces.md
  - docs/checkout/data.md
---

# ADR-008 — Phase 18: chỉ một payment provider (Stripe), bỏ `payment_method` khỏi Checkout

## Trạng thái

`Accepted` từ Phase 18 (ngày cụ thể không rõ). Thay thế thiết kế cũ trong đó
client gửi `payment_method` (ví dụ `VNPAY`) ở Submit và Checkout validate/lưu
field này trong session. Tài liệu cũ tham chiếu
`docs/_contracts/CHANGELOG.md#phase-18`, file này không còn tồn tại trong
repository.

## Bối cảnh

Hệ thống quyết định chỉ dùng một payment provider duy nhất là Stripe. Việc client
chọn provider ở Checkout, Checkout validate `payment_method` và Order/Payment
route theo provider không còn cần thiết.

## Quyết định

- Checkout **không nhận** `payment_method` từ client; field này bị loại khỏi
  Submit payload và response. Nếu client vẫn gửi, field bị bỏ qua (N/A).
  > **Ghi chú 2026-10-07:** OpenAPI (`additionalProperties: false`) và
  > [ADR-015](./ADR-015-problem-catalog-view-model-and-order-outcomes.md) quy định field lạ là
  > `400 INVALID_REQUEST`; hành vi hiện hành là từ chối, không bỏ qua
  > ([ADR-018](./ADR-018-implementation-decisions.md)).
- Redis session không lưu `payment_method`; chỉ lưu `pricing_summary` +
  `delivery_address_snapshot` + voucher selection. Provider được fix cứng = Stripe
  ở Order Service và Payment Service.
- Preview gọi Promotion calculation với `payment_method=STRIPE` cố định.
- Order trả `[order_id, client_secret, publishable_key]` (thay cho `payment_url`
  trước đây); Checkout chuyển tiếp cho Frontend dùng Stripe Elements / Stripe
  Checkout SDK. (Ghi chú 2026-09-19: response cache **không** còn lưu
  `client_secret`/`publishable_key` — xem [ADR-010](./ADR-010-relay-payment-client-data-no-secret-cache.md).)

## Phương án đã cân nhắc

- **Giữ `payment_method` như optional với default Stripe:** tương thích ngược
  nhưng giữ một field vô nghĩa và bề mặt validate thừa; không chọn.
- **Bỏ hẳn field (chọn).**

## Hệ quả

- Tích cực: Submit payload chỉ còn `checkout_token` và
  `user_ack_degraded_pricing`; bớt validate; session gọn hơn.
- Tiêu cực: thêm provider sau này là breaking change cho Checkout/Order/Payment
  contract.
- Constraint: Checkout không có logic provider.

## Ảnh hưởng migration

Frontend bỏ chọn provider và chuyển sang nhận `client_secret`/`publishable_key`;
tài liệu Checkout/Order/Payment cập nhật đồng bộ.

## Xác minh

Khi có code: Submit với `payment_method` trong body vẫn thành công và field bị bỏ
qua; response không có `payment_method`/`payment_url`; session không có field
provider.
