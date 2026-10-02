---
doc_id: order-adr-013
service: order
status: accepted
date: 2026-10-02
decision_owners:
  - Order Service team
  - Inventory Service team
supersedes: null
evidence:
  - docs/order/workflows.md
  - docs/order/domain.md
  - docs/order/data.md
  - docs/inventory/interfaces.md
  - contracts/http/order/v1/openapi.yaml
  - contracts/http/order/internal/v1/openapi.yaml
  - contracts/http/inventory/workflow/v1/openapi.yaml
---

# ADR-013 — Durable finalization of the existing Inventory Refund Scope

## Status

Accepted — 2026-10-02. Refines ADR-010/011 and makes Order's orchestration of
Inventory's existing close/cancel guards explicit. Historical ADRs remain unchanged.
This is a target design decision, not implementation evidence.

## Context

Inventory requires Order to close a completed return scope or end an incomplete
return. The Order refund saga previously stopped after persisting the Payment result,
leaving Inventory in `INSPECTION_COMPLETED`. Its 30-day manual lane also lacked a
decision that ends a return without payout. ADR-010 mentions `CLOSED_INCOMPLETE`, but
the Order case FSM, SQL example and public enum did not represent that outcome.

## Decision

### Financial result and physical finalization

Only `BUYER_RETURN_SELECTED_LINES` has a Refund Scope. Admin pre-dispatch refund
uses Inventory's atomic release evidence and has finalization `NOT_REQUIRED`.
For Buyer, `FinalizeRefundResultActivity` commits the validated financial result
and a durable scope-finalization intent/checkpoint/dispatch in one Order transaction.
The subsequent remote Activity is outside that transaction:

| Trigger | Inventory guard | Activity / reason | Inventory outcome |
|---|---|---|---|
| `REFUND_SUCCESS` | `INSPECTION_COMPLETED` | `CloseRefundScopeActivity`, `REFUNDED` | `CLOSED` |
| Terminal `REFUND_FAILED` | `INSPECTION_COMPLETED` | `CloseRefundScopeActivity`, `REJECTED` | `CLOSED` |
| Validated `REFUND_REVERSED`, with no earlier finalization intent | `INSPECTION_COMPLETED` | `CloseRefundScopeActivity`, `REJECTED` | `CLOSED`; financial case remains manual |
| Approved no-payout decision; some expected quantity missing and all received units final-inspected | `IN_PROGRESS` | `CloseIncompleteRefundScopeActivity`, decision reason | `CLOSED_INCOMPLETE` |
| Approved no-payout decision; no receipt | `REGISTERED` | `CancelRefundScopeActivity`; reason stays in Order audit | `CANCELLED` |

`REJECTED` here means the terminal financial refund did not settle; it does not mean
inspection `FAIL`. All expected units final-inspected, including failed units, still
require the ordinary Payment call. A reversal never changes an existing finalization
command/payload, reopens a terminal scope, creates a scope or starts another refund.
If success already froze reason `REFUNDED`, that exact close command still completes
after reversal; the reversal is separate financial evidence.

### Durable execution

Case financial status and `inventory_scope_finalization_status` are separate.
Finalization is `NOT_REQUIRED`, `NOT_REQUESTED`, `PENDING`, `RECOVERY_REQUIRED`,
`CONFLICT`, or `COMPLETED`. A persisted intent freezes operation, reason, command
ID, request digest, scope identity and finalization generation before any HTTP call.
Response evidence includes all resource IDs, terminal outcome, scope revision and
response digest. Only a matching accepted command result proves completion.

Retry an unknown/503 outcome with the same command ID and body; query
`GET /commands/{command_id}` after transport loss. Never select a different endpoint
because a response timed out. After three Activity attempts, keep the intent as
`RECOVERY_REQUIRED`, alert Order on-call, and durably dispatch
`OrderRefundScopeFinalizationWorkflow` on `order-refund-queue`, with ID
`order-refund-scope-finalization-{order_id}-{generation}`. It only repairs the frozen
Inventory command; it cannot call Payment or change a financial fact. A finalization
reconciler scans unresolved intents even on financially terminal cases.
Retry exhaustion/conflict also creates or attaches an audited scope-repair reconciliation
obligation without changing the financial status. Finance/Ops may resume this frozen
command on `SUCCEEDED`/`FAILED` cases; no `REFUNDED` rollback is required to enter repair.
If a finalization run failed, dispatcher may restart the same generation/input/key;
technical retry never advances the generation or starts another Payment refund.

A durable `409` becomes `CONFLICT`, not an automatic retry/new decision. Finance/Ops
must reconcile current evidence. An explicitly approved corrected attempt may advance
the generation and use a new command ID only after the prior command is proven rejected;
retain every prior checkpoint. Accepted or unknown commands cannot be superseded.
Receipt/inspection races are decided by Inventory's atomic guards. A failed incomplete
close never falls back to completed close, payout, or scope replacement.
If conflict came from a mismatched transport response, reconciliation may instead
recover the matching accepted original-command receipt and mark `COMPLETED` without
changing the generation/key. A valid receipt, not the bad response, is the authority.

Order may record master/payment `REFUNDED` while closure is pending. That fact is
never rolled back because Inventory is unavailable. The Buyer return operation stays
`RUNNING` while closure is pending, or `MANUAL_REVIEW_REQUIRED` in recovery/conflict;
after closure it maps the persisted financial outcome. Workflow termination requires
either matching completion evidence or a durable dispatch that owns the remaining work.

### Ending a return without payout

The 30-day timer only enters `MANUAL_VERIFICATION_REQUIRED` and creates/attaches the
audited reconciliation case. It never closes Inventory or bypasses the Payment gate.
Finance/Ops may submit `CLOSE_RETURN_WITHOUT_REFUND` through the existing reconciliation
decision API with the bound refund request/scope, evidence and a reason
`RETURN_WINDOW_EXPIRED`, `PACKAGE_LOST`, or `CUSTOMER_WITHDREW`.

The server locks the reconciliation/refund case, checks expected case revision,
Buyer mode/manual status, absence of a refund transaction or accepted/unknown Payment
initiation checkpoint, and absence of full inspection completion. It obtains and
persists a matching Inventory query checkpoint with complete per-line counters.
Only `REGISTERED` with zero receipt or `IN_PROGRESS` with missing expected units and
every received unit final-inspected permits the decision. No remote call is inside
the Order transaction; local guards are rechecked when recording the decision.

The transaction freezes the finalization intent and dispatch, blocking Payment initiation
and financial resume while this no-payout decision is unresolved. Later completion
signals are retained as evidence, not used to bypass that decision. Inventory guards
remain authoritative when receipts race the close. A rejected command requires a fresh
audited decision before resume or a corrected finalization attempt.
An approved resume after proven no-payout command rejection and matching full completion
clears only the active intent to `NOT_REQUESTED`, retains rejected generation/decision
history, and resumes the original case's first Payment gate. The eventual financial
result creates a new `CLOSE` generation. Unknown/accepted no-payout commands cannot be
cleared or superseded this way.

After Inventory confirms `CLOSED_INCOMPLETE` or `CANCELLED`, Order sets the same refund
case to `CLOSED_INCOMPLETE`, sets `resolved_at`, and resolves the return-closure
obligation. Reconciliation closes only when no other financial/dispute obligation remains.
The latter Inventory outcome represents an entirely missing return. Master remains
`RETURN_REQUESTED`, payment remains `PAID`, and `refund_transaction_id` remains null;
the API/UI shows the case ended without payout. No refund notification/event is emitted.
Lifetime case/scope identity and Buyer one-shot guard remain consumed.

## Compatibility and migration

Inventory endpoint shapes, state guards and reason enums are unchanged. Order public
v1 gains `CLOSED_INCOMPLETE` and optional scope-finalization progress. Order internal
v1 gains the no-payout resolution and its conditional payload. Both Order contracts
remain proposed with no implementation evidence; consumers must support the new enum
before rollout. Do not treat response-enum expansion as universally compatible with
generated clients. An already adopted incompatible consumer requires a versioned
contract and migration before rollout.

Add/backfill finalization metadata/checkpoints/dispatches. Reconcile historical Buyer
`SUCCEEDED`/`FAILED`/manual cases against Inventory before creating an intent; never
reissue Payment. Admin backfills to `NOT_REQUIRED`. Preserve terminal Inventory
identity and original command evidence; do not invent a close receipt from a query.
Version Temporal workflow changes for replay and register the recovery workflow on
the existing refund queue. Update all target docs, catalogs, contracts and runbooks.

## Verification

Prove success/failure close, mixed PASS/FAIL, no-scope Admin, timeout without automatic
close, partial/no receipt manual close, uninspected rejection, final receipt/inspection
race, local-commit/remote-call crash, accepted close with lost response, recovery after
retry exhaustion, mismatched response, durable 409 with corrected generation, reversal
before/after closure, and lifetime uniqueness. Every recovery test must show no second
Payment refund and no rollback of financial evidence.
