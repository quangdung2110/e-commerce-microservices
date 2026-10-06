---
doc_id: promotion-adr-018
service: promotion
status: accepted
date: 2026-10-07
decision_owners:
  - Promotion Service team
  - Checkout Service team
supersedes: null
evidence:
  - contracts/http/promotion/v1/openapi.yaml
  - docs/checkout/decisions/ADR-017-voucher-options-read-composition.md
---

# ADR-018 — Voucher options nội bộ cho Checkout và lưu voucher không cần context

## Status

`Accepted` 2026-10-07; contract `proposed`, chưa có code. Phía Checkout:
[Checkout ADR-017](../../checkout/decisions/ADR-017-voucher-options-read-composition.md).

## Context

Trang checkout cần khay voucher (ví + công khai) được đánh giá trên đúng báo giá. Các query
customer hiện có (`/api/v1/me/promotion-voucher-suggestions`, `/api/v1/promotion-discovery`)
và lệnh lưu `PUT /api/v1/me/promotion-vouchers/{promotion_id}` đều bắt browser gửi
`CheckoutContext` có `unit_price` và `category_ids`. Browser không có `category_ids` tin cậy;
Checkout là nơi duy nhất có snapshot server-owned. `VoucherEvaluation` cũng thiếu tên, mô tả,
điều kiện hiển thị.

## Decision

- Thêm `POST /internal/v1/promotion-voucher-options`, chỉ principal `checkout-service` (mesh
  `AuthorizationPolicy` + `X-Service-Identity` attribution). Request: `user_id`, `user_tier`
  tùy chọn, `TrustedCheckoutContext`, `include_public`, `include_ineligible`, `limit` ≤ 50.
  Response: mỗi item gồm `evaluation` (`VoucherEvaluation` hiện có) và `display` (`code`,
  `name`, `description`, `discount`, `min_order_amount`, `valid_until`). Cùng logic và ranking
  với suggestion/discovery; safe query ngoài lazy-normalize `SAVED` hết hạn; không save, claim,
  reserve. Không có local rate limiter: quota thuộc Gateway profile `checkout-voucher-options`
  ở route Checkout.
- `context` của `PUT /api/v1/me/promotion-vouchers/{promotion_id}` thành tùy chọn. Thiếu
  `context` thì chỉ lưu wallet và trả `evaluation: null`. Có `context` thì hành vi như cũ.
- Suggestion/discovery public giữ nguyên cho tương thích; trang checkout không dùng chúng. Việc
  deprecate để quyết định sau khi không còn consumer.

## Alternatives considered

- **Browser gửi context tự dựng:** không có `category_ids`, giá không tin cậy; không chọn.
- **Checkout gọi API public trong mesh với `X-User-Id`:** phá ranh giới edge/internal và policy
  mesh của route public; không chọn.
- **Thêm API đọc thông tin hiển thị riêng rồi Checkout ghép:** hai lời gọi cho một khay; không
  chọn.

## Consequences

- Promotion có thêm một operation nội bộ cần provider test; logic evaluate dùng chung.
- Lệnh lưu không cần context đơn giản hoá trang checkout; Preview của Checkout tính discount
  chính thức.

## Migration impact

Promotion: triển khai operation mới, nới validation `context`, test MockMvc và cập nhật
`HttpContractIntegrationTest`. Mesh thêm policy `checkout-lists-voucher-options`; catalog thêm
interaction. Frontend sinh lại type `promotion`. Tới khi triển khai, Checkout giữ
`CHECKOUT_VOUCHER_OPTIONS_ENABLED=false`.

## Verification

Provider test: chỉ `checkout-service` gọi được; ranking giống discovery; `display` khớp
definition; lưu voucher không có body context trả `evaluation: null`; có context vẫn như cũ.
