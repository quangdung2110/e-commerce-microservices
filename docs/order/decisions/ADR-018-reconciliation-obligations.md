---
doc_id: order-adr-018
service: order
status: accepted
date: 2026-10-05
refined_by:
  - ADR-019
decision_owners:
  - Order Service team
  - Finance Operations
supersedes: null
evidence:
  - docs/order/data.md
  - docs/order/workflows.md
  - contracts/http/order/internal/v1/openapi.yaml
---

# ADR-018 — Reconciliation case envelope với nhiều obligations

> Current authority: ADR-019 refines external-remediation convergence for an open
> refund lane; the envelope/obligation model in this ADR remains active.

## Trạng thái

`Accepted` — 2026-10-05. Refine ADR-011 và ADR-013.

## Bối cảnh

Một case row với một status/decision không biểu diễn được Inventory repair, Promotion
repair, dispute, refund reversal và scope finalization đồng thời. Đóng một nhánh có thể
vô tình che nhánh khác còn nợ.

## Quyết định

Mỗi order có tối đa một reconciliation case envelope và nhiều typed obligations độc lập:
post-capture Inventory/Promotion repair, capture-after-terminal, refund reversal,
customer payout remediation, scope finalization và payment dispute. Evidence và decision
là append-only, luôn gắn `obligation_id`; optimistic input dùng case revision.

Mỗi remote obligation có command/checkpoint/generation riêng. Independent obligations
được attempt độc lập; failure của một nhánh không chặn nhánh khác. Case chỉ `CLOSED` khi
mọi obligation terminal-resolved. Master FSM chỉ đổi qua proof-backed resolution được
liệt kê; decision không được tự invent Payment/Inventory/Promotion fact.

Financial anomaly obligations (`CAPTURE_AFTER_TERMINAL_NON_CAPTURE`, `REFUND_REVERSAL`,
`CUSTOMER_PAYOUT_REMEDIATION`, `PAYMENT_DISPUTE`) có thể đóng bằng
`CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION` chỉ khi có accounting case reference và
immutable Payment/provider settlement evidence. Resolution này ghi nhận tiền đã được xử
lý ngoài saga; nó không hồi sinh/đổi terminal Order FSM và không phát Order fact giả.

## Xác minh

Test nhiều obligations mở/đóng lệch thứ tự, stale case revision, decision sai obligation,
duplicate evidence, partial repair và case không đóng sớm. Bao phủ thêm escalation rồi
external-remediation close, thiếu/sai evidence, dùng resolution financial cho obligation
Inventory/Promotion/scope và xác nhận không có FSM/outbox fact mutation.
