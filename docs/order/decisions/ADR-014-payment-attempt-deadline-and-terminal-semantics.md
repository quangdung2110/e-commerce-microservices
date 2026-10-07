---
doc_id: order-adr-014
service: order
status: accepted
date: 2026-10-05
decision_owners:
  - Order Service team
  - Payment Service team
supersedes:
  - ADR-008
evidence:
  - docs/order/domain.md
  - docs/order/workflows.md
  - docs/order/interfaces.md
  - contracts/http/order/v1/openapi.yaml
---

# ADR-014 — Payment-attempt deadline và terminal semantics

## Trạng thái

`Accepted` — 2026-10-05. Thay thế ADR-008 và refine phần payment race của ADR-011.

## Bối cảnh

Payment contract xác định `FAILED`, `EXPIRED`, `CANCELED` là non-capture facts nhưng
chỉ `FAILED` biểu diễn attempt có thể thử lại trong cùng business window. Việc hồi sinh
`CANCELLED -> PAID` sau khi resources đã release vừa trái terminal contract vừa có thể
tạo paid order không còn stock/voucher.

## Quyết định

- `payment_deadline_at` được khóa một lần khi order đạt `PAYMENT_READY`, bằng minimum
  của business ceiling 15 phút và mọi resource-hold expiry trừ clock-skew safety mặc
  định 30 giây (configurable). Provider expiry evidence được persist; attempt mới không
  gia hạn deadline.
- Chỉ attempt ngay trước ở `FAILED` được request attempt kế tiếp, trước deadline và khi
  Inventory/Promotion holds vẫn có accepted proof. API atomically ghi operation và durable
  workflow message; workflow recheck toàn bộ guard dưới order lock.
- `EXPIRED`, `CANCELED`, explicit cancel hoặc deadline bắt đầu pre-payment cleanup và
  không còn eligible cho attempt mới.
- Nếu capture thắng khi order còn `PENDING_PAYMENT` hoặc `CANCELLING`, capture là
  financial watermark và flow đi post-capture forward repair.
- Success tới sau terminal non-capture/`CANCELLED` là provider-contract anomaly:
  Order giữ nguyên terminal state, mở `CAPTURE_AFTER_TERMINAL_NON_CAPTURE` obligation,
  attach evidence và yêu cầu Finance/Payment xử lý. Không phát `order.paid`, không
  re-reserve và không auto-refund thiếu gate.

## Hệ quả và xác minh

Không còn late-payment recovery workflow/task queue. Test phải bao phủ FAILED retry,
deadline/hold race, EXPIRED/CANCELED rejection, capture-vs-cancel serialization,
duplicate result và anomalous success sau terminal mà không đổi master FSM.
