# Inventory Service — Interfaces and Event Contracts

> **Status:** Target contract for Release 1. Endpoint shapes are implementation-independent; semantic fields and outcomes are binding.

## 1. Surfaces and common envelope

| Surface | Audience | Purpose |
|---|---|---|
| `/internal/inventory/v1` | Checkout and Order Temporal Saga | Allocation preview, reservation/refund workflow commands, and workflow queries. |
| `/internal/inventory/v1/sku-availabilities:batch-get` | Product Detail BFF | Read-only advisory per-SKU availability; no hold, quantity, warehouse, or reservation data. |
| `/admin/inventory/v1` | Authenticated `ADMIN` | Warehouse configuration and physical stock operations. |
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

Input is `line_reference`, opaque `sku_id`, and positive quantity. A `line_reference` is mapped to the Order line by Checkout/Order, not by Inventory. The response is one of:

| Transport | Domain outcome | Meaning |
|---:|---|---|
| `200` | `ALLOCATABLE` | Advisory plan with `plan_id`, per-line warehouse, SKU, quantity, and policy explanation. |
| `200` | `REJECT_ALL` | At least one line has no eligible whole-quantity warehouse. No partial plan is a commitment. |
| `503` | `INVENTORY_UNAVAILABLE` | Primary authoritative store is unavailable; caller may retry. |

Preview excludes inactive/outage warehouses and selects priority deterministically. It creates no reservation; Reserve must revalidate the exact plan.

### Order Temporal Saga commands

| Command | Endpoint | Essential guard / terminal result |
|---|---|---|
| Reserve whole order | `POST /internal/inventory/v1/reservations` | Immutable `order_id`, order lines, exact preview plan. Returns `RESERVED` or `REJECT_ALL`; all lines or none. |
| Commit payment | `POST /internal/inventory/v1/reservations/{order_id}/commit` | Root must be `RESERVED` and a new command must be before `expires_at`; moves all lines to `COMMITTED`. |
| Release reservation | `POST /internal/inventory/v1/reservations/{order_id}/release` | Only `RESERVED`; reason `PAYMENT_FAILED`, `RESERVATION_EXPIRED`, or `STOCK_DISCREPANCY` plus `order_exception_id`. |
| Recover timed-out payment | `POST /internal/inventory/v1/reservations/{order_id}/recover-timed-out-payment` | Only this root was released for `RESERVATION_EXPIRED`; no dispatch and the full original plan is still available. Atomically restores the whole root `RELEASED -> COMMITTED`; any guard failure is durable `409`, no movement. |
| Release committed line | `POST /internal/inventory/v1/reservations/{order_id}/lines/{order_line_id}/release` | Target is committed and undispatched; reason guard defined in [workflows.md](workflows.md). |
| Release committed order before dispatch | `POST /internal/inventory/v1/reservations/{order_id}/release-committed-before-dispatch` | Admin automatic refund only; atomically releases every committed undispatched line or releases none. |
| Register Refund Scope | `POST /internal/inventory/v1/refund-scopes` | Immutable selected full-quantity order lines; rejects a second scope for the order even after closure. |
| Cancel scope | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/cancel` | Only `REGISTERED` and no receipt. |
| Close completed scope | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/close` | Only `INSPECTION_COMPLETED`; carries terminal Order reason. |
| Close incomplete scope | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/close-incomplete` | Only `IN_PROGRESS`, all received final-inspected, and some expected quantity missing. |

Order can query its own command result and state:

- `GET /internal/inventory/v1/commands/{command_id}`
- `GET /internal/inventory/v1/reservations/{order_id}`
- `GET /internal/inventory/v1/refund-scopes/{refund_scope_id}`

Registration accepts Order's immutable `(order_id, refund_request_id)` business identity and
returns Inventory's opaque `refund_scope_id`. Subsequent resource operations use
`refund_scope_id`; facts and queries retain all three identifiers for reconciliation.

These queries are the recovery path when an Order event is delayed or missed; no generic cross-service event replay API is promised to Order.

The late-payment command includes the verified Payment transaction reference. It is
idempotent by the ordinary Order command ID, records both success and rejected guard
outcomes, and is the only allowed path from a released timeout allocation to a committed
allocation. It may not reserve a new root, select another warehouse, or recover a
`PAYMENT_FAILED`/customer-cancelled release.

## 3. Storefront availability read API

`POST /internal/inventory/v1/sku-availabilities:batch-get` is an authenticated,
read-only endpoint for Product Detail BFF. It receives 1–500 unique opaque `sku_id` in
one request and returns exactly one result per requested SKU, in no guaranteed order.
It does not accept quantity, create a hold, call Product, or change Inventory state.

Each result is `IN_STOCK`, `OUT_OF_STOCK`, or `UNKNOWN`, with
`availability_revision` and `evaluated_at` only for a known state. `UNKNOWN` means the
Inventory-owned derived state is missing or has pending dirty/recompute work; it never
means `OUT_OF_STOCK`. A dependency-wide `503 INVENTORY_AVAILABILITY_UNAVAILABLE` has no
partial result. PDP BFF maps that failure to `UNKNOWN` and does not retry repeatedly in
the buyer hot path.

The response is advisory UI data only. It never includes quantity, warehouse,
availability algorithm internals, allocation plan, reservation, or an authority to
Reserve. Checkout/Order continue using the Workflow API for Preview and Reserve.

## 4. ADMIN API

ADMIN commands are the only physical-operation path. Every stock-affecting input includes `warehouse_id`, `sku_id` when relevant, positive quantity, command ID, operation identity, reason/evidence reference, and actor identity.

| Capability | Endpoint family | Rules |
|---|---|---|
| Warehouse master | `/admin/inventory/v1/warehouses` | Create/update address, `is_active`, `operational_state`, `fulfillment_priority`; no implicit movement or release/reroute. |
| Sellable receipt | `/admin/inventory/v1/stock-receipts` | `INITIAL_STOCK_LOAD` needs approved baseline batch; `SUPPLIER_RECEIPT` needs delivery/GRN reference. |
| Adjustment | `/admin/inventory/v1/stock-adjustments` | `+/-`, ADMIN-only, approved reason code and evidence; negative only from sellable. |
| Sellable reversal | `/admin/inventory/v1/stock-movements/{movement_id}/reversals` | Only successful sellable receipt/adjustment; append-only compensating movement. |
| Physical dispatch | `/admin/inventory/v1/physical-dispatches` | Only exact committed allocation at assigned warehouse and only when goods left physically. |
| Return receipt | `/admin/inventory/v1/return-receipts` | Exact active scope line; goes into quarantine. |
| Final inspection | `/admin/inventory/v1/inspections` | `PASS`/`FAIL` final per receipt quantity at receiving warehouse; evidence required. |
| Quarantine disposition | `/admin/inventory/v1/quarantine-dispositions` | `HOLD`, `DISPOSE`, `RETURN_TO_SUPPLIER`; physical departure proof for last two. |
| Disposition reversal | `/admin/inventory/v1/quarantine-dispositions/{id}/reversals` | Only verified physical return to same warehouse; restores quarantine, not sellable. |

Allowed receipt reasons are `INITIAL_STOCK_LOAD` and `SUPPLIER_RECEIPT`. Adjustment reasons are `COUNT_GAIN`, `COUNT_LOSS`, `DAMAGE_REMOVAL`, `EXPIRY_REMOVAL`, and `DATA_CORRECTION`. Reversal reasons are `DUPLICATE_ENTRY`, `WRONG_QUANTITY`, `WRONG_SKU`, `WRONG_WAREHOUSE`, and `SOURCE_DOCUMENT_VOIDED`. There is no catch-all `OTHER`. Inspection fail reasons and disposition reasons are enumerated in [domain.md](domain.md).

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

Every error response includes stable `code`, `retryable`, `correlation_id`, and, when supplied, `command_id`. A technical failure is never represented as `REJECT_ALL`.

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
the strictly increasing export sequence assigned at durable creation and can contain gaps; no
global Kafka ordering is promised.

| Event | Consumer | Minimum data / semantic |
|---|---|---|
| `com.ecommerce.inventory.sku-availability.changed.v1` | Search | Opaque SKU, derived `is_in_stock`, monotonic per-SKU `availability_revision`, and `observed_at`; no warehouse or quantity data. |
| `com.ecommerce.inventory.reservation.reserved.v1` | Order | Durable Inventory fact for reconciliation. |
| `com.ecommerce.inventory.reservation.committed.v1` | Order | Payment commit fact; Order still owns payment state. |
| `com.ecommerce.inventory.reservation.released.v1` | Order | Release fact/reason. |
| `com.ecommerce.inventory.reservation.line-dispatched.v1` | Order | Physical dispatch fact; Order owns shipment/delivery transition. |
| `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` | Order | Selected scope ID and aggregated expected/received/pass/fail per-line results; not payout. |

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
   watermark, and 24-hour session expiry.
4. Paginate registered per-SKU availability snapshots at that fixed watermark.
5. `GET /exports/inventory/v1/changes?after_sequence=N` returns only the same per-SKU
   availability CloudEvents after a sequence.
6. Search applies snapshots/changes directly to existing Product documents using
   `availability_revision`. If more than 30 days behind, Inventory returns
   `410 REPLAY_WINDOW_EXPIRED`; Search rebuilds availability fields from a new snapshot.

Within v1, changes are additive and backward compatible. A breaking change requires v2 and parallel support for at least 90 days after every registered consumer confirms migration. Contract registry records owner, contact, schema version, compatibility decision, and test checkpoint.
