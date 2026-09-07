# Inventory Service — Architecture Decision Records

## Current target decisions

| ADR | Status | Decision |
|---|---|---|
| [ADR-011](ADR-011-authoritative-consistency.md) | Accepted | One relational authoritative write source; state + immutable evidence + transactional outbox commit atomically. |
| [ADR-012](ADR-012-order-allocation-lifecycle.md) | Accepted | Whole-order reserve, one warehouse per line, Order-owned timeout and physical-dispatch semantics. |
| [ADR-013](ADR-013-refund-quarantine-lifecycle.md) | Accepted | Immutable whole-order Refund Scope and mandatory return quarantine/inspection lifecycle. |
| [ADR-014](ADR-014-integration-and-search-contracts.md) | Accepted, Search details superseded | Contract surfaces, at-least-once facts, and general recovery policy; ADR-016 owns the Search-specific aggregate. |
| [ADR-015](ADR-015-fail-closed-operations.md) | Accepted | Fail-closed safety, reconciliation without auto-correction, audit/security, and DR proof requirements. |
| [ADR-016](ADR-016-per-sku-search-availability.md) | Accepted | Inventory derives per-SKU availability, publishes only after Search document readiness, and exports per-SKU snapshots/change feed. |

The target documents in the parent directory are the normative detailed specifications. An ADR records the durable trade-off and links to those specifications; it does not duplicate every field or endpoint.

## Historical ADR status

ADRs 001–010 are preserved as historical evidence and are **superseded** by the target decision set above. They must not be used as implementation authority, even where a narrow conclusion resembles the target. Their old context depends on the replaced service design.

| Historical ADR | Superseded by | Reason |
|---|---|---|
| ADR-001, ADR-004 | ADR-011 | Redis is not a commit source or stock correctness authority. |
| ADR-002, ADR-003 | ADR-011, ADR-014 | Transactional outbox remains mandatory, but Redis Stream/Debezium are not prescribed as the source of truth. |
| ADR-005 | ADR-015 | Recovery is fail-closed with evidence and runbook, not ad-hoc suspension/direct repair. |
| ADR-006 | ADR-012, ADR-015 | Order remains timeout owner, now with explicit expiry, retry, and orphan-alert semantics. |
| ADR-007 | ADR-016 | Search consumes Inventory-owned per-SKU boolean snapshots; position/warehouse facts are not its public input. |
| ADR-008 | ADR-012 | Allocation is single-warehouse per line with active/outage eligibility and deterministic priority. |
| ADR-009 | ADR-012 | Physical dispatch is an Inventory stock movement/fact, while shipment/delivery stays with Order. |
| ADR-010 | ADR-013 | Refund Scope is immutable whole-order, lock-controlled, and closes only through Order. |

## ADR rules

Create a new ADR for a change to an accepted invariant, ownership boundary, public contract, consistency/recovery guarantee, or operational policy. An ADR must identify affected target documents, compatibility/migration impact, tests required to prove it, and which existing ADR it supersedes. Do not edit a historical ADR merely to make it look current.
