---
doc_id: checkout-adr-016
service: checkout
status: accepted
date: 2026-10-07
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/data.md
  - docs/checkout/workflows.md
  - contracts/http/checkout/v1/openapi.yaml
---

# ADR-016 — Fresh preview thay báo giá cũ; chặn Preview khi submission đang chạy

## Status

`Accepted` 2026-10-07. Làm rõ vai trò con trỏ active của ADR-011; thay luật WF-05 cũ ("cho
phép Preview mới khi token cũ đang submit").

## Context

Luật cũ: Preview không kèm `previous_checkout_token` chỉ được tạo khi user không có báo giá
`PREVIEWED`; Preview mới được phép trong lúc token cũ đang submit.

1. **Kẹt khi mất token.** Frontend giữ token trong bộ nhớ. Tải lại trang, mở tab mới hay app
   mobile bị tắt làm mất token; Preview không kèm `previous` nhận
   `409 ACTIVE_CHECKOUT_CHANGED` và không có API nào để biết token hiện hành, nên người dùng
   bị kẹt tới khi báo giá hết hạn (20 phút).
2. **Đơn trùng.** Người dùng bấm đặt hàng (token A `SUBMITTING`), mạng chậm nên tải lại trang,
   Preview tạo token B rồi đặt hàng lần nữa. Order chống trùng theo token nên tạo hai đơn.

## Decision

- **Fresh preview:** Preview không kèm `previous` luôn tạo báo giá mới và atomically chuyển
  báo giá `PREVIEWED` hiện tại (nếu có) sang `SUPERSEDED`. Tab cũ bấm đặt hàng nhận
  `410 CHECKOUT_SUPERSEDED`.
- **Optimistic update:** Preview kèm `previous` (đổi địa chỉ/voucher trên cùng trang) chỉ thành
  công khi `previous` đúng là token `PREVIEWED` hiện hành; sai thì
  `409 ACTIVE_CHECKOUT_CHANGED` với action `START_NEW_PREVIEW`.
- **In-flight guard:** khi token hiện hành đang `SUBMITTING` hoặc `RETRYABLE`, mọi Preview trả
  `409 SUBMISSION_IN_PROGRESS` kèm `status_url` và không tạo gì. Kiểm tra nhanh trước khi
  fan-out và kiểm tra lại trong script Lua ghi session.
- **Bất biến:** mỗi user có tối đa một token chưa terminal; con trỏ active luôn trỏ tới nó và có
  TTL bằng session nó trỏ tới. Token terminal không chặn Preview.

## Alternatives considered

- **`GET /api/v1/checkout/active` để frontend lấy lại token:** thêm endpoint và trạng thái phía
  client, lại tái dùng báo giá có thể đã cũ; vẫn phải xử lý riêng trường hợp đang submit. Không
  chọn.
- **Trả token hiện hành trong body `409 ACTIVE_CHECKOUT_CHANGED`:** vá triệu chứng, vẫn không
  chặn đơn trùng. Không chọn.
- **Giữ luật cũ:** hai lỗi ở Context. Không chọn.

## Consequences

- Tải lại trang luôn ra báo giá với giá mới nhất.
- Người dùng phải chờ submission đang chạy kết thúc (thường vài giây; tối đa tới khi recovery
  phân giải) trước khi tạo báo giá mới.
- Nếu Order không phân giải được (Order sập kéo dài), user bị chặn tới khi session hết TTL 30
  phút; đây là chủ ý vì không thể chứng minh chưa có đơn.

## Migration impact

Checkout OpenAPI mô tả lại Preview và thêm code `SUBMISSION_IN_PROGRESS` cho Preview; script Lua
tạo Preview thêm bước kiểm tra; frontend trang checkout gọi Preview không kèm `previous` khi tải
trang và chuyển tới `status_url` khi bị chặn.

## Verification

Test: fresh preview supersede `PREVIEWED`; `previous` cũ → `409`; Preview khi
`SUBMITTING`/`RETRYABLE` → `409 SUBMISSION_IN_PROGRESS` không fan-out; Preview sau terminal
thành công; hai Preview đồng thời chỉ một token `PREVIEWED`.
