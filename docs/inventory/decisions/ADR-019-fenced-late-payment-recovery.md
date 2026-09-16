# ADR-019 — Fenced late-payment allocation recovery

## Status

Accepted — 2026-09-16. Refines the late-payment rule of ADR-012; ADR-012 remains
the allocation-lifecycle authority for all other transitions.

## Context

Order can time out a pending payment and release an Inventory reservation, while a
provider success for the original payment transaction is delivered late. The previous
idea to "re-reserve" is unsafe and incompatible with the allocation lifecycle: a new
reserve can choose a different plan, fail after a financial capture, or create a
second root. Treating a released root as automatically committable can also consume
stock that another order validly acquired after the timeout.

## Decision

Inventory adds `RecoverTimedOutPayment(order_id, payment_reference)`, callable only
by the authenticated Order Saga with a stable command ID after it verifies the
original Payment transaction. It is a whole-root, transactional `RELEASED ->
COMMITTED` recovery and not a Reserve operation.

The command accepts only when all guards hold: the exact root was released for
`RESERVATION_EXPIRED`; no line has physical dispatch; and every original quantity is
again available at its original warehouse. It locks the root, every line, and all
original stock positions, then moves all buckets and all lines together. It records
the Payment reference, movement/audit evidence, outbox facts, and durable command
outcome in the same transaction.

Every other release reason, an incomplete plan, any dispatch, or insufficient stock
is a durable conflict with no stock movement. Inventory does not reroute, create a
new root, partially recover, auto-refund, or decide Order's financial remediation.

## Consequences

Order has a deterministic recovery result after a provider/webhook race and can retry
an uncertain call using the same command ID. A rejected recovery leaves the provider
payment fact intact and requires Order's explicit financial-reconciliation/manual
path. The approach intentionally favors no oversell and auditability over maximizing
late-payment fulfillment.

## Verification

Contract, property, and concurrency tests must prove: replay returns the original
result; only `RESERVATION_EXPIRED` can recover; all original positions lock and move
together; dispatch/release/recovery races have one winner; and every rejected guard
leaves no state, bucket, movement, or outbox change.
