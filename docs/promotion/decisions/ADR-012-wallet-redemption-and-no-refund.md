---
doc_id: promotion-adr-012
service: promotion
status: accepted
date: 2026-09-10
decision_owners:
  - Promotion Service team
supersedes:
  - ADR-001
  - ADR-006
  - ADR-008
evidence:
  - docs/promotion/data.md
  - docs/promotion/domain.md
  - docs/promotion/workflows.md
---

# ADR-012 — Wallet là redemption state và customer refund không hoàn voucher

## Status

`Accepted` ở mức thiết kế; chưa implemented. Supersede usage/idempotency ledger,
voucher-refund policy và reconciliation query cũ.

## Context

Voucher có ý nghĩa cho toàn đơn, không có cơ sở domain để phân bổ xuống SKU chỉ nhằm
hoàn budget khi partial refund. `coupon_usages` trùng thông tin current redemption với
wallet; bảng idempotency tổng quát làm schema phức tạp trong khi mỗi flow có natural key.
Customer refund cũng không nên làm Promotion phụ thuộc thiết kế partial refund chưa chốt
của Order/Payment.

## Decision

- Một order tối đa một platform voucher toàn đơn; không line/SKU allocation.
- `user_coupon_wallets` là current ownership/redemption state và lưu used order, amount,
  time. Xóa `coupon_usages` và `promotion_event_idempotency`.
- Idempotency dựa unique constraint, guarded transition, stable reservation key và
  unique outbox dedup key.
- Voucher validity được admin chọn rõ; `expired_at` snapshot lúc save. Natural campaign
  end không xóa voucher đã lưu.
- Customer refund partial, nhiều partial hay full-order không gọi Promotion: wallet giữ
  USED, budget giữ consumed, không cấp lại voucher và không phát coupon-refunded event.
- `cancel-confirmed` chỉ dành cho lỗi kỹ thuật sau confirm: reverse budget và chuyển
  wallet về SAVED nếu còn hạn, EXPIRED nếu hết hạn.
- Reconciliation dựng used từ wallet USED, claimed từ claim rows và reserved từ Redis
  payload hợp lệ; chỉ repair DB → Redis.
- Tiền/counter dùng integer VND; chỉ campaign giữ business revision.

## Alternatives considered

- Usage/refund ledger riêng: audit chi tiết hơn nhưng trùng state và kéo Promotion vào
  mọi refund transaction.
- Phân bổ voucher theo line: hỗ trợ proportional refund nhưng tạo nghĩa business giả cho
  voucher toàn đơn.
- Restore voucher khi full refund: thân thiện hơn nhưng cho phép reuse, tăng accounting
  và phụ thuộc refund policy.
- Không reverse `cancel-confirmed`: đơn giản nhưng lỗi hệ thống làm mất quyền lợi khách.

## Consequences

Schema và refund boundary đơn giản, độc lập với Order/Payment, không có tranh cãi phân bổ
SKU. Đổi lại customer không nhận lại voucher kể cả full refund; budget promotion vẫn
được xem là đã sử dụng. Wallet là current state, không phải ledger lịch sử đa lần.

## Migration impact

Backfill wallet used fields/budget từ dữ liệu hiện có nếu có, chuyển idempotency sang
natural keys/outbox dedup, xóa refund endpoint/consumer/event/payload allocation và cập
nhật Order contract. Destructive drop chỉ sau đối soát/backfill và rollout tương thích.

## Verification

Test unique wallet/order, confirm duplicate, cancel-confirmed retry và validity, customer
partial/full refund no-op, reconciliation từ wallet, outbox dedup retention và chứng minh
không tồn tại Promotion refund surface.
