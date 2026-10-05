# Debezium connector của Order

`debezium-connector-order-outbox.json` relay bảng append-only `public.outbox_events`
([Order ADR-003/ADR-016](../../../../../../../docs/order/decisions/README.md)) sang hai
destination trong allowlist:

- `t.order.events`: hai Order fact `com.ecommerce.order.paid.v1` và
  `com.ecommerce.order.completed.v1`, key = `order_id`.
- `t.notification.transactional.v1`: năm command `com.ecommerce.notification.requested.v1`
  (`ORDER_*`), key = `orders/{order_id}` (subject).

`EventRouter` route theo cột `destination_topic`, dùng `partition_key` làm Kafka key và
`payload` (CloudEvent đầy đủ, đã có `sequence`) làm value; `event_id` là CloudEvents `id`
ổn định xuyên relay retry. Chỉ capture `INSERT`; trimmer xoá row cũ không sinh message.
Bảng có CHECK allowlist destination nên row sai topic không thể được ghi.

Kafka Connect không tự thay placeholder `${VAR:default}`; thay trước khi đăng ký (Kafka
Connect publish cổng host `8084`):

```bash
perl -pe 's/\$\{(\w+):([^}]*)\}/$ENV{$1} \/\/ $2/ge' debezium-connector-order-outbox.json > /tmp/order-connector.json
```

```bash
curl -sS -X POST -H "Content-Type: application/json" --data @/tmp/order-connector.json http://localhost:8084/connectors
```

```bash
curl -sS http://localhost:8084/connectors/order-outbox-connector/status
```

Receipt observer (`order-outbox-receipt-observer`) đọc lại cả hai topic và ghi
`outbox_delivery_receipts(event_id,destination_topic)`; trimmer chỉ xoá row quá 15 ngày khi
đã có receipt khớp chính xác và qua safety delay. Connector hoặc observer dừng thì row được
giữ lại (rò rỉ dung lượng an toàn), không mất event.
