---
doc_id: checkout-adr-009
service: checkout
status: superseded
superseded_by: checkout-adr-012
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/workflows.md
  - docs/checkout/operations.md
---

# ADR-009 — Degraded pricing bắt buộc user acknowledgement

## Trạng thái

`Superseded` bởi [ADR-012](./ADR-012-contract-aligned-fail-closed-orchestration.md) ngày 2026-09-20; nội dung dưới đây là lịch sử, không phải contract hiện hành. Trước đó: `Accepted` theo thiết kế cũ, chưa có implementation evidence.

## Bối cảnh

Preview gọi nhiều downstream qua mạng nội bộ, nơi luôn có tỉ lệ sự cố (network
partition). Nếu Promotion hoặc Inventory timeout, Checkout phải chọn giữa từ chối
toàn bộ Preview hoặc trả báo giá tạm thời. Trả báo giá tạm thời mà không có xác
nhận của user có thể khiến user commit số tiền khác với kỳ vọng (không có voucher,
giá tạm).

## Quyết định

- Checkout áp dụng Circuit Breaker / Fallback theo từng scope với timeout: User
  500ms (fallback địa chỉ cũ từ session), Cart 50ms (lỗi Preview), Inventory
  routing 3s, Promotion calculation 2s, tổng Preview 5s (fallback từng phần,
  không block toàn bộ).
- Promotion timeout → Preview trả trạng thái `DEGRADED_PROMOTION` (không áp
  voucher) và UI **bắt buộc** hiển thị hộp xác nhận “Thanh toán không áp mã giảm
  giá tạm thời” trước khi cho Submit.
- Inventory routing timeout → Preview trả `DEGRADED_STOCK` và UI bắt buộc hiển thị
  hộp xác nhận “Thanh toán với giá tạm thời” trước khi cho Submit.
- Mọi Submit ở chế độ degraded phải mang `user_ack_degraded_pricing = true`;
  thiếu cờ → backend nhả lock và trả `409 DEGRADED_PRICING_NOT_CONFIRMED`.
- Cờ `user_ack_degraded_pricing` được gửi sang Order trong payload; Order chấp
  nhận `promotions` rỗng khi cờ này `true`.

## Phương án đã cân nhắc

- **Từ chối Preview khi bất kỳ downstream timeout:** an toàn nhưng làm mất toàn
  bộ khả năng checkout khi một dependency chập chờn; không chọn.
- **Trả giá tạm mà không cần ack:** user có thể commit ngoài ý muốn; không chọn.
- **Degraded + explicit ack (chọn).**

## Hệ quả

- Tích cực: Checkout vẫn phục vụ khi Promotion/Inventory chập chờn; user có thông
  tin trước khi commit.
- Tiêu cực: Frontend phải implement hộp xác nhận và cờ ack; cờ degraded sống trong
  session nên user phải Preview lại để bỏ cờ khi downstream hồi phục.
- Constraint: Submit kiểm tra ack trước Price Staleness Check và trước Lua.

## Ảnh hưởng migration

Chưa có code. Contract Submit có thêm `user_ack_degraded_pricing`; Order nhận cờ
này trong payload.

## Xác minh

Khi có code: Promotion/Inventory timeout → response mang cờ degraded; Submit không
ack → `409 DEGRADED_PRICING_NOT_CONFIRMED`, lock được nhả, session không đổi;
Submit có ack → payload sang Order mang cờ.
