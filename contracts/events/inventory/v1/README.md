# Inventory public facts v1

Inventory publishes CloudEvents from its transactional outbox with at-least-once
delivery. Order deduplicates by `id`; Search obtains idempotency from the monotonic
per-SKU revision. All consumers continue a business trace with
`correlationid`/`causationid` and reject stale facts using `aggregaterevision`.

| Topic | Key | Consumers | Facts |
|---|---|---|---|
| `t.inventory.stock.events.v1` | `sku_id` | Search | Inventory-owned per-SKU availability snapshots |
| `t.inventory.order.events.v1` | `order_id` | Order | Physical line dispatch and selected-scope refund inspection completion only |

Reserve/Commit/Release and fenced late-payment recovery return durable HTTP command
receipts and publish no reservation-result event. Order stores stable command/request
checkpoints and recovers unknown outcomes by command query or same-ID/body replay through
the [Workflow API](../../../http/inventory/workflow/v1/openapi.yaml). Current reservation
queries support reconciliation; a historical command receipt does not establish current
state. Required availability work and public outbox records still commit atomically with
the transition that creates them. See
[Inventory ADR-020](../../../../docs/inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md).

The refund inspection-completion fact contains every line in the immutable selected
scope with expected/received/passed/failed quantities. It is emitted after final
inspection of all expected selected quantity; failed inspection remains quarantine and
does not express a payout decision.

`sequence` is the durable Inventory export sequence. A single fenced sequencer assigns
it after commit, so it follows commit order; it may contain permanent gaps and is not a
promise of global Kafka ordering. Only sequenced facts are relayed
([Inventory ADR-022](../../../../docs/inventory/decisions/ADR-022-commit-ordered-export-sequence.md)). Search guards by
per-SKU `aggregaterevision` and recovers with the
[Export API](../../../http/inventory/exports/v1/openapi.yaml), not broker retention
alone. Inventory must not publish a SKU availability fact until Search has registered
that the corresponding Product-owned SKU document exists. Both topics retain public
facts for at least 30 days; each consumer owns its retry and recovery policy. Order
uses an inbox/DLQ; Search uses revision-guarded no-upsert writes and full rebuild.

Examples live in [`contracts/examples/events`](../../../examples/events).
