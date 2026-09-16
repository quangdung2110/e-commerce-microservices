# ADR-011 — Financial Saga recovery and durable dispatch

## Status

Accepted — 2026-09-16. Refines the active recovery/compensation interpretation of
ADR-001, ADR-005, ADR-008 and ADR-010; their historical decisions remain retained.

## Context

The previous Order Saga assumed a remote timeout meant no mutation, used Redis-only
event dedup/start protection, and rolled back post-payment steps in LIFO order. That
creates loss/duplication hazards: provider capture can race cancellation; Inventory and
Promotion commands can succeed after transport loss; technical promotion cancellation
can erase a redeemed voucher after payment; a Temporal start can be lost after the local
guard commits; refund evidence/attempts can be incomplete or consume a lifetime case;
and refund reversal has no valid transition from `REFUNDED`.

## Decision

Order persists original Payment identity and validates every financial event by order,
transaction, amount, currency and monotonic financial version in a durable inbox. API and
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
reconciliation workflow and operator workload. The system deliberately leaves a captured
but unrecoverable fulfilment in an explicit financial state rather than making an unsafe
automatic refund or changing wallet/stock by assumption.

## Verification

Prove provider create/cancel/capture races; remote timeout before/after accepted mutation;
workflow start crash; duplicate/stale/mismatched Payment/Inventory facts; every late-payment
fence; rejected versus accepted admin release; full inspection evidence; reversal after
`REFUNDED`; and that physical dispatch cannot complete/deliver an order. Each test must
show atomic local state/inbox/dispatch/checkpoint/outbox behavior and no second payment,
refund, reservation or voucher transition.
