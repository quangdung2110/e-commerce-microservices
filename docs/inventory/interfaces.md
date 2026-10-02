# Inventory Service — Interfaces and Event Contracts

> **Status:** Target contract for Release 1. Endpoint shapes are implementation-independent; semantic fields and outcomes are binding.

## 1. Surfaces and common envelope

| Surface | Audience | Purpose |
|---|---|---|
| `/internal/inventory/v1` | Checkout and Order Temporal Saga | Allocation preview, reservation/refund workflow commands, and workflow queries. |
| `/internal/inventory/v1/sku-availabilities:batch-get` | Product Detail BFF | Read-only advisory per-SKU availability; no hold, quantity, warehouse, or reservation data. |
| `/api/v1/admin/inventory` | Authenticated `ADMIN` through the gateway | Warehouse configuration, physical stock operations and read-only operational queues. |
| `/exports/inventory/v1` | Search Projection only | SKU-document readiness plus consistent per-SKU snapshot/change feed. |

Machine-readable contracts are split by trust boundary: [Workflow OpenAPI](../../contracts/http/inventory/workflow/v1/openapi.yaml),
[Storefront Availability Read OpenAPI](../../contracts/http/inventory/read/v1/openapi.yaml),
[ADMIN OpenAPI](../../contracts/http/inventory/admin/v1/openapi.yaml),
[Export OpenAPI](../../contracts/http/inventory/exports/v1/openapi.yaml), and
[Inventory AsyncAPI](../../contracts/events/inventory/v1/asyncapi.yaml).

There is no public customer Inventory write API. Every mutation requires authenticated workload/user identity plus:

| Header / context | Rule |
|---|---|
| `Idempotency-Key` | Required command ID; same caller namespace + key must carry the exact same fingerprint. |
| `X-Correlation-ID` | Required for end-to-end trace. |
| `X-Causation-ID` | Required where a prior workflow/event caused this command; otherwise a newly created root cause ID. |
| `traceparent` | Propagated when present according to W3C Trace Context; it does not replace the business correlation/causation IDs. |
| Actor context | Service identity for Saga, interactive ADMIN identity/role for physical commands. It is written to audit. |

An accepted command persists its terminal result. Same command ID/payload returns that result. Same ID with a different payload returns `409 IDEMPOTENCY_KEY_REUSED`. A business rejection records the command result but does **not** permanently claim the operation identity, so a corrected new command may later succeed.

The command result and canonical fingerprint are retained for at least seven years. A concurrent
retry waits only for a bounded interval; if the first execution has not completed, Inventory
returns retryable `503 INVENTORY_BUSY` and the caller retries the same command ID.

Release 1 bounds preview, reservation, Refund Scope, and event line collections at 100 items;
export pages are bounded at 1,000 rows. These are wire-safety limits, not permission to accept a
partial order reservation or a partial quantity of a selected Refund Scope line.

## 2. Checkout and Order workflow API

### Allocation preview

`POST /internal/inventory/v1/allocation-previews`

Input is `line_reference`, opaque `sku_id`, and positive quantity. For Checkout v1,
both identifiers are at most 64 characters and `line_reference=sku_id`; elsewhere
Inventory identifiers are opaque strings of at most 128 characters. A `line_reference`
is mapped to the Order line by Checkout/Order, not by Inventory. The response is one of:

| Transport | Domain outcome | Meaning |
|---:|---|---|
| `200` | `ALLOCATABLE` | Advisory plan with `allocation_plan_id`, per-line warehouse, SKU, quantity, and policy explanation. |
| `200` | `REJECT_ALL` | At least one line has no eligible whole-quantity warehouse. No partial plan is a commitment. |
| `503` | `INVENTORY_UNAVAILABLE` | Primary authoritative store is unavailable; caller may retry. |

Preview excludes inactive/outage warehouses and selects priority deterministically. It
is stateless: it creates no reservation and persists no plan. `allocation_plan_id` is an
opaque correlation identifier with no expiry; Reserve stores it for audit but never
looks it up, and revalidates exactly the submitted per-line warehouse plan
([ADR-021](decisions/ADR-021-order-integration-lifecycle-corrections.md)).

### Order Temporal Saga commands

| Command | Endpoint | Essential guard / terminal result |
|---|---|---|
| Reserve whole order | `POST /internal/inventory/v1/reservations` | Immutable `order_id`, `allocation_plan_id` (stored, not looked up), order lines with exact preview warehouse. Returns `RESERVED` or `REJECT_ALL`; all lines or none. |
| Commit payment | `POST /internal/inventory/v1/reservations/{order_id}/commit` | Root must be `RESERVED`; no wall-clock deadline applies. Moves all lines to `COMMITTED`; otherwise durable `409 RESERVATION_NOT_RESERVED`. |
| Release reservation | `POST /internal/inventory/v1/reservations/{order_id}/release` | Only `RESERVED`; releases the whole root with one immutable reason: `PAYMENT_FAILED`, `RESERVATION_EXPIRED`, `BUYER_CANCELLED`, `ADMIN_CANCELLED`, or `PRE_PAYMENT_SAGA_FAILED`. |
| Recover timed-out payment | `POST /internal/inventory/v1/reservations/{order_id}/recover-timed-out-payment` | Only this root was released for `RESERVATION_EXPIRED`; no dispatch and the full original plan is still available. Atomically restores the whole root `RELEASED -> COMMITTED`; any guard failure is durable `409`, no movement. |
| Release committed order before dispatch | `POST /internal/inventory/v1/reservations/{order_id}/release-committed-before-dispatch` | Admin automatic refund only; atomically releases every committed undispatched line or releases none. |
| Register Refund Scope | `POST /internal/inventory/v1/refund-scopes` | Immutable selected full-quantity order lines, each already `DISPATCHED` (`409 REFUND_SCOPE_LINE_NOT_DISPATCHED` otherwise); rejects a second scope for the order even after closure. |
| Cancel scope | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/cancel` | Only `REGISTERED` and no receipt. |
| Close completed scope | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/close` | Only `INSPECTION_COMPLETED`; carries terminal Order reason. |
| Close incomplete scope | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/close-incomplete` | Only `IN_PROGRESS`, all received final-inspected, and some expected quantity missing. |

`close` reasons are `REFUNDED`, `REJECTED`, `CANCELLED`. Order maps terminal Payment
success to `REFUNDED`, terminal failure/reversal without a previous close intent to
`REJECTED`; `REJECTED` is financial finality, not inspection FAIL. If a prior intent
exists, replay its original reason even after reversal. Inventory does not verify
Payment or own the financial decision.

`close-incomplete` reasons are `RETURN_WINDOW_EXPIRED`, `PACKAGE_LOST`,
`CUSTOMER_WITHDREW`; only an approved no-payout Order/Ops decision invokes it.
For every line `passed_quantity + failed_quantity = received_quantity`, and at least
one line has `received_quantity < expected_quantity`. A zero-receipt `REGISTERED`
scope uses `cancel`, which has no body; Order retains the decision reason in its audit.
The 30-day timer alone never invokes a terminal command.

Same command ID/body replays the accepted/rejected result. Receipt/inspection races
are resolved by Inventory's atomic state guards; a durable 409 cannot silently become
another close command. Closure returns `refund_scope_id`, `order_id`,
`refund_request_id`, command ID, target outcome and `scope_revision`; Order must persist
the matching receipt. No closure integration event is added. Terminal scopes stay
lifetime unique, reject subsequent receipt, and are never reopened after reversal.

Order can query its own command result and state:

- `GET /internal/inventory/v1/commands/{command_id}`
- `GET /internal/inventory/v1/reservations/{order_id}`
- `GET /internal/inventory/v1/refund-scopes/{refund_scope_id}`

Registration accepts Order's immutable `(order_id, refund_request_id)` business identity and
returns Inventory's opaque `refund_scope_id`. Subsequent resource operations use
`refund_scope_id`; facts and queries retain all three identifiers for reconciliation.

Reserve, Commit, Release and timed-out-payment recovery return durable command receipts;
they publish no reservation-result event to Order. Order checkpoints a stable command
ID and request before calling, then persists the matching outcome. On response loss or
crash, query the original command or replay the same ID/body; never infer failure from
timeout or a missing query result while execution may still be in flight. Exhausted
retries retain durable recovery work and alert instead of completing an unknown step.
Command queries prove a specific command's original result; reservation queries prove
current state and may reflect later transitions. These queries also recover delayed or
missed dispatch/inspection facts; no generic cross-service event replay API is promised
to Order. See [ADR-020](decisions/ADR-020-command-receipts-and-minimal-order-events.md).

The late-payment command includes the verified Payment transaction reference. It is
idempotent by the ordinary Order command ID, records both success and rejected guard
outcomes, and is the only allowed path from a released timeout allocation to a committed
allocation. It may not reserve a new root, select another warehouse, or recover a
`PAYMENT_FAILED`, `BUYER_CANCELLED`, `ADMIN_CANCELLED` or `PRE_PAYMENT_SAGA_FAILED` release.

There is no single-line committed release and no `STOCK_DISCREPANCY` release in
Release 1; committed stock is released only by the all-or-nothing
`release-committed-before-dispatch` command
([ADR-021](decisions/ADR-021-order-integration-lifecycle-corrections.md)).

## 3. Storefront availability read API

`POST /internal/inventory/v1/sku-availabilities:batch-get` is an authenticated,
read-only endpoint for Product Detail BFF. It receives 1–500 unique opaque `sku_id` in
one request and returns exactly one result per requested SKU, in no guaranteed order.
It does not accept quantity, create a hold, call Product, or change Inventory state.

Each result is `IN_STOCK`, `OUT_OF_STOCK`, or `UNKNOWN`, with
`availability_revision` and `evaluated_at` only for a known state. `UNKNOWN` means the
Inventory-owned derived state is missing or has pending dirty/recompute work; it never
means `OUT_OF_STOCK`. A dependency-wide `503 INVENTORY_AVAILABILITY_UNAVAILABLE` has no
partial result. PDP BFF maps that failure to `UNKNOWN` and does not retry in the buyer
hot path (no application retry; the mesh route has `attempts: 0`). BFF cuts the call at
`min(300 ms, remaining PDP deadline − 50 ms)` and has its own circuit breaker; see
[Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md).
A missing item or an item for an unrequested SKU breaks the one-result-per-SKU promise:
BFF degrades only the missing SKU to `UNKNOWN`, drops the extra item, and records an
item-level contract violation that alerts Inventory
([ADR-007](../product-detail-bff/decisions/ADR-007-hot-path-clarifications.md)). BFF sends
`X-Correlation-ID` as a normalized 8–128 character value, never a raw client header.

The response is advisory UI data only. It never includes quantity, warehouse,
availability algorithm internals, allocation plan, reservation, or an authority to
Reserve. Checkout uses the Workflow API for Preview; Order uses it for Reserve.

## 4. ADMIN API

ADMIN commands are the only physical-operation path. Every stock-affecting input includes `warehouse_id`, `sku_id` when relevant, positive quantity, command ID, operation identity, reason/evidence reference, and actor identity.

The surface is `/api/v1/admin/inventory/**`, routed by Traefik with `api-authenticated`
and role `ADMIN`; the gateway never retries a write. Inventory authorizes from
`X-User-Roles` containing `ADMIN` and writes `X-User-Id`/`X-User-Session-Id` to audit.
There is no non-interactive ADMIN principal. MFA is mandatory for production and is a
release gate: it needs a separate Auth/gateway decision that issues and forwards an MFA
assertion, which Inventory then verifies on every ADMIN mutation. Local and development
environments run without MFA ([ADR-024](decisions/ADR-024-admin-surface-and-error-catalog.md)).

| Capability | Endpoint family | Rules |
|---|---|---|
| Warehouse master | `/api/v1/admin/inventory/warehouses` | Create/update address, `is_active`, `operational_state`, `fulfillment_priority`; no implicit movement or release/reroute. |
| Sellable receipt | `/api/v1/admin/inventory/stock-receipts` | `INITIAL_STOCK_LOAD` needs approved baseline batch; `SUPPLIER_RECEIPT` needs delivery/GRN reference. |
| Adjustment | `/api/v1/admin/inventory/stock-adjustments` | `+/-`, ADMIN-only, approved reason code and evidence; negative only from sellable. |
| Sellable reversal | `/api/v1/admin/inventory/stock-movements/{movement_id}/reversals` | Only successful sellable receipt/adjustment; append-only compensating movement. `reversal_sequence` is 1..n per original movement and the cumulative reversed quantity cannot exceed the original. Reversing an increase needs `quantity <= available_to_sell`; reversing a decrease adds back to sellable. |
| Physical dispatch | `/api/v1/admin/inventory/physical-dispatches` | Whole line only: `quantity` equals the immutable line quantity, at its assigned warehouse and SKU, while the line is `COMMITTED`, and only when goods left physically. |
| Return receipt | `/api/v1/admin/inventory/return-receipts` | Exact active scope line; goes into quarantine. |
| Final inspection | `/api/v1/admin/inventory/inspections` | `PASS`/`FAIL` final per receipt quantity at receiving warehouse; evidence required. |
| Quarantine disposition | `/api/v1/admin/inventory/quarantine-dispositions` | `HOLD` is a non-terminal annotation with no bucket movement; the quantity stays disposable. `DISPOSE`/`RETURN_TO_SUPPLIER` consume failed, undisposed quantity and require physical departure proof. |
| Disposition reversal | `/api/v1/admin/inventory/quarantine-dispositions/{id}/reversals` | Only for `DISPOSE`/`RETURN_TO_SUPPLIER`, with verified physical return to the same warehouse; restores quarantine, not sellable. |

Allowed receipt reasons are `INITIAL_STOCK_LOAD` and `SUPPLIER_RECEIPT`. Adjustment reasons are `COUNT_GAIN`, `COUNT_LOSS`, `DAMAGE_REMOVAL`, `EXPIRY_REMOVAL`, and `DATA_CORRECTION`. Sellable reversal reasons are `DUPLICATE_ENTRY`, `WRONG_QUANTITY`, `WRONG_SKU`, `WRONG_WAREHOUSE`, and `SOURCE_DOCUMENT_VOIDED`. Disposition reversal reasons are `WRONG_RECORD`, `DISPOSAL_ABORTED`, and `SUPPLIER_RETURNED`. There is no catch-all `OTHER`. Inspection fail reasons and disposition reasons are enumerated in [domain.md](domain.md).

Read-only operational queries are cursor-paginated (`limit <= 100`), never mutate state
and return no customer PII:

| Query | Purpose |
|---|---|
| `GET /api/v1/admin/inventory/warehouses` | List warehouses; optional `is_active`. |
| `GET /api/v1/admin/inventory/stock-positions` | Positions filtered by `warehouse_id` and/or `sku_id` (at least one). |
| `GET /api/v1/admin/inventory/dispatch-queue?warehouse_id=` | `COMMITTED`, undispatched lines allocated to the warehouse, oldest commit first. |
| `GET /api/v1/admin/inventory/inspection-queue?warehouse_id=` | Return receipt lines with uninspected quantity at the warehouse. |
| `GET /api/v1/admin/inventory/quarantine-items?warehouse_id=` | Failed-inspection quantity not yet disposed out, with the latest `HOLD` reason. |
| `GET /api/v1/admin/inventory/refund-scopes?order_id=` | Resolve the order's lifetime scope. |
| `GET /api/v1/admin/inventory/stock-movements` | Movement evidence by `warehouse_id`, `sku_id` and `recorded_from`/`recorded_to`. |

## 5. Errors and retries

| HTTP | Code / meaning | Caller behavior |
|---:|---|---|
| `200` | Accepted result or expected terminal domain result such as `REJECT_ALL`, `RESERVED`, `COMMITTED`, `RELEASED`. | Do not retry for a different business decision; replay only to obtain same result. |
| `400` | Invalid syntax/shape before a durable command exists. | Correct input, then submit a new command ID. |
| `401` | Caller identity is missing or invalid. | Refresh/fix authentication; do not retry blindly. |
| `403` | Caller lacks required service/ADMIN authority. | Do not retry without changed authorization. |
| `404` | Query aggregate/command is not visible to caller. | Reconcile caller identifiers. |
| `409` | Invalid state guard, immutable scope mismatch, operation identity conflict, or reused ID with different payload. | Treat as durable business conflict; investigate or issue a correct new command. |
| `503` | `INVENTORY_UNAVAILABLE` or `INVENTORY_BUSY`; no accepted transition was returned. | Retry with the same command ID and backoff. |

Every error response includes stable `code`, `retryable`, `correlation_id`, and, when supplied, `command_id`. A technical failure is never represented as `REJECT_ALL`. A durable `409` is replayed with the same body for the same command ID/payload, and the command query reports it as `result: REJECTED` with its `http_status` and `error_code` ([ADR-024](decisions/ADR-024-admin-surface-and-error-catalog.md)).

| HTTP | `code` | Raised by |
|---:|---|---|
| `400` | `INVALID_REQUEST` | Any surface: syntax/shape failure before a durable command exists. |
| `401` | `UNAUTHENTICATED` | Missing or invalid workload/ADMIN identity. |
| `403` | `FORBIDDEN` | Caller lacks the required service principal or `ADMIN` role. |
| `404` | `NOT_FOUND` | Query target or command not visible to the caller. |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Same caller namespace and key with a different fingerprint. |
| `409` | `RESERVATION_ALREADY_EXISTS` | Reserve for an `order_id` that already has a root under another command. |
| `409` | `RESERVATION_NOT_RESERVED` | Commit or Release on a root that is not `RESERVED`. |
| `409` | `RESERVATION_NOT_RECOVERABLE` | `recover-timed-out-payment` guard failed (reason, dispatch, stock). |
| `409` | `PRE_DISPATCH_RELEASE_NOT_ALLOWED` | `release-committed-before-dispatch` when not every line is `COMMITTED` and undispatched. |
| `409` | `REFUND_SCOPE_ALREADY_EXISTS` | A second `refund_request_id` for the order's lifetime scope. |
| `409` | `REFUND_SCOPE_LINE_INVALID` | Unknown/duplicate line, SKU mismatch or partial quantity. |
| `409` | `REFUND_SCOPE_LINE_NOT_DISPATCHED` | A selected line is not `DISPATCHED`. |
| `409` | `REFUND_SCOPE_STATE_CONFLICT` | Close/cancel/close-incomplete guard, or receipt on a terminal scope. |
| `409` | `OPERATION_IDENTITY_CONFLICT` | Business operation identity already claimed by another accepted operation. |
| `409` | `INSUFFICIENT_SELLABLE_STOCK` | Negative adjustment or reversal larger than `available_to_sell`. |
| `409` | `DISPATCH_NOT_ALLOWED` | Line not `COMMITTED`, or warehouse/SKU/quantity differs from the allocation. |
| `409` | `RETURN_RECEIPT_REJECTED` | Wrong line/SKU, over-receipt or inactive scope. |
| `409` | `INSPECTION_REJECTED` | Quantity above the uninspected residual or wrong warehouse. |
| `409` | `DISPOSITION_REJECTED` | Quantity above failed undisposed quantity, missing departure, or ineligible reversal. |
| `409` | `MOVEMENT_NOT_REVERSIBLE` | Target is not a sellable receipt/adjustment or the cumulative limit is exceeded. |
| `409` | `WAREHOUSE_CONFLICT` | Warehouse configuration conflicts with its current revision. |
| `503` | `INVENTORY_UNAVAILABLE` / `INVENTORY_BUSY` | Retryable technical failure; same command ID. |

`REJECT_ALL` is a `200` domain outcome, never an error code. The Export surface adds
`SNAPSHOT_SESSION_EXPIRED` and `REPLAY_WINDOW_EXPIRED` (`410`), and the Read surface uses
`INVENTORY_AVAILABILITY_UNAVAILABLE` (`503`).

The table above governs workflow/admin commands and their recovery queries. The BFF
availability read is defined by its own OpenAPI: it has no idempotency key or business
transition, and its `503` is intentionally degraded to `UNKNOWN` by PDP BFF.

## 6. Public events

Every public event uses CloudEvents 1.0 structured JSON. The semantic envelope below is mapped
onto the shared contract convention rather than introducing a second custom envelope:

```json
{
  "specversion": "1.0",
  "id": "stable UUID",
  "type": "com.ecommerce.inventory.example.v1",
  "source": "/services/inventory",
  "subject": "resource identity",
  "time": "RFC-3339 timestamp",
  "datacontenttype": "application/json",
  "dataschema": "/contracts/events/inventory/v1/schemas/example.schema.json",
  "correlationid": "...",
  "causationid": "...",
  "sequence": 12345,
  "aggregatetype": "...",
  "aggregateid": "...",
  "aggregaterevision": 7,
  "data": {}
}
```

`id` supports deduplication. `aggregaterevision` supports per-aggregate ordering. `sequence` is
the export sequence assigned after commit by Inventory's single fenced sequencer, so it
follows commit order: once a reader has seen sequence `N`, no new fact with a sequence
`<= N` appears later. It can contain permanent gaps that are safe to skip. No global Kafka
ordering is promised beyond this ([ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md)).

| Event | Consumer | Minimum data / semantic |
|---|---|---|
| `com.ecommerce.inventory.sku-availability.changed.v1` | Search | Opaque SKU, derived `is_in_stock`, monotonic per-SKU `availability_revision`, and `observed_at`; no warehouse or quantity data. |
| `com.ecommerce.inventory.reservation.line-dispatched.v1` | Order | Physical dispatch fact; Order owns shipment/delivery transition. |
| `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` | Order | Selected scope ID and aggregated expected/received/pass/fail per-line results; not payout. |

This table is the complete public event catalog for Release 1. The Order topic contains
only physical dispatch and selected-scope inspection completion. Reservation command
results and scope-finalization results use durable HTTP receipts and query/replay;
movement/audit and internal work are not additional public event types.

Events are written through the transactional outbox and delivered at least once. Order
atomically records inbox/dedupe with its effect. Search uses strict per-SKU revision
guards and no-upsert writes; a missing target document is retried as a readiness contract
violation, not ACKed as normal data.

Product Detail BFF does not consume any Inventory event, topic, snapshot session, change
feed, or readiness operation. Its only availability interaction is the separate
authenticated batch-read API above.

## 7. Search export, bootstrap, and evolution

Inventory owns and exports only the Search-facing boolean
`is_in_stock = exists(active, non-outage warehouse with available_to_sell > 0)`. Search
copies this value; it does not receive warehouse or quantity state and must not use the
boolean as checkout authority.

1. After creating a Product-owned SKU document, Search calls
   `PUT /exports/inventory/v1/search-projection-readiness/{sku_id}` with its
   `catalog_revision`. Inventory cannot publish/export that SKU before registration and
   emits the current snapshot when registration succeeds.
2. After tombstone and before purge, Search calls `DELETE` on the same resource with the
   tombstone `catalog_revision`; stale revocation returns a conflict.
3. `POST /exports/inventory/v1/snapshot-sessions` returns a `snapshot_id`, fixed export
   watermark (the greatest assigned sequence at creation), and 24-hour session expiry.
4. Paginate registered per-SKU availability snapshots. Each row is the current state
   with its `availability_revision`, so it may be newer than the watermark but never
   older; Search's strict revision guard makes the overlap safe
   ([ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md)).
5. `GET /exports/inventory/v1/changes?after_sequence=N` returns only the same per-SKU
   availability CloudEvents after a sequence.
6. Search applies snapshots/changes directly to existing Product documents using
   `availability_revision`. If more than 30 days behind, Inventory returns
   `410 REPLAY_WINDOW_EXPIRED`; Search rebuilds availability fields from a new snapshot.

Within v1, changes are additive and backward compatible. A breaking change requires v2 and parallel support for at least 90 days after every registered consumer confirms migration. Contract registry records owner, contact, schema version, compatibility decision, and test checkpoint.
