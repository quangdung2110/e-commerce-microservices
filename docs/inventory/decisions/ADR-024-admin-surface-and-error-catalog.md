# ADR-024 — ADMIN surface, operational queries and stable error catalog

## Status

Accepted — 2026-10-03. Refines the ADMIN and error parts of ADR-014 and ADR-015. All
domain rules, idempotency and audit guarantees remain unchanged.

## Context

The pre-implementation review found contract gaps that would block both the service
and its ADMIN UI:

1. The ADMIN surface used `/admin/inventory/v1`, outside the gateway convention
   `/api/v1/admin/...`, and the gateway route matrix had no Inventory row.
2. The contract required MFA, but Auth issues no MFA claim and `gateway-auth` injects
   only `X-User-Id`, `X-User-Roles`, `X-User-Session-Id` and `X-Token-Jti`. The security
   scheme also admitted an undefined non-interactive "warehouse-operations principal".
3. ADMIN could only read by identifier. Warehouse staff had no way to list warehouses,
   positions, lines awaiting dispatch, receipts awaiting inspection or failed stock
   awaiting disposition.
4. Physical rules were incomplete: dispatch quantity versus line quantity, the HOLD
   lifecycle, disposition-reversal reasons and sellable-reversal guards.
5. Conflicts had no stable `code` list, `503 BUSY` and `INVENTORY_BUSY` were both used,
   and a command query could not represent a durable rejection.

## Decision

### ADMIN path and identity

- The ADMIN surface is `/api/v1/admin/inventory/**`, routed by Traefik with
  `api-authenticated` and role `ADMIN`; the gateway never retries a write.
- Inventory authorizes from `X-User-Roles` containing `ADMIN` and records `X-User-Id`
  and `X-User-Session-Id` in audit. There is no non-interactive ADMIN principal.
- MFA stays mandatory **for production**: it is a release gate that requires a separate
  Auth/gateway ADR to issue and forward an MFA assertion, which Inventory will then
  verify on every ADMIN mutation. Local and development environments run without MFA.

### Operational queries (read-only, cursor-paginated, `limit <= 100`)

| Query | Purpose |
|---|---|
| `GET /warehouses` | List warehouses, optional `is_active` filter. |
| `GET /stock-positions?warehouse_id=&sku_id=` | Positions; both filters optional (ADR-029 removed the original "at least one"). |
| `GET /dispatch-queue?warehouse_id=` | `COMMITTED`, undispatched lines, oldest commit first; `warehouse_id` optional (ADR-029). |
| `GET /inspection-queue?warehouse_id=` | Return receipt lines with uninspected quantity; `warehouse_id` optional (ADR-029). |
| `GET /quarantine-items?warehouse_id=` | Failed-inspection quantity not yet disposed out, with latest HOLD reason; `warehouse_id` optional (ADR-029). |
| `GET /refund-scopes?order_id=` | Resolve the order's lifetime scope. |
| `GET /refund-scope-summaries?state=` | List Refund Scopes, newest first (added by ADR-029). |
| `GET /stock-movements?warehouse_id=&sku_id=&source_operation_type=&recorded_from=&recorded_to=` | Movement evidence search; every filter optional (ADR-029). |

Queries never mutate state and return no customer PII.

### Physical-operation guards

- **Dispatch** records an entire line: `quantity` must equal the immutable
  `ReservationLine.quantity`, at its warehouse and SKU, while the line is `COMMITTED`.
  Partial-line dispatch does not exist.
- **HOLD** is a non-terminal annotation on failed quantity: no bucket moves and the
  quantity stays eligible for a later `DISPOSE` or `RETURN_TO_SUPPLIER`. Only those two
  outbound dispositions consume failed, undisposed quantity, each requiring physical
  departure evidence.
- **Disposition reversal** applies only to `DISPOSE`/`RETURN_TO_SUPPLIER`, with reason
  `WRONG_RECORD`, `DISPOSAL_ABORTED` or `SUPPLIER_RETURNED`, and restores quarantine at
  the same warehouse only.
- **Sellable reversal** targets a successful receipt or adjustment. `reversal_sequence`
  numbers 1..n per original movement; cumulative reversed quantity cannot exceed the
  original quantity. Reversing an increase requires `quantity <= available_to_sell`;
  reversing a decrease adds back to sellable.

### Error catalog and command outcomes

- Every Workflow/ADMIN problem carries a stable `code` from the catalog in
  [interfaces.md](../interfaces.md#5-errors-and-retries). The busy code is
  `INVENTORY_BUSY` everywhere.
- A durable rejection (`409`) is replayed with the same body. Command queries return
  `result` `ACCEPTED` or `REJECTED`; a rejected outcome includes its `http_status` and
  `error_code`.

## Alternatives considered

- **Keep `/admin/inventory/v1`.** Avoids frontend changes but adds a second gateway
  prefix convention. Rejected by the owner.
- **Drop MFA from the design.** Simpler but removes a production control on physical
  stock changes. Rejected.
- **Partial-line dispatch.** Requires per-line dispatched quantity in Order and events.
  Not needed for Release 1.

## Consequences

- The ADMIN UI can operate the warehouse without guessing identifiers.
- Production rollout is blocked until the Auth/gateway MFA ADR is implemented.
- Order and the ADMIN UI can map conflicts by code instead of message text.

## Migration impact

Contracts are `proposed`. Update the ADMIN and Workflow OpenAPI, Inventory documents,
the mesh and gateway route matrices, and frontend docs. The frontend API client and its
generated types must switch to `/api/v1/admin/inventory`.

## Verification

Contract tests prove: the new path and role check; no ADMIN access without `ADMIN`;
each query's filters, pagination and absence of PII; dispatch rejects partial quantity;
HOLD keeps quantity disposable; reversal limits; every 409 carries a catalog code; and
command queries report rejected outcomes.
