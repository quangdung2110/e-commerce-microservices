# ADR-027 — Pre-implementation contract corrections and runtime defaults

## Status

Accepted — 2026-10-05. Refines ADR-014, ADR-016 (readiness re-registration) and ADR-024
(ADMIN request shapes and error catalog).
Every domain rule, invariant, ownership boundary and delivery guarantee of those ADRs
remains active.

## Context

A last review of the target documents and the four OpenAPI/AsyncAPI contracts against
their callers (Order, Search, Product Detail BFF, ADMIN UI) and the local stack found
defects that would force an implementer to guess:

1. `CloseRefundScopeRequest.reason` allowed `CANCELLED`, which Order never sends; a
   zero-receipt scope already has its own `cancel` endpoint.
2. A receipt on a terminal scope mapped to both `REFUND_SCOPE_STATE_CONFLICT` and
   `RETURN_RECEIPT_REJECTED` ("inactive scope").
3. `WAREHOUSE_CONFLICT` meant "conflicts with its current revision", but
   `UpdateWarehouseRequest` carried no revision, so the code could never occur.
4. `InspectionRequest` addresses a return receipt line without `refund_scope_id`, and the
   disposition reversal path `{disposition_id}` cannot select one line of a multi-line
   disposition keyed by `(warehouse_id, disposition_id, disposition_line_id)`.
5. A warehouse had no human-readable name; the server generates `warehouse_id`, so the
   ADMIN UI could display only an opaque identifier and an address.
6. A command body referencing a non-existent warehouse had no catalog code, and the code
   for other missing references was not stated.
7. Search, an implemented caller, sent no `X-Service-Identity`, although
   [mesh runtime contract §6](../../mesh/runtime-contract.md#6-quy-ước-identity-header)
   requires every internal caller to send it.
8. The local PostgreSQL allowed four replication slots while five Debezium connectors
   already existed; Inventory adds a sixth.
9. Several runtime parameters were described only qualitatively: ADMIN idempotency
   namespace, the "bounded" wait, reversal numbering, readiness re-registration, event
   aggregate revisions, manual fail-closed control, outbox backpressure and the stored
   release reason of a pre-dispatch release.

## Decision

### Contract corrections

| # | Correction |
|---|---|
| 1 | `close` reasons are exactly `REFUNDED` and `REJECTED`. `CANCELLED` is removed; a `REGISTERED` scope with no receipt uses `cancel`. |
| 2 | A return receipt on a terminal scope (`CANCELLED`, `CLOSED`, `CLOSED_INCOMPLETE`) or on `INSPECTION_COMPLETED` is `409 REFUND_SCOPE_STATE_CONFLICT`. `RETURN_RECEIPT_REJECTED` covers only an unknown scope or line, a SKU mismatch and over-receipt. |
| 3 | `UpdateWarehouseRequest` requires `expected_revision`. A mismatch with the current `Warehouse.revision` is a durable `409 WAREHOUSE_CONFLICT`. |
| 4a | `(return_receipt_id, return_receipt_line_id)` and `inspection_id` are globally unique. A receipt line is still recorded with its `refund_scope_id`. |
| 4b | `(disposition_id, disposition_line_id)` is globally unique. `DispositionReversalRequest` requires `disposition_line_id`; the path `{disposition_id}` plus that field select the reversed line. |
| 5 | `Warehouse` gains `name` (1–120 characters): required on create, optional on update. `warehouse_id` stays an Inventory-generated opaque identifier. |
| 6 | New catalog code `409 WAREHOUSE_NOT_FOUND` for an unknown `warehouse_id` in an ADMIN command body. Other missing references keep the operation code: unknown order/line on dispatch → `DISPATCH_NOT_ALLOWED`; unknown scope/line on return receipt → `RETURN_RECEIPT_REJECTED`; unknown receipt line on inspection → `INSPECTION_REJECTED`; unknown inspection on disposition → `DISPOSITION_REJECTED`; unknown order on Commit/Release → `RESERVATION_NOT_RESERVED`; recovery → `RESERVATION_NOT_RECOVERABLE`; pre-dispatch release → `PRE_DISPATCH_RELEASE_NOT_ALLOWED`; unknown scope on close/cancel/close-incomplete → `REFUND_SCOPE_STATE_CONFLICT`; unknown order on scope registration → `REFUND_SCOPE_LINE_INVALID`. Unknown path targets of ADMIN (`PATCH /warehouses/{id}`, movement and disposition reversals) are durable `404 NOT_FOUND`. |
| 7 | Inventory checks `X-Service-Identity` against a per-path allowlist as defense-in-depth (mesh remains the authority); missing header is `401 UNAUTHENTICATED`, another service is `403 FORBIDDEN`. Search sends `X-Service-Identity: search-service` on every Product and Inventory internal/export call. |
| 8 | Local PostgreSQL runs with `max_replication_slots=10` and `max_wal_senders=10`. |

### Runtime defaults

| # | Default |
|---|---|
| 9 | Idempotency namespace is the authenticated service identity for Workflow calls and the single shared namespace `admin` for every ADMIN user. The canonical fingerprint covers operation, path parameters and request body, never the actor, so any ADMIN can query an ADMIN command. |
| 10 | Each transaction runs with `lock_timeout = 2 s`. Deadlock, serialization failure or lock timeout is retried internally at most twice, then answered `503 INVENTORY_BUSY` with `Retry-After: 1`. A concurrent same-key request waits on the first execution under the same lock timeout. |
| 11 | `reversal_sequence` must equal the number of accepted reversals of the same original plus one; another value is `409 MOVEMENT_NOT_REVERSIBLE` (sellable) or `409 DISPOSITION_REJECTED` (disposition). A reversal movement cannot itself be reversed. |
| 12 | Readiness `PUT` creates a publication task only for a new registration or a greater `catalog_revision`; an equal or lower revision returns `200` with the stored registration and creates no task. After revocation, only a `catalog_revision` greater than the revoked one re-registers; otherwise `200` returns the stored (revoked) revision and nothing is published. `DELETE` revokes when `catalog_revision >=` the stored one, returns `409` when lower and `204` when absent. A SKU with no state at registration is evaluated (`false`, revision 1) and then published. |
| 13 | `reservation.line-dispatched.v1` uses the reservation root revision after the dispatch as both `aggregaterevision` and `data.reservation_revision`; `aggregateid` is `{order_id}:{order_line_id}`. `all-items-inspection-completed.v1` uses `scope_revision`. `sku-availability.changed.v1` uses `availability_revision`. |
| 14 | Configuration flag `inventory.write-freeze` (environment `INVENTORY_WRITE_FREEZE`) is the manual fail-closed control executed by Platform/SRE: every mutation, including readiness registration, returns `503 INVENTORY_UNAVAILABLE`; reads remain available. Reconciliation (hourly bucket scan, daily ledger replay, 5-minute orphan scan) publishes metrics and error logs only. When unsequenced outbox rows exceed `inventory.outbox.max-unsequenced` (default 100 000), new mutations fail closed with `503 INVENTORY_UNAVAILABLE`. |
| 15 | ADMIN MFA is not implemented until the Auth/gateway MFA assertion exists; it remains the production release gate of ADR-024. |
| 16 | `release_reason` is immutable and is still returned after a successful `RecoverTimedOutPayment`. `ReleaseCommittedOrderBeforeDispatch` stores `ADMIN_FORCE_REFUND_BEFORE_DISPATCH` in a separate committed-release reason that the `release_reason` field never exposes. |
| 17 | A stale readiness `DELETE` uses the Export catalog code `409 READINESS_REVISION_CONFLICT`. The command query reports a `200 REJECT_ALL` Reserve as `result: ACCEPTED`, `outcome: REJECT_ALL`, `http_status: 200`; `REJECTED` is reserved for durable `4xx` outcomes with an `error_code`. |

## Alternatives considered

- **Add `refund_scope_id` to `InspectionRequest` and a line path for disposition
  reversal.** Equivalent, but changes more request shapes; rejected in favour of global
  identifier uniqueness, which also makes `inspection_id` usable by the quarantine queue.
- **Drop `WAREHOUSE_CONFLICT` instead of adding `expected_revision`.** Simpler, but two
  ADMIN users could silently overwrite each other's warehouse status. Rejected.
- **Client-chosen warehouse code.** Rejected; `name` keeps identifiers opaque.
- **`400` for an unknown warehouse.** Rejected: the outcome depends on state, so it is a
  durable conflict like other guards.
- **Per-user ADMIN idempotency namespace.** Rejected: another ADMIN could not recover a
  colleague's uncertain command.

## Consequences

- The ADMIN UI must send `expected_revision` and `name`, and regenerate its types.
- Search must send its service identity; without it Inventory and Product reject the call
  locally even though compose has no mesh.
- Operators gain an explicit fail-closed switch without a database write path.

## Migration impact

All affected contracts are `proposed` and no consumer is deployed. Update
[interfaces.md](../interfaces.md), [data.md](../data.md), [domain.md](../domain.md),
[operations.md](../operations.md), [development.md](../development.md), the ADMIN and
Workflow OpenAPI, the contracts changelog, the Search client and the local
`docker-compose.yml`.

## Verification

Implementation tests prove: `close` rejects `CANCELLED` at validation; a receipt on a
terminal scope returns `REFUND_SCOPE_STATE_CONFLICT`; a stale `expected_revision` returns
`WAREHOUSE_CONFLICT`; an unknown warehouse returns `WAREHOUSE_NOT_FOUND`; inspection and
disposition reversal resolve by the global identifiers; non-contiguous reversal sequence
is rejected; readiness re-registration and revocation follow the revision rules; the
write-freeze flag returns `503`; and emitted CloudEvents carry the stated revisions.
