---
doc_id: order-adr-019
service: order
status: accepted
date: 2026-10-05
decision_owners:
  - Order Service team
  - Finance/Ops
supersedes: null
refines:
  - ADR-018
evidence:
  - docs/order/domain.md
  - docs/order/data.md
  - docs/order/workflows.md
  - contracts/http/order/internal/v1/openapi.yaml
---

# ADR-019 — External remediation convergence cho refund lane

## Bối cảnh

ADR-018 cho phép đóng financial obligation bằng bằng chứng settlement ngoài saga nhưng
chưa định nghĩa cách một refund bị fail/reverse thoát khỏi `RETURN_REQUESTED` và
`MANUAL_VERIFICATION_REQUIRED`. Chỉ đóng obligation sẽ để một aggregate không terminal
mà không còn convergence owner; đổi Payment status thành success lại làm sai provider fact.

## Quyết định

`CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION` không đổi Payment/provider facts và không
hồi sinh `CANCELLED`. Với `CUSTOMER_PAYOUT_REMEDIATION` hoặc `REFUND_REVERSAL` gắn với
lifetime refund case, quyết định chỉ được commit khi immutable Accounting/settlement
evidence chứng minh đúng customer, currency `VND` và toàn bộ
`refund_amount_locked_minor` đã thực nhận, đồng thời bind latest persisted Payment
transaction/version và proof rằng original refund không còn recoverable. Cùng order/case
row lock, decision recheck không có provider success mới rồi đóng obligation, chuyển case
`FAILED|MANUAL_VERIFICATION_REQUIRED -> EXTERNALLY_REMEDIATED` và, nếu master đang ở
refund lane, chuyển `RETURN_REQUESTED -> REFUNDED`. `payment_status` vẫn
`REFUND_FAILED`; `orders.refunded_at` lấy immutable external `settled_at`, còn original
refund transaction/reversal và evidence không bị sửa.

Không phát `ORDER_REFUNDED` notification hay Order integration fact vì hai contract đó
đại diện provider-confirmed refund success. Capture-after-terminal và dispute obligations
chỉ đóng obligation; master terminal/fulfilment state của chúng không đổi.

Nếu provider success hợp lệ thắng row-lock/version race, external decision bị conflict và
không được ghi như settlement mới. Một provider success trái contract đến sau external
commit được giữ thành financial-anomaly evidence/obligation mới; không silently đổi
`EXTERNALLY_REMEDIATED` hay che khả năng duplicate payout.

## Hệ quả và xác minh

Public refund case có terminal status `EXTERNALLY_REMEDIATED`, cho phép UI/Ops phân biệt
provider refund với payout ngoài. Test bắt buộc bao phủ exact amount/currency/customer,
duplicate decision, concurrent provider recovery, partial settlement, wrong evidence,
scope finalization độc lập, `CANCELLED` không đổi và không phát false event/notification.
