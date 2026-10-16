# Order Service

Order lifecycle owner: master/sub-order snapshots, Temporal checkout/refund sagas,
PostgreSQL read/search path, durable inbox for Payment/Inventory facts and a
multi-destination outbox for Order facts and Notification commands. Design and decisions:
[docs/order](../../../docs/order/README.md) (runtime and pre-implementation clarifications in
[ADR-020](../../../docs/order/decisions/ADR-020-release-1-implementation-clarifications.md)).

| Item | Value |
|---|---|
| Runtime | Java 21, Spring Boot 3.3, JDBC + Flyway, Spring Kafka, Temporal Java SDK 1.25 |
| HTTP | `contracts/http/order/v1` (Buyer/Admin via Gateway), `contracts/http/order/internal/v1` (Checkout, Finance/Ops) |
| Checkout HTTP wait | `DeferredResult`, PostgreSQL V3 commit NOTIFY, one dedicated LISTEN session/replica and bounded batch recovery; [ADR-027](../../../docs/order/decisions/ADR-027-async-submission-checkpoint-waiters.md) |
| Events | Produces `t.order.events` (`order.paid.v1`, `order.completed.v1`) and `t.notification.transactional.v1` (`ORDER_*`); consumes `t.payment.events.v2`, `t.inventory.order.events.v1`; delivery is confirmed by an Admin through `POST /api/v1/admin/orders/{order_id}/delivery-confirmations` (no Delivery service, ADR-021) |
| Temporal | `OrderCheckoutWorkflow`, `OrderRefundWorkflow`, `OrderRefundScopeFinalizationWorkflow`, `OrderFinancialReconciliationWorkflow` on `order-checkout-queue`, `order-refund-queue`, `order-financial-reconciliation-queue` |
| Local port | 8095 (compose) |

Build, test, local run and configuration: [docs/order/development.md](../../../docs/order/development.md)
and [docs/order/operations.md §2](../../../docs/order/operations.md). Debezium connector:
[src/main/resources/kafka/README.md](src/main/resources/kafka/README.md).
