---
doc_id: order-adr-007
service: order
status: superseded
date: 2026-06-17
decision_owners:
  - Architecture Team
supersedes: shipping-voucher-and-voucher-restore-policy
superseded_by: ADR-009
evidence:
  - docs/order/domain.md
  - docs/order/interfaces.md
---

# ADR-007 — Chính sách promotion allocation khi refund

## Trạng thái

`Superseded` bởi
[ADR-009](./ADR-009-customer-refund-does-not-mutate-promotion.md). Nội dung dưới đây
được giữ làm lịch sử của thiết kế allocation cũ.

## Bối cảnh

Refund FULL/PARTIAL cần hoàn ngân sách promotion đúng số tiền. Nếu Promotion tự
suy luận từ item thiếu context, có thể leak budget hoặc hoàn thiếu. Tiền đã refund
không nên rollback vì wallet/budget phụ trợ thất bại, tránh chargeback/rủi ro lớn hơn.

## Quyết định

Chỉ có voucher `PLATFORM` (percentage/fixed amount). Order workflow compute
`promotion_refund_allocations[]` deterministic:

- FULL: full `discount_amount` của mỗi promotion.
- PARTIAL: full allocation chỉ khi scope bao phủ toàn bộ item của platform; ngược
  lại 0 (`RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`).

Entry phát đi chỉ còn `promotion_id` và `refunded_discount_amount`. Promotion hoàn
budget/ledger với key ổn định nhưng luôn giữ wallet `USED`, dù còn hạn, hết hạn hay
row không tồn tại. `VOUCHER_UNAVAILABLE`/budget outcome không rollback Payment refund.
Theo thiết kế cũ, Promotion thực thi `refund_budget`, cập nhật
`promotion_budgets.used_*`, ghi ledger `coupon_usages` và dedup bằng
`promotion_event_idempotency.event_id` với action `REFUND_COUPON`. Outcome bình
thường là `RETAINED_USED`; các chi tiết storage này vẫn thuộc Promotion.

## Phương án đã cân nhắc

- Promotion tự chia tỷ lệ: ít payload nhưng thiếu scope/order truth; không chọn.
- Restore voucher nếu còn hạn: thân thiện hơn nhưng Phase 23 loại do lifecycle/risk.
- Rollback tiền nếu budget fail: giữ sổ cái đồng bộ nhưng rủi ro chargeback; không chọn.

## Hệ quả

Allocation có thể audit và retry, Payment safety ưu tiên. Bug compute phía Order có
thể leak budget/trả thiếu nên cần deterministic tests/reconciliation. Buyer không
nhận lại voucher, kể cả refund full.

## Ảnh hưởng migration

Loại SHIPPING fields, `admin_funding`, `voucher_restore_mode` và restore transition;
giữ promotion snapshot đủ compute; cập nhật producer/consumer theo hai-field allocation.

## Xác minh

Property/table-driven test FULL/PARTIAL coverage, stable idempotency key, duplicate
event, expired/missing wallet, budget failure và assertion Payment refund không bị
đảo ngược.
