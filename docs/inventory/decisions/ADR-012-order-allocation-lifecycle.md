# ADR-012 — Whole-order allocation and Order-owned lifecycle

## Status

Accepted — 2026-09-07. Supersedes ADR-006, ADR-008, and ADR-009.

## Context

Inventory must prevent oversell without taking ownership of payment, customer order, shipment, or delivery.

## Decision

Allocation chooses one active/non-outage warehouse per line by static priority; line quantity is never split. Preview is advisory. Reserve locks and revalidates the exact plan, then accepts all lines or returns `REJECT_ALL`. Order Temporal Saga owns payment retry and 15-minute timeout; Inventory never auto-releases and alerts only after 60 minutes. Payment moves `reserved → committed`; ADMIN-recorded physical dispatch moves `committed →` outside `on_hand`. Order owns shipment/delivery state.

## Consequences

There is no backorder, partial reservation, silent reroute, or cache-based stock decision. Release/dispatch races are resolved by the first atomic transition. See [domain.md](../domain.md) and [workflows.md](../workflows.md).

## Verification

Property/concurrency tests prove all-or-nothing reserve, exact-plan revalidation, late-payment semantics, and release/dispatch race guards.
