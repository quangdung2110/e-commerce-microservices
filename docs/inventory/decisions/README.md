# Inventory Service — Architecture Decision Records

## Current target decisions

| ADR | Status | Decision |
|---|---|---|
| [ADR-011](ADR-011-authoritative-consistency.md) | Accepted, publication wording refined by ADR-020; runtime fixed by ADR-023 | One relational authoritative write source; state + immutable evidence + required transactional outbox/work commit atomically. |
| [ADR-012](ADR-012-order-allocation-lifecycle.md) | Accepted, Commit deadline, release reasons and plan semantics refined by ADR-021 | Whole-order reserve, one warehouse per line, Order-owned timeout and physical-dispatch semantics. |
| [ADR-013](ADR-013-refund-quarantine-lifecycle.md) | Superseded | Historical whole-order Refund Scope and quarantine/inspection lifecycle; ADR-018 owns current scope policy. |
| [ADR-014](ADR-014-integration-and-search-contracts.md) | Accepted, publication scope partially superseded; ADMIN path and error catalog refined by ADR-024 and ADR-027 | Contract surfaces, at-least-once facts, and general recovery policy; ADR-016 owns Search details and ADR-020 removes reservation-result publication to Order. Export sequencing is refined by ADR-022. |
| [ADR-015](ADR-015-fail-closed-operations.md) | Accepted, ADMIN MFA made a production gate by ADR-024 | Fail-closed safety, reconciliation without auto-correction, audit/security, and DR proof requirements. |
| [ADR-016](ADR-016-per-sku-search-availability.md) | Accepted, snapshot watermark semantics refined by ADR-022; readiness re-registration rules by ADR-027 | Inventory derives per-SKU availability, publishes only after Search document readiness, and exports per-SKU snapshots/change feed. |
| [ADR-017](ADR-017-pdp-availability-read-api.md) | Accepted, `UNKNOWN` rule refined by ADR-025 | Product Detail BFF reads Inventory-owned advisory availability through a bounded synchronous API, not Search events/exports. |
| [ADR-018](ADR-018-selected-line-refund-scope-and-pre-dispatch-release.md) | Accepted, single-line committed release removed and scope dispatch guard added by ADR-021 | Selected full-quantity order-line scope, one scope per order lifetime, and atomic pre-dispatch release for admin refund. |
| [ADR-019](ADR-019-fenced-late-payment-recovery.md) | Accepted, publication wording refined by ADR-020 | Fenced whole-root recovery for a verified payment captured after the timeout release; durable command receipt and affected-SKU work replace an Order-facing commit acknowledgment. |
| [ADR-020](ADR-020-command-receipts-and-minimal-order-events.md) | Accepted | Reservation commands use durable receipts/query/replay; Order receives only dispatch and inspection completion; required outbox and availability work remain atomic. |
| [ADR-021](ADR-021-order-integration-lifecycle-corrections.md) | Accepted | Commit is not deadline-gated; complete whole-root release reasons; no single-line committed or `STOCK_DISCREPANCY` release; Refund Scope lines must be dispatched; stateless allocation plan. |
| [ADR-022](ADR-022-commit-ordered-export-sequence.md) | Accepted | A single fenced sequencer assigns the commit-ordered export sequence; snapshot rows are current state guarded by per-SKU revision. |
| [ADR-023](ADR-023-java-spring-boot-postgresql-runtime.md) | Accepted | Java 21 + Spring Boot 3.x, PostgreSQL 16 with an explicit-SQL write path, Flyway, Debezium relay of the sequenced event table, and leased in-service workers. |
| [ADR-024](ADR-024-admin-surface-and-error-catalog.md) | Accepted, warehouse/disposition request shapes and error catalog refined by ADR-027 | ADMIN surface at `/api/v1/admin/inventory` with role `ADMIN` and MFA as a production gate; read-only operational queues; whole-line dispatch, non-terminal HOLD and reversal limits; stable error-code catalog and rejected command outcomes. |
| [ADR-025](ADR-025-pdp-availability-staleness-budget.md) | Accepted | PDP freshness budget is 30 s: return the last computed state while the oldest pending work is at most 30 s old; `UNKNOWN` only for missing state or older pending work. |
| [ADR-026](ADR-026-no-product-lifecycle-dependency.md) | Accepted | Inventory consumes no Product event and does not guard deleted/inactive SKUs; Product and Checkout block them before Reserve. |
| [ADR-027](ADR-027-pre-implementation-contract-corrections.md) | Accepted | Pre-implementation corrections: `close` reasons `REFUNDED`/`REJECTED` only, terminal-scope receipt code, warehouse `name` and `expected_revision`, global inspection/receipt-line/disposition-line identifiers, `WAREHOUSE_NOT_FOUND`, service-identity allowlist, and runtime defaults (ADMIN idempotency namespace, 2 s lock timeout, contiguous reversal sequence, readiness revision rules, event revisions, write-freeze flag, outbox backpressure). |
| [ADR-028](ADR-028-post-implementation-audit-corrections.md) | Accepted | Post-implementation audit corrections: identity checks on the decoded path, export watermark never below the retention floor, per-order Reserve serialization (`RESERVATION_ALREADY_EXISTS` wins a race), replayed scope registration reports the current revision, continuous availability audit, 30-day bounded topic retention, snapshot-session `410`/`404`, and documentation/contract alignment. |
| [ADR-029](ADR-029-unfiltered-admin-lists.md) | Accepted | Admin lists and queues are complete without a filter: `warehouse_id`/`sku_id` become optional narrowing filters, movements gain `source_operation_type`, new `GET /refund-scope-summaries`, and Flyway `V2` adds the unscoped ordering indexes. |
| [ADR-030](ADR-030-request-correlation-and-caller-deadline.md) | Accepted | Request ID, trace ID and caller deadline in Inventory logs; `X-Correlation-ID` stays the business flow ID stored with commands |
| [ADR-031](ADR-031-retry-after-default-on-503.md) | Accepted | `Retry-After` default of 2 seconds for a retryable `503` (1 for `429`) when the code that raised it gave no estimate |

The target documents in the parent directory are the normative detailed specifications. An ADR records the durable trade-off and links to those specifications; it does not duplicate every field or endpoint.

[Order ADR-013](../../order/decisions/ADR-013-durable-refund-scope-finalization.md)
defines the cross-service orchestration of existing scope close/cancel commands,
durable recovery and the no-payout Ops decision. Inventory state guards, lifetime
uniqueness and endpoint reason enums remain unchanged.

## Historical ADR status

ADRs 001–010 are preserved as historical evidence and are **superseded** by the target decision set above. They must not be used as implementation authority, even where a narrow conclusion resembles the target. Their old context depends on the replaced service design. Each historical file, and ADR-013, records its supersession in its status block; bodies are unchanged except that links to the removed legacy Java code are now plain text.

| Historical ADR | Superseded by | Reason |
|---|---|---|
| ADR-001, ADR-004 | ADR-011 | Redis is not a commit source or stock correctness authority. |
| ADR-002, ADR-003 | ADR-011, ADR-014 | Transactional outbox remains mandatory, but Redis Stream/Debezium are not prescribed as the source of truth. |
| ADR-005 | ADR-015 | Recovery is fail-closed with evidence and runbook, not ad-hoc suspension/direct repair. |
| ADR-006 | ADR-012, ADR-015 | Order remains timeout owner, now with explicit expiry, retry, and orphan-alert semantics. |
| ADR-007 | ADR-016 | Search consumes Inventory-owned per-SKU boolean snapshots; position/warehouse facts are not its public input. |
| ADR-008 | ADR-012 | Allocation is single-warehouse per line with active/outage eligibility and deterministic priority. |
| ADR-009 | ADR-012 | Physical dispatch is an Inventory stock movement/fact, while shipment/delivery stays with Order. |
| ADR-010 | ADR-013, ADR-018 | Scope remains immutable and Order-controlled; ADR-018 replaces its whole-order and active-only-lock semantics. |

## ADR rules

Create a new ADR for a change to an accepted invariant, ownership boundary, public contract, consistency/recovery guarantee, or operational policy. An ADR must identify affected target documents, compatibility/migration impact, tests required to prove it, and which existing ADR it supersedes. Do not edit a historical ADR merely to make it look current.
