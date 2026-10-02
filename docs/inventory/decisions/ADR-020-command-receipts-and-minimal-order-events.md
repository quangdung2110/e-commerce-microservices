# ADR-020 — Command receipts and minimal Inventory events for Order

## Status

Accepted — 2026-10-02. Supersedes the reservation-result publication part of
ADR-014 and refines the per-command outbox wording of ADR-011 and ADR-019.
Their authoritative-state, atomicity, delivery and recovery guarantees remain active.

## Context

Order is the sole caller and decision owner for Reserve, Commit, Release and fenced
late-payment recovery. Inventory does not auto-expire reservations or decide payment.
These commands already return durable results, and Order stores stable command IDs,
request digests and remote outcomes in Saga checkpoints. Unknown responses are recovered
by command query/replay; current reservation queries support reconciliation.

Publishing the same command results to Order adds another result-processing path without
a defined business consumer. Physical dispatch and completed return inspection are
different: warehouse ADMIN produces those facts after Order's command, so Order needs
their asynchronous notification.

## Decision

- Remove these public types from the Release 1 Inventory-to-Order contract:
  `com.ecommerce.inventory.reservation.reserved.v1`,
  `com.ecommerce.inventory.reservation.committed.v1`, and
  `com.ecommerce.inventory.reservation.released.v1`.
- `t.inventory.order.events.v1` carries only
  `com.ecommerce.inventory.reservation.line-dispatched.v1` and
  `com.ecommerce.inventory.refund.all-items-inspection-completed.v1`.
  Search retains `com.ecommerce.inventory.sku-availability.changed.v1` on its own topic.
- Order persists the stable command ID and canonical request before the remote call,
  then checkpoints the matching durable result. On timeout/crash it queries
  `GET /internal/inventory/v1/commands/{command_id}` or replays the same command ID/body.
  A missing query result alone does not prove that an in-flight command failed.
  Order never compensates, creates a replacement reservation or advances a successful
  step from an unknown outcome. Exhausted retries retain durable recovery work and alert.
- Reservation queries establish current state; a stored command outcome establishes
  the result of that specific command. A successful old Reserve receipt must not be
  mistaken for a root still being reserved after later transitions.
- Every accepted mutation still atomically commits authoritative state, command/operation
  identity and movement/audit evidence. Required public outbox records and availability
  dirty markers participate in the same transaction that creates their corresponding fact
  or work. No reservation-result public outbox record is created merely to acknowledge a
  command. Internal evidence does not become a broker contract.
- Reserve, reservation release, committed release and successful timed-out-payment
  recovery durably mark affected SKUs when `available_to_sell` changes. The asynchronous
  availability projector alone creates Search events under ADR-016. Normal Commit and
  physical dispatch do not change `available_to_sell`; dispatch still atomically creates
  its Order-facing fact. Inspection completion retains its atomic outbox guarantee.
- Reconciliation uses Order checkpoints, Inventory command/current-state queries and
  orphan alerts. Inventory never auto-releases or corrects stock to replace an event.

## Consequences and compatibility

Order has one command-result recovery path and two Inventory event consumers. Its
payment watermark, release/dispatch guards, selected-line refund gate and scope-finalization
receipts remain unchanged. Removing public acknowledgments does not remove stock history
or weaken no-oversell, idempotency, audit or at-least-once delivery of the remaining facts.

Inventory implementation is not started and the AsyncAPI adoption status is `proposed`.
This is a coordinated pre-implementation correction of that proposed v1 contract:
remove its three messages, send operations, schemas, examples and unused allocation-line
event helper; align Inventory/Order documentation, workflow contract descriptions and
the interaction catalog. No deployed-consumer migration is asserted. If any consumer
has adopted the old proposal, apply the documented breaking-change/version and 90-day
parallel-support policy before stopping publication; this decision grants no exemption.

Historical ADR bodies remain evidence. The decision index records this refinement;
the brainstorm contract catalogue is aligned and earlier workflow/consistency notes
explicitly defer publication scope to this ADR.

## Verification

Before implementation, validate the AsyncAPI and remaining schemas/examples, resolve
contract references and documentation links, and check that the active public catalog
contains exactly the retained three Inventory types. Removed types may appear only in
this decision's removal record.

Implementation gates cover successful commands with no reservation-result publication;
Reserve/Release/recovery dirty markers; no new dirty marker for normal Commit;
response loss and crash recovery with the same ID/body; durable rejected-result replay;
bounded retry exhaustion with durable repair; query-current-state versus original receipt;
broker outage without blocking a command receipt; and duplicate/reordered dispatch and
inspection facts without a second effect or a lost refund gate.

See [interfaces.md](../interfaces.md), [data.md](../data.md),
[workflows.md](../workflows.md), [operations.md](../operations.md),
[Order interfaces](../../order/interfaces.md#62-inventory-events),
[Order checkpoints](../../order/data.md),
[Inventory AsyncAPI](../../../contracts/events/inventory/v1/asyncapi.yaml) and
[Workflow OpenAPI](../../../contracts/http/inventory/workflow/v1/openapi.yaml).
