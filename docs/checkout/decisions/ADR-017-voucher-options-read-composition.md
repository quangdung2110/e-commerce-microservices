---
doc_id: checkout-adr-017
service: checkout
status: accepted
date: 2026-10-07
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - contracts/http/checkout/v1/openapi.yaml
  - contracts/http/promotion/v1/openapi.yaml
  - docs/promotion/decisions/ADR-018-checkout-voucher-options-and-context-free-save.md
---

# ADR-017 — Khay voucher là read composition của Checkout; save/claim ở Promotion

## Status

`Accepted` 2026-10-07. Phần Promotion tương ứng:
[Promotion ADR-018](../../promotion/decisions/ADR-018-checkout-voucher-options-and-context-free-save.md).

## Context

Các API voucher của Promotion cho trang checkout (`/api/v1/me/promotion-voucher-suggestions`,
`/api/v1/promotion-discovery`, `PUT /api/v1/me/promotion-vouchers/{promotion_id}`) đều bắt
client gửi `context` gồm `unit_price` và `category_ids` từng dòng. Frontend không có
`category_ids` (Cart và Checkout không trả), và nếu tự dựng thì giá do client quyết định, kết
quả khay có thể lệch với Preview. `VoucherEvaluation` cũng không có tên, mô tả hay điều kiện
hiển thị của voucher.

## Decision

- Checkout cung cấp `GET /api/v1/checkout/previews/{checkout_token}/voucher-options` cho token
  `PREVIEWED` chưa hết hạn. Checkout dựng `TrustedCheckoutContext` từ snapshot của token (giá
  và `category_ids` Product, quantity Cart, subtotal, `checkout_channel=CHECKOUT`,
  `payment_method=STRIPE`, không tier) và gọi Promotion
  `POST /internal/v1/promotion-voucher-options` (`include_public`, `include_ineligible`,
  `limit=50`).
- Response là view model cho frontend: thông tin hiển thị, `eligible`/`reason`, discount ước
  tính, `required_action` (`NONE`/`SAVE`/`CLAIM`), `expected_campaign_revision`, `is_applied`.
  Thứ tự giữ ranking của Promotion.
- Khay chỉ đọc và advisory. Áp voucher luôn bằng Preview mới với `promotion_id`.
- Save (`PUT` với body rỗng) và claim gọi thẳng Promotion qua Gateway; Checkout không proxy
  write của domain khác.
- Lỗi Promotion trả `503 VOUCHER_OPTIONS_UNAVAILABLE`, có circuit riêng, không ảnh hưởng
  Preview/Submit. Cờ `CHECKOUT_VOUCHER_OPTIONS_ENABLED` cho phép bật sau khi Promotion triển khai.
- Gateway profile `checkout-voucher-options`: 20 request/user/phút. Không cache.

## Alternatives considered

- **Frontend gọi thẳng Promotion với context tự dựng:** không có `category_ids`, giá do client
  quyết định; không chọn.
- **Nhúng khay voucher vào response Preview:** Preview bị gọi lại mỗi lần đổi địa chỉ/voucher,
  trong khi đánh giá tới 50 voucher là đắt; làm chậm đường đặt hàng chính dù khay ít được mở;
  lỗi Promotion sẽ làm hỏng cả Preview. Không chọn.
- **Checkout proxy cả save/claim:** BFF ôm write của Promotion, phải gọi API public trong mesh;
  không chọn.

## Consequences

- Khay voucher khớp báo giá; frontend không cần biết giá hay category.
- Promotion thêm một operation nội bộ và nới `context` khi save (Promotion ADR-018).
- Thêm một route Gateway và một policy mesh.

## Migration impact

Checkout OpenAPI thêm endpoint và schema `VoucherOptions`; Promotion OpenAPI thêm operation
`proposed`; catalog thêm interaction `checkout-lists-voucher-options`; Gateway thêm route và
profile; mesh thêm `AuthorizationPolicy` và traffic policy; frontend trang checkout dùng endpoint
này thay vì suggestion/discovery.

## Verification

Test mapping `required_action`; context không gọi lại Cart/Product; Promotion lỗi không ảnh
hưởng Preview; token không `PREVIEWED` bị từ chối; cờ tắt không gọi Promotion.
