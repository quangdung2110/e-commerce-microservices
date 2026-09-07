# Inventory public facts v1

Inventory publishes CloudEvents from its transactional outbox with at-least-once
delivery. Order deduplicates by `id`; Search obtains idempotency from the monotonic
per-SKU revision. All consumers continue a business trace with
`correlationid`/`causationid` and reject stale facts using `aggregaterevision`.

| Topic | Key | Consumers | Facts |
|---|---|---|---|
| `t.inventory.stock.events.v1` | `sku_id` | Search | Inventory-owned per-SKU availability snapshots |
| `t.inventory.order.events.v1` | `order_id` | Order | Reserved, committed, released, physical dispatch, refund inspection completion |

`sequence` is the durable Inventory export sequence. It is increasing at creation,
may contain gaps, and is not a promise of global Kafka ordering. Search guards by
per-SKU `aggregaterevision` and recovers with the
[Export API](../../../http/inventory/exports/v1/openapi.yaml), not broker retention
alone. Inventory must not publish a SKU availability fact until Search has registered
that the corresponding Product-owned SKU document exists. Both topics retain public
facts for at least 30 days; each consumer owns its retry and recovery policy. Order
uses an inbox/DLQ; Search uses revision-guarded no-upsert writes and full rebuild.

Examples live in [`contracts/examples/events`](../../../examples/events).
