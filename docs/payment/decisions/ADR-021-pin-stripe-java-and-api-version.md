---
doc_id: payment-adr-021
service: payment
status: accepted
date: 2026-09-22
decision_owners:
  - Payment Service team
supersedes: null
evidence:
  - java/services/payment-service/pom.xml
  - java/services/payment-service/src/main/resources/application.yml
  - java/services/payment-service/src/test/java/com/platform/paymentservice
---

# ADR-021 — Pin Stripe Java SDK và Stripe API version

## Trạng thái

`Accepted` — 2026-09-22.

## Bối cảnh

ADR-003 yêu cầu dùng trực tiếp `stripe-java`; ADR-018 và cấu hình vận hành yêu cầu
pin API version cụ thể, nhưng tài liệu chưa ghi version. Không pin đồng thời SDK và
API làm generated model, webhook fixture và provider contract có thể drift.

## Quyết định

- Pin Maven `com.stripe:stripe-java` ở **`33.4.2`**; không dùng version range,
  alpha, beta hoặc preview.
- Pin Stripe API ở **`2026-08-26.dahlia`** cho outbound request, webhook endpoint và
  fixture. Đây là API version được chính `stripe-java 33.4.2` khai báo.
- Mọi môi trường dùng cùng hai version; secret key/sandbox có thể khác nhưng contract
  không khác.
- Upgrade chỉ qua ADR mới hoặc ADR thay thế, kèm changelog review, sandbox webhook
  replay, provider contract tests và rollback plan. Không tự động nâng dependency.

## Hệ quả

Build và payload deterministic; SDK model khớp API header. Service chấp nhận phải
chủ động nâng version để nhận breaking API release hoặc security fix mới.

## Xác minh

- Unit test khẳng định `Stripe.VERSION = 33.4.2` và
  `Stripe.API_VERSION = 2026-08-26.dahlia`.
- Adapter luôn truyền API version pin và timeout 5s/15s.
- Webhook fixtures và Stripe sandbox endpoint dùng cùng API version.
