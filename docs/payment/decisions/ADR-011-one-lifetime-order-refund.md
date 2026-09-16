---
doc_id: payment-adr-011
service: payment
status: accepted
date: 2026-09-15
decision_owners:
  - Payment Service team
  - Order Service team
  - Inventory Service team
supersedes:
  - ADR-010
evidence:
  - docs/payment/data.md
  - docs/payment/interfaces.md
  - docs/payment/workflows.md
  - docs/order/decisions/ADR-010-selected-line-refund-policy.md
---

# ADR-011 — One Payment refund transaction per Order lifetime

## Trạng thái

`Accepted` — 2026-09-15. Supersedes the refund-gate details of ADR-010; explicit
Order command remains mandatory.

## Quyết định

- Payment accepts at most one refund transaction for an `order_id` across all
  payment attempts. `refund_transactions.order_id` is unique; a replay uses the
  same `Idempotency-Key` and returns the existing transaction.
- The command contains `refund_request_id` and one immutable Inventory evidence:
  selected-return scope inspection completion, or atomic all-line committed release
  before dispatch for Admin. Payment verifies command shape/identity, but Order
  remains the owner that validates the Inventory fact and decides the amount.
- Payment does not reject a valid Order refund merely because one or more inspected
  returned units failed quality inspection. Inventory `FAIL` is a quarantine/payout-
  independent fact.
- Amount is supplied as canonical VND money by Order's locked policy: gross selected line
  snapshots capped at `final_amount_minor`, or full `final_amount_minor` for Admin
  pre-dispatch. Payment still checks `amount_minor <= paid_amount_minor` as defense in depth.
- A provider failure/reversal creates manual review on the existing transaction;
  it never permits another refund transaction for the order.

## Hệ quả

The older many-partial-refund model is not an Order integration capability. Refund
polling, webhook reconciliation and provider retry continue to operate on the one
transaction, with its stable idempotency identity.
