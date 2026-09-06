# Inventory Service — Interfaces and Event Contracts

> **Status:** Target contract for Release 1. Endpoint shapes are implementation-independent; semantic fields and outcomes are binding.

## 1. Surfaces and common envelope

| Surface | Audience | Purpose |
|---|---|---|
| `/internal/inventory/v1` | Checkout and Order Temporal Saga | Allocation preview, reservation/refund workflow commands, and workflow queries. |
| `/admin/inventory/v1` | Authenticated `ADMIN` | Warehouse configuration and physical stock operations. |
| `/exports/inventory/v1` | Search Projection only | Consistent snapshot and ordered change feed for `in_stock`. |

There is no public customer Inventory write API. Every mutation requires authenticated workload/user identity plus:

| Header / context | Rule |
|---|---|
| `Idempotency-Key` | Required command ID; same caller namespace + key must carry the exact same fingerprint. |
| `X-Correlation-ID` | Required for end-to-end trace. |
| `X-Causation-ID` | Required where a prior workflow/event caused this command; otherwise a newly created root cause ID. |
| Actor context | Service identity for Saga, interactive ADMIN identity/role for physical commands. It is written to audit. |

An accepted command persists its terminal result. Same command ID/payload returns that result. Same ID with a different payload returns `409 IDEMPOTENCY_KEY_REUSED`. A business rejection records the command result but does **not** permanently claim the operation identity, so a corrected new command may later succeed.

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
| Release committed line | `POST /internal/inventory/v1/reservations/{order_id}/lines/{order_line_id}/release` | Target is committed and undispatched; reason guard defined in [workflows.md](workflows.md). |
| Register Refund Scope | `POST /internal/inventory/v1/refund-scopes` | Whole immutable order lines; rejects a second active scope. |
| Cancel scope | `POST /internal/inventory/v1/refund-scopes/{refund_request_id}/cancel` | Only `REGISTERED` and no receipt. |
| Close completed scope | `POST /internal/inventory/v1/refund-scopes/{refund_request_id}/close` | Only `INSPECTION_COMPLETED`; carries terminal Order reason. |
| Close incomplete scope | `POST /internal/inventory/v1/refund-scopes/{refund_request_id}/close-incomplete` | Only `IN_PROGRESS`, all received final-inspected, and some expected quantity missing. |

Order can query its own command result and state:

- `GET /internal/inventory/v1/commands/{command_id}`
- `GET /internal/inventory/v1/reservations/{order_id}`
- `GET /internal/inventory/v1/refund-scopes/{refund_request_id}`

These queries are the recovery path when an Order event is delayed or missed; no generic cross-service event replay API is promised to Order.

## 3. ADMIN API

ADMIN commands are the only physical-operation path. Every stock-affecting input includes `warehouse_id`, `sku_id` when relevant, positive quantity, command ID, operation identity, reason/evidence reference, and actor identity.

| Capability | Endpoint family | Rules |
|---|---|---|
| Warehouse master | `/admin/inventory/v1/warehouses` | Create/update address, `is_active`, `operational_state`, `fulfillment_priority`; no implicit movement or release/reroute. |
| Sellable receipt | `/admin/inventory/v1/stock-receipts` | `INITIAL_STOCK_LOAD` needs approved baseline batch; `SUPPLIER_RECEIPT` needs delivery/GRN reference. |
| Adjustment | `/admin/inventory/v1/stock-adjustments` | `+/-`, ADMIN-only, approved reason code and evidence; negative only from sellable. |
| Sellable reversal | `/admin/inventory/v1/stock-movements/{movement_id}/reversals` | Only successful sellable receipt/adjustment; append-only compensating movement. |
| Physical dispatch | `/admin/inventory/v1/dispatches` | Only exact committed allocation at assigned warehouse and only when goods left physically. |
| Return receipt | `/admin/inventory/v1/return-receipts` | Exact active scope line; goes into quarantine. |
| Final inspection | `/admin/inventory/v1/inspections` | `PASS`/`FAIL` final per receipt quantity at receiving warehouse; evidence required. |
| Quarantine disposition | `/admin/inventory/v1/quarantine-dispositions` | `HOLD`, `DISPOSE`, `RETURN_TO_SUPPLIER`; physical departure proof for last two. |
| Disposition reversal | `/admin/inventory/v1/quarantine-dispositions/{id}/reversals` | Only verified physical return to same warehouse; restores quarantine, not sellable. |

Allowed receipt reasons are `INITIAL_STOCK_LOAD` and `SUPPLIER_RECEIPT`. Adjustment reasons are `COUNT_GAIN`, `COUNT_LOSS`, `DAMAGE_REMOVAL`, `EXPIRY_REMOVAL`, and `DATA_CORRECTION`. Reversal reasons are `DUPLICATE_ENTRY`, `WRONG_QUANTITY`, `WRONG_SKU`, `WRONG_WAREHOUSE`, and `SOURCE_DOCUMENT_VOIDED`. There is no catch-all `OTHER`. Inspection fail reasons and disposition reasons are enumerated in [domain.md](domain.md).

## 4. Errors and retries

| HTTP | Code / meaning | Caller behavior |
|---:|---|---|
| `200` | Accepted result or expected terminal domain result such as `REJECT_ALL`, `RESERVED`, `COMMITTED`, `RELEASED`. | Do not retry for a different business decision; replay only to obtain same result. |
| `400` | Invalid syntax/shape before a durable command exists. | Correct input, then submit a new command ID. |
| `403` | Caller lacks required service/ADMIN authority. | Do not retry without changed authorization. |
| `404` | Query aggregate/command is not visible to caller. | Reconcile caller identifiers. |
| `409` | Invalid state guard, immutable scope mismatch, operation identity conflict, or reused ID with different payload. | Treat as durable business conflict; investigate or issue a correct new command. |
| `503` | `INVENTORY_UNAVAILABLE` or `BUSY`; no accepted transition was returned. | Retry with the same command ID and backoff. |

Every error response includes stable `code`, `retryable`, `correlation_id`, and, when supplied, `command_id`. A technical failure is never represented as `REJECT_ALL`.

## 5. Public events

Every public event uses this envelope:

```json
{
  "event_id": "stable UUID",
  "event_type": "inventory.example.v1",
  "schema_version": 1,
  "export_sequence": 12345,
  "occurred_at": "RFC-3339 timestamp",
  "correlation_id": "...",
  "causation_id": "...",
  "aggregate": { "type": "...", "id": "...", "revision": 7 },
  "data": {}
}
```

`event_id` supports deduplication. Aggregate revision supports per-aggregate ordering. `export_sequence` is strictly increasing at durable creation and can contain gaps; no global aggregate ordering is promised.

| Event | Consumer | Minimum data / semantic |
|---|---|---|
| `inventory.stock-position.changed.v1` | Search | Warehouse, SKU, `available_to_sell`, position revision. No raw on-hand or other buckets. |
| `inventory.warehouse-availability.changed.v1` | Search | Warehouse activity/outage and revision. |
| `inventory.reservation.reserved.v1` | Order | Durable Inventory fact for reconciliation. |
| `inventory.reservation.committed.v1` | Order | Payment commit fact; Order still owns payment state. |
| `inventory.reservation.released.v1` | Order | Release fact/reason. |
| `inventory.reservation.line-dispatched.v1` | Order | Physical dispatch fact; Order owns shipment/delivery transition. |
| `inventory.refund.all-items-inspection-completed.v1` | Order | Whole scope ID and aggregated per-line pass/fail results; not payout. |

Events are written through the transactional outbox and delivered at least once. Consumers atomically record an inbox/dedupe entry with their effect.

## 6. Search export, bootstrap, and evolution

Search exposes only `in_stock = exists(active, non-outage warehouse with available_to_sell > 0)`. It must not derive checkout availability from cache or expose raw stock buckets.

1. `POST /exports/inventory/v1/snapshot-sessions` returns a `snapshot_id`, fixed export watermark, and 24-hour session expiry.
2. Paginate warehouse and stock-position snapshots at that fixed watermark.
3. `GET /exports/inventory/v1/changes?after_sequence=N` returns only stock/warehouse events after a sequence.
4. Search applies the snapshot, then changes after its watermark using event/inbox dedupe. If more than 30 days behind, Inventory returns `410 REPLAY_WINDOW_EXPIRED`; Search takes a new snapshot rather than guessing.

Within v1, changes are additive and backward compatible. A breaking change requires v2 and parallel support for at least 90 days after every registered consumer confirms migration. Contract registry records owner, contact, schema version, compatibility decision, and test checkpoint.
