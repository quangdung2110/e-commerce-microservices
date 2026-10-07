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

Root stack/PowerShell: `pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -Services order`
từ gốc repo; `-CheckOnly` kiểm tra connector và mọi task RUNNING, `-ValidateOnly`
kiểm tra template không gọi mạng. Script thay DB fields, giữ nguyên EventRouter
expression và không log password. Các lệnh Bash bên dưới dành cho môi trường có Perl.

```bash
perl -pe 's/\$\{(\w+):([^}]*)\}/$ENV{$1} \/\/ $2/ge' debezium-connector-order-outbox.json > /tmp/order-connector.json
```

```bash
curl -sS -X POST -H "Content-Type: application/json" --data @/tmp/order-connector.json http://localhost:8084/connectors
```

```bash
curl -sS http://localhost:8084/connectors/order-outbox-connector/status
```

Cấu hình đã được smoke test với `debezium/connect:3.0.0.Final` + `cp-kafka:7.5.0`: row
`INTEGRATION_FACT` tới `t.order.events` với key `order_id`, row `NOTIFICATION_COMMAND` tới
`t.notification.transactional.v1` với key `orders/{order_id}`; value là CloudEvent nguyên
vẹn, header `id`, `ce_type`, `order_message_class`.

Receipt observer (`order-outbox-receipt-observer`) đọc lại cả hai topic và ghi
`outbox_delivery_receipts(event_id,destination_topic)`; trimmer chỉ xoá row quá 15 ngày khi
đã có receipt khớp chính xác và qua safety delay. Connector hoặc observer dừng thì row được
giữ lại (rò rỉ dung lượng an toàn), không mất event.
