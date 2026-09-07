# Inventory public facts v1

Inventory publishes CloudEvents from its transactional outbox with at-least-once
delivery. Consumers deduplicate by `id`, continue a business trace with
`correlationid`/`causationid`, and reject stale facts using `aggregaterevision`.

| Topic | Key | Consumers | Facts |
|---|---|---|---|
| `t.inventory.stock.events.v1` | Stock position `(warehouse_id, sku_id)` or `warehouse_id` | Search | Stock-position and warehouse-availability snapshots |
| `t.inventory.order.events.v1` | `order_id` | Order | Reserved, committed, released, physical dispatch, refund inspection completion |

`sequence` is the durable Inventory export sequence. It is increasing at creation,
may contain gaps, and is not a promise of global Kafka ordering. Search recovers with
the [Export API](../../../http/inventory/exports/v1/openapi.yaml), not broker retention
alone. Both topics retain public facts for at least 30 days; each consumer owns its
inbox/dedupe marker, retry policy, and DLQ.

Examples live in [`contracts/examples/events`](../../../examples/events).
