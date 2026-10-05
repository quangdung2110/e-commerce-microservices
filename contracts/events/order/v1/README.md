# Order Events v1

This contract describes the complete asynchronous integration surface of Order Service.
Order publishes only facts with registered, implemented consumers:

- `com.ecommerce.order.paid.v1` is consumed by Cart Service to remove the purchased
  SKUs without changing cart activity.
- `com.ecommerce.order.completed.v1` is consumed by Review Service to grant one
  entitlement per purchased `(product_id, sku_id)`.

Notification does not consume these facts; Order sends five
`com.ecommerce.notification.requested.v1` commands on the Notification-owned
transactional lane from the same multi-destination outbox. Analytics and Payment do not
currently have an implemented consumer for any other Order fact. Consequently Order does not publish created, cancelled, generic
status-changed, line-fulfillment, refunded or refund-failed events. HTTP read models and
operation resources expose those states. A new event is added only after its consumer,
business action and catalog interaction are registered.

Both messages use CloudEvents 1.0 structured JSON on `t.order.events`. Kafka key,
`data.order_id` and `subject` use the same canonical, unpadded base-10 Order ID. Delivery
is at-least-once. `id` is the stable outbox event UUID and must not change during relay
retry. `sequence` is monotonic over the integration events emitted for one order; gaps
are valid and consumers must not interpret it as the Order FSM version.

`ORDER_PAID` contains only `order_id`, `user_id` and the unique purchased `sku_ids[]`
that Cart needs. It deliberately excludes amount, payment transaction/provider data and
recovery/anomaly metadata because no registered consumer needs them. A success after
terminal non-capture opens reconciliation and never emits this fact. CloudEvents `time` is
the durable PAID transition time.

`ORDER_COMPLETED.items[]` is mandatory because Review must not synchronously call Order
or Product on its write path. `variant_name` is nullable for historical orders, but new
Order producers populate it whenever the accepted checkout snapshot contains it. The
producer groups items by `(product_id, sku_id)` and sends one entry per pair; quantity
does not grant multiple review entitlements.

The same AsyncAPI also documents Order's receive operations without copying schemas
owned by other providers:

- Payment terminal/refund/dispute facts from `t.payment.events.v2`, dispatched by
  exact namespaced CloudEvents `type` and validated against `dataschema`. Dispute
  facts only open/update Order financial reconciliation; they never imply a refund,
  payment-status rewrite or fulfillment transition;
- Inventory physical-dispatch and completed-inspection facts from
  `t.inventory.order.events.v1`;
- verified Delivery facts from `t.delivery.order.events.v1`.

Order does not consume Payment initiation facts or Inventory reserve/commit/release
acknowledgments. Inventory publishes no reservation-result events; its reservation
commands return durable HTTP receipts and provide same-command recovery queries/replay
([Inventory ADR-020](../../../../docs/inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md)).
The producer is implemented in [`java/services/order-service`](../../../../java/services/order-service/README.md)
(multi-destination outbox + `debezium-connector-order-outbox.json`); its tests validate both
facts and all five notification commands against the provider JSON Schemas.

Every accepted inbound event first passes semantic identity/revision validation and is
committed to a durable inbox plus workflow-message outbox. Only the dispatcher signals
Temporal with a stable message ID; consumers never depend on a DB-to-Temporal dual write.
