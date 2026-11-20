# ADR-028 — Post-implementation audit corrections

## Status

Accepted — 2026-10-09. Refines ADR-014 (retention wording), ADR-016 (first evaluation of a
SKU), ADR-018 (replayed registration), ADR-022 (snapshot watermark) and ADR-027 (identity
allowlist). Every domain rule, invariant, ownership boundary and delivery guarantee of those
ADRs remains active.

## Context

The docs ↔ contracts ↔ code audit of 2026-10-07 reported Inventory as nearly in sync (items
I1 and I2 only). Re-reading the whole service against its documents, contracts, tests and
callers confirmed I2, left I1 to the owner (the brainstorm folder is the original idea record)
and found the following defects, four of them in code and proven by a failing test before the
fix:

1. **Identity checks matched the raw request URI.** `IdentityFilter` compared
   `getRequestURI()` with the protected prefixes, but Spring MVC routes on the decoded path
   without `;` parameters. `GET /api/v1/admin/inventor%79/warehouses` or
   `/api/v1/admin/inventory;x=1/warehouses` skipped both the ADMIN role check and the
   service-identity allowlist and was answered `200` to a caller without `ADMIN`. Mutations
   failed only because the idempotency namespace was missing; reads of warehouses, positions,
   movements, reservations and Refund Scopes were open.
2. **The snapshot watermark could fall below the retention floor.** When retention deleted
   every published fact, `max(sequence)` was `NULL`, so a new snapshot reported watermark `0`
   while the floor was above `0`; the next `changes?after_sequence=0` answered
   `410 REPLAY_WINDOW_EXPIRED`, and Search would bootstrap in a loop.
3. **Duplicate-order Reserve could answer `REJECT_ALL`.** Two Reserve commands for one
   `order_id` with different command IDs both passed the "root exists?" read; the second then
   waited for the first one's position locks, found the stock gone and recorded a durable
   `REJECT_ALL` for an order that has a root.
4. **A replayed Refund Scope registration reported a stale revision.** ADR-018 returns the
   existing scope for the same `(order_id, refund_request_id)` and lines, but the receipt always
   carried `scope_revision: 1`, even when the scope had moved on.
5. **`operations.md` §3 promised checks the code did not perform:** a sampled recomputation of
   `SkuAvailabilityState` and a count of facts suppressed for lack of Search readiness.
6. **Retention was declared as a floor only** (`x-retention: 30d-minimum`) and nothing
   provisions the topics (local compose uses broker defaults). A consumer cannot size a window
   "longer than the topic retention" against an unbounded value; this is the Inventory half of
   audit item S4.
7. **Contract and documentation gaps.** `interfaces.md` §4 omitted six ADMIN resource reads
   that exist in the ADMIN OpenAPI and `AdminController`; the cancel scope operation listed no
   `400` although its headers are validated; no query listed `503`; behavior of a purged
   snapshot session (`404`), the "false, revision 1" first evaluation, the fan-out pending-work
   rule, the lock order around `OperationIdentity` and the role of `X-Service-Identity` in
   `operations.md` §5 were imprecise or contradicted the code; the interaction catalog and
   `ADOPTION.md` still described implemented, consumed contracts as `proposed` or without a
   consumer.

## Decision

| # | Decision |
|---|---|
| 1 | `IdentityFilter` evaluates the decoded path without `;` parameters (the path Spring MVC routes on) for the service allowlists and the ADMIN role check. A regression test covers percent-encoded characters, `;` parameters and a fully encoded first segment for the ADMIN and Workflow surfaces. |
| 2 | The export watermark and `latest_sequence` are `max(greatest published sequence, retention floor)`. A snapshot watermark is therefore always answerable by `changes?after_sequence=<watermark>`. |
| 3 | Reserve takes a transaction-scoped lock keyed by `order_id` before it looks for an existing root. An order that already has a root is `409 RESERVATION_ALREADY_EXISTS` under any other command ID, including a racing one; `REJECT_ALL` is only produced for an order without a root. |
| 4 | A replayed registration returns `outcome: REGISTERED` with the scope's **current** `scope_revision`. Different lines or another `refund_request_id` remain `REFUND_SCOPE_ALREADY_EXISTS`. The behavior is added to `interfaces.md` and the Workflow OpenAPI. |
| 5 | A detection-only availability audit runs every `inventory.workers.availability-audit-interval` (default 1 minute) over the next `availability-audit-batch-size` (default 500) state rows in `sku_id` order, wrapping around. One statement compares each stored boolean with the warehouse-aware rule; rows with a dirty mark or an unfinished fan-out are skipped. It publishes `inventory_integrity_availability_mismatches` and an error log, plus `inventory_availability_readiness_suppressed` (states without an active Search registration). It never changes state. |
| 6 | Both Inventory topics are provisioned with `retention.ms` = 30 days as floor and ceiling; AsyncAPI `x-retention` becomes `30d`. Platform provisioning is a production gate in `development.md`. Search's tombstone protection window must be strictly longer; that change belongs to Search and is not made here. |
| 7 | An expired snapshot session answers `410 SNAPSHOT_SESSION_EXPIRED` until the hourly retention pass deletes it and `404 NOT_FOUND` afterwards (Search already treats both as "create a new session"). Documented in the Export OpenAPI, `interfaces.md` and `data.md`; no code change. |
| 8 | Documentation and contract text is aligned with the code: the six ADMIN resource reads (audit I2) in `interfaces.md` §4 and the ADMIN README; `400` on cancel and `503` on every query in the Workflow and ADMIN OpenAPI; first evaluation "from current stock (`false` when it has none), revision 1"; fan-out pending work counts only SKUs the job's cursor has not reached (a reached SKU carries its own dirty mark of the same age); `OperationIdentity` is checked by an unlocked read first and claimed by insert after the aggregates are locked; `X-Service-Identity` is an in-service allowlist, not attribution only. |
| 9 | The interaction catalog marks `warehouse-admin-operates-inventory`, `product-detail-bff-queries-inventory-availability`, `inventory-publishes-public-facts`, `search-exports-inventory-availability` and `checkout-previews-inventory-allocation` `implemented` (provider and consumer exist), and `ADOPTION.md` records the Order consumer and the regenerated ADMIN UI types. |

## Alternatives considered

- **Reject any path containing `%` or `;`.** Blunt and would break legitimate encoded SKU or
  order identifiers in path segments; evaluating the decoded path keeps routing and checks
  on the same string.
- **Make the Reserve duplicate check after the position locks only.** It fixes the ordinary
  race but leaves a window when the two plans touch different warehouses; a per-order key
  closes it for every plan.
- **Return `scope_revision: 1` and document it.** A receipt that disagrees with the scope it
  names is not usable for reconciliation; rejected.
- **Retain expired snapshot sessions for the replay window** so `410` is always returned.
  Needs a retention rule for no consumer benefit, since Search handles both codes.
- **Drop the availability audit from the documents.** Cheaper, but the sampled recomputation
  is the only continuous detector of projector drift; the owner chose to implement it.
- **Also change Search's protection window.** Correct but outside the Inventory service; the
  owner limited this decision to Inventory.

## Consequences

- ADMIN and Workflow routes cannot be reached unchecked through a non-canonical path.
- A new snapshot is never immediately invalid after a quiet 30 days.
- Order may rely on `RESERVATION_ALREADY_EXISTS` as the only answer for a duplicate order.
- Projector drift becomes visible within one audit cycle (batch 500 per minute).
- Kafka topic provisioning and Search's window are explicit follow-ups, not silently assumed.
- The same raw-URI pattern exists in other services' identity filters; they are outside this
  decision and are tracked separately.

## Migration impact

No schema change, no new endpoint and no wire change except the corrected
`scope_revision` of a replayed registration and `RESERVATION_ALREADY_EXISTS` replacing a
racing `REJECT_ALL`. Two configuration properties are added with defaults:
`inventory.workers.availability-audit-interval` and
`inventory.workers.availability-audit-batch-size`. Contract text changes are additive.

## Verification

`AuditCorrectionsIT` fails on the previous code for the identity bypass, the watermark, the
duplicate-order race and the replayed revision, and passes with the fixes; it also covers the
availability audit (drift, pending work, fan-out, suppressed count) and the snapshot-session
`410`/`404` sequence. The existing suites remain green. Contract linting (Redocly, AsyncAPI)
and the example schemas pass.
