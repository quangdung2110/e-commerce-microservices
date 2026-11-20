# ADR-029 — Admin lists and queues are complete without a filter

## Status

Accepted — 2026-10-12. Refines ADR-024 (operational queries). Every other rule of ADR-024,
including the ADMIN-only role, the read-only guarantee and cursor pagination, remains active.

## Context

ADR-024 required a scope on most operational reads: `warehouse_id` for the dispatch,
inspection and quarantine queues, and at least one of `warehouse_id` or `sku_id` for stock
positions and movements. The Admin UI inherited the rule, so the stock, movement, dispatch,
inspection and quarantine screens opened empty with "enter a warehouse code" and showed data
only after the operator already knew an identifier. Return requests could only be found by
typing an order ID, and stock receipts had no list at all (they only appear as movements).

An operator's first question is "what is there", not "what is in warehouse X". The scope
requirement protected nothing: every query is keyset-paged with `limit <= 100`, read-only,
ADMIN-only and returns no customer PII, so an unfiltered page costs the same as a filtered one
once the ordering is indexed.

## Decision

1. **No mandatory filter on any list or queue.** `GET /stock-positions`, `/stock-movements`,
   `/dispatch-queue`, `/inspection-queue` and `/quarantine-items` return every row when called
   without parameters, in the same order as before (positions by `(warehouse_id, sku_id)`,
   movements newest first, queues oldest first). `warehouse_id`, `sku_id` and the movement time
   window only narrow the result; an unknown value yields an empty page, not an error.
2. **`GET /stock-movements` gains `source_operation_type`** (exact match). Stock receipts are
   `SELLABLE_RECEIPT` movements and returned goods are `RETURN_RECEIPT` movements, so a "received
   stock" or "returned goods" view is the movement ledger with this filter and needs no new table.
3. **New `GET /refund-scope-summaries`** lists Refund Scopes (the return requests) newest
   registration first, optionally by `state` (an unknown state is `400`). Items carry only
   `refund_scope_id`, `refund_request_id`, `order_id`, `state`, `revision`, `registered_at`;
   per-line quantities stay on `GET /refund-scopes/{refund_scope_id}`.
   `GET /refund-scopes?order_id=` is unchanged.
4. **Indexes without the warehouse prefix** (`V2__admin_unscoped_list_indexes.sql`) so the
   unfiltered ordering never sorts a table: `stock_movement (recorded_at DESC, movement_id DESC)`
   and `(source_operation_type, recorded_at DESC, movement_id DESC)`; the three partial queue
   indexes repeated without `warehouse_id`; `refund_scope (registered_at DESC, refund_scope_id
   DESC)` and `(state, registered_at DESC, refund_scope_id DESC)`. The V1 indexes stay and still
   serve the warehouse-scoped variants. Stock positions already order by the primary key.

## Consequences

- The Admin UI loads every screen immediately and treats the filters as optional.
- A request that was `400` (`warehouse_id` or `sku_id` missing, or a queue without
  `warehouse_id`) is now `200`. No caller depended on the `400`.
- Writes pay for seven more indexes. The movement ledger is the largest table (seven-year
  retention); its two extra indexes add roughly one B-tree entry per movement, which is
  accepted to keep the default "newest first" screen index-ordered.
- The warehouse filter is no longer a safety net against a very large first page; the page
  size cap (`limit <= 100`, default 50) is the only bound, as it already was for the
  warehouse-scoped queries.

## Migration impact

Additive. Contract `admin/v1` goes `1.0.0 → 1.1.0`: parameters become optional, one query
parameter and one operation are added, no field is removed or renamed. Flyway `V2` only adds
indexes (`CREATE INDEX`, safe on the current table sizes; on a large production ledger create
the movement indexes `CONCURRENTLY` outside Flyway first).

## Verification

`AdminOperationsIT.listsAndQueuesAreCompleteWithoutAnyFilter` (positions, movements with and
without `source_operation_type`, dispatch and quarantine queues across two warehouses) and the
extended `RefundReturnIT` (inspection and quarantine queues without a warehouse, scope summaries
with and without `state`, `400` for an unknown state).
