---
doc_id: payment-adr-003
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/architecture.md
  - docs/payment/interfaces.md
---

# ADR-003 — Stripe là provider duy nhất, gọi SDK trực tiếp, bỏ Provider Adapter Pattern và COD

## Trạng thái

`Accepted` trong thiết kế (ghi nhận là "Phase 18 — Single Payment Provider";
ngày không được ghi lại). Thay thế thiết kế multi-provider (VNPay, MoMo, ZaloPay)
+ COD trước đó. Chưa xác minh implementation.

## Bối cảnh

Thiết kế trước Phase 18 hỗ trợ nhiều cổng thanh toán qua Provider Adapter/Factory
(Strategy) Pattern, endpoint webhook có path variable `{provider}`, và COD (thanh
toán khi nhận hàng). Điều này tạo nhiều edge case (tiền mặt, xác nhận COD khi
giao, variance signature/callback theo từng cổng) và làm FSM/refund phức tạp.

## Quyết định

- Hệ thống chỉ dùng **Stripe** làm payment provider duy nhất. Bỏ hoàn toàn VNPay,
  MoMo, ZaloPay; bỏ Provider Adapter/Factory đa provider.
- **COD không được hỗ trợ.** Mọi đơn hàng thanh toán online qua Stripe trước khi
  giao hàng; Refund xử lý hoàn toàn qua Stripe API (`Refund.create`).
- Payment Service gọi trực tiếp `com.stripe:stripe-java` SDK. Toàn bộ
  Stripe-specific code encapsulate trong package `com.payment.stripe`:
  `PaymentIntentClient` (wrap `PaymentIntent.create/retrieve`), `RefundClient`
  (wrap `Refund.create/retrieve`), `WebhookVerifier` (wrap
  `Webhook.constructEvent` + signature validation).
- Application code gọi qua narrow `PaymentProviderPort` với đúng một
  `StripeAdapter` để cô lập SDK/test seam; port không cam kết thay provider.
- Một `StripeClient` singleton với API key từ Vault/Secret Manager, dùng chung cho
  toàn service; mọi call site (`PaymentIntent.create`, `Refund.create`,
  `PaymentIntent.retrieve`, `Webhook.constructEvent`) dùng chung instance này.
- Webhook endpoint cố định `POST /v1/payments/webhook/stripe`.
- Dữ liệu: `provider` NOT NULL DEFAULT `'STRIPE'` với `CHECK (provider = 'STRIPE')`;
  `provider_transaction_id` = `pi_xxx`, `provider_refund_id` = `re_xxx`,
  `stripe_event_id` = `evt_xxx`; `payment_method` bị loại khỏi event payload.
- Frontend dùng `client_secret` + `publishable_key` với Stripe Elements/Checkout
  SDK; không redirect sang URL ngoài.
- Webhook verify theo chuẩn Stripe: `Stripe-Signature` `t=...,v1=...`, HMAC-SHA256
  với `whsec_...`, cửa sổ timestamp 300s chống replay; Stripe retry 3–5 lần với
  exponential backoff nên hệ thống phải idempotent.

## Phương án đã cân nhắc

- **Giữ Provider Adapter/Factory với một provider:** abstraction không có giá trị
  khi chỉ có một implementation, thêm code và indirection. Không chọn.
- **Multi-provider + COD như cũ:** nhiều edge case, FSM phức tạp, refund tiền mặt
  khó tự động hóa. Không chọn.
- **Stripe direct + encapsulation:** đơn giản, FSM gọn, vẫn giới hạn blast radius.
  Được chọn.

## Hệ quả

Tích cực: giảm độ phức tạp refund flow, loại bỏ hàng loạt edge case COD; FSM gọn
hơn; một cơ chế webhook signature chuẩn. Tiêu cực: **vendor lock-in với Stripe**;
đổi provider trong tương lai phải refactor code Stripe-specific. Compensation:
encapsulation trong `com.payment.stripe` giới hạn blast radius; interface
`PaymentProviderPort` cho phép viết adapter mới khi cần.

## Ảnh hưởng migration

Bỏ path variable `{provider}` khỏi webhook endpoint; bỏ cột/field
`payment_method`; `CHECK (provider = 'STRIPE')`; không ảnh hưởng quyết định giữ
Refund trong Payment ([ADR-002](./ADR-002-refund-inside-payment-service.md)).

## Xác minh

Cần package `com.payment.stripe` với ba client, interface `PaymentProviderPort`
một implementation, `StripeClient` singleton lấy key từ secret manager, endpoint
webhook cố định, constraint `provider` trong migration, test verify signature
với `Webhook.constructEvent`.
