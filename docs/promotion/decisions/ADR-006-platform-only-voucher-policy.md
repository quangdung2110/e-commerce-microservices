---
doc_id: promotion-adr-006
service: promotion
status: superseded
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
superseded_by: ADR-012
evidence:
  - docs/promotion/domain.md
  - docs/promotion/workflows.md
---

# ADR-006 — Một voucher PLATFORM và policy wallet/refund

## Status

`Superseded` bởi [ADR-012](./ADR-012-wallet-redemption-and-no-refund.md).

## Context

Hệ thống chỉ có admin/customer, không có seller, merchant hay shipping voucher. Cần slot,
vòng đời sở hữu và refund semantics đơn giản, không cho mã được dùng lặp ngoài ý muốn.

## Decision

Một order tối đa một `PLATFORM_VOUCHER` PERCENTAGE/FIXED. Claim window dùng promotion
start/end; use window dùng wallet expiry, mặc định save +30 ngày. Wallet tray chỉ mã đã
save; public discovery + explicit save là write có chủ đích, Submit không auto-save.
Refund giữ wallet USED; voucher không tái sử dụng. Partial allocation chỉ restore theo
internal rule `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`.

## Alternatives considered

- Nhiều slot/platform+shop+shipping: linh hoạt nhưng không phù hợp bounded context hiện tại.
- Dùng promotion end_time làm use expiry: làm mã đã claim mất giá trị sớm.
- Restore USED→SAVED sau refund: tốt cho user nhưng mở reuse/budget/accounting phức tạp.

## Consequences

Stacking/waterfall/ledger đơn giản và audit rõ. Đổi lại khả năng campaign ít linh hoạt;
cancel-confirmed phải validity-aware, và thay đổi multi-slot tương lai cần thiết kế lại.

## Migration impact

Schema/API/UI phải loại field shop/shipping/funding; wallet cần expired_at và state guard.
Order chỉ lưu promotion ID + discount amount.

## Verification

Test tối đa một slot, claim-vs-use window, no-auto-save, SAVED/EXPIRED/USED upsert, full/
partial refund và chứng minh wallet không trở lại SAVED.
