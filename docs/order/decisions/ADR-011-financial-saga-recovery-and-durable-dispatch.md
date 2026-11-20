---
doc_id: order-adr-011
service: order
status: accepted
date: 2026-09-16
decision_owners:
  - Order Service team
supersedes: null
refined_by:
  - ADR-014
  - ADR-015
  - ADR-018
evidence:
  - docs/order/domain.md
  - docs/order/workflows.md
  - docs/order/data.md
---

# ADR-011 — Financial Saga recovery and durable dispatch

## Status

Accepted — 2026-09-16. Refines the active recovery/compensation interpretation of
ADR-001, ADR-005, ADR-008 and ADR-010; their historical decisions remain retained.

> Delivery source: ADR-021 replaces the "verified Delivery proof" with the Admin delivery
> confirmation on Order; no Delivery service exists.
> Current authority: ADR-014 supersedes the late-timeout/fenced-recovery paragraph;
> ADR-015 replaces direct post-inbox signaling with durable workflow messages; ADR-018
> models independent repairs as obligations. The remaining financial-watermark and
> stable-command rules stay active.

## Context

The previous Order Saga assumed a remote timeout meant no mutation, used Redis-only
event dedup/start protection, and rolled back post-payment steps in LIFO order. That
creates loss/duplication hazards: provider capture can race cancellation; Inventory and
Promotion commands can succeed after transport loss; technical promotion cancellation
can erase a redeemed voucher after payment; a Temporal start can be lost after the local
guard commits; refund evidence/attempts can be incomplete or consume a lifetime case;
and refund reversal has no valid transition from `REFUNDED`.

## Decision

Order persists numbered Payment-attempt↔transaction mappings and validates every financial
event by order, mapped attempt, transaction, VND amount/currency and monotonic version per
transaction in a durable inbox. API and
consumer commands persist a workflow dispatch record before returning accepted; a
dispatcher start/attaches the fixed workflow ID after failure. Every remote Activity has a
durable command checkpoint and resolves an uncertain result by the provider's replay/query
mechanism before it changes Order state.

Pre-payment cancellation first enters `CANCELLING` and waits for Payment's durable
non-capture/capture result. Only non-capture permits releasing Promotion/Inventory and
`CANCELLED`. A capture result enters paid forward repair. Payment success is the financial
watermark: post-capture Inventory/Promotion failures retry the same commands or enter
`FINANCIAL_RECONCILIATION_REQUIRED`; active flow never uses Inventory rollback or
Promotion `CANCEL_FINAL`.

Late timeout success uses Inventory's separate fenced recovery command, never a fresh
reserve/reroute. Refund inspection/release evidence is persisted in full before Payment.
An Admin release is a durable attempt separate from the lifetime case, so rejection does
not consume Buyer entitlement. Refund reversal reopens the same case/master to manual
verification and never creates a second refund. Inventory physical dispatch maps only to
`DISPATCHED`, not customer delivery.

## Consequences

Order gains inbox, dispatch, checkpoint and admin-attempt persistence plus a financial
 reconciliation workflow and an evidence-backed Finance/Ops decision workload. The system deliberately leaves a captured
but unrecoverable fulfilment in an explicit financial state rather than making an unsafe
automatic refund or changing wallet/stock by assumption.

## Verification

Prove provider create/cancel/capture races; remote timeout before/after accepted mutation;
workflow start crash; duplicate/stale/mismatched Payment/Inventory facts; every late-payment
fence; rejected versus accepted admin release; full inspection evidence; reversal after
`REFUNDED`; physical dispatch cannot deliver/complete an order; and only a verified Delivery
proof may change one line to `DELIVERED`. Each test must
show atomic local state/inbox/dispatch/checkpoint/outbox behavior and no second payment,
refund, reservation or voucher transition.
