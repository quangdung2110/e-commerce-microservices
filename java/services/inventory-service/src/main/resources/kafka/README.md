# Debezium connector của Inventory

`debezium-connector-inventory-outbox.json` là artifact vận hành của
[Inventory ADR-022/ADR-023](../../../../../../../docs/inventory/decisions/README.md). Connector
chỉ đọc `public.published_event` — bảng mà export sequencer đã gán `sequence` theo thứ tự
commit — và không bao giờ đọc `outbox_event` (fact chưa được sequence).

- Chỉ capture `INSERT` (`skipped.operations: u,d,t`): retention xoá row cũ không sinh
  message.
- `EventRouter` dùng cột `topic` làm topic đích (`t.inventory.stock.events.v1` hoặc
  `t.inventory.order.events.v1`), `message_key` làm Kafka key (`sku_id` hoặc `order_id`) và
  `payload` (CloudEvent đầy đủ, đã có `sequence`) làm value.
- Retry vô hạn với backoff tối đa 5 phút (`errors.retry.delay.max.ms`), khớp
  [operations.md §4](../../../../../../../docs/inventory/operations.md).

- Retention của hai topic là **đúng 30 ngày** (`retention.ms=2592000000`, vừa là sàn vừa là trần) theo
  [ADR-028](../../../../../../../docs/inventory/decisions/ADR-028-post-implementation-audit-corrections.md) để consumer
  (ví dụ cửa sổ bảo vệ tombstone của Search) chọn được cửa sổ dài hơn hẳn. Compose local bật auto-create topic và dùng
  retention mặc định của broker nên chưa đạt; Platform phải tạo tường minh `t.inventory.stock.events.v1` và
  `t.inventory.order.events.v1` (gate production trong [development.md](../../../../../../../docs/inventory/development.md)).

Kafka Connect không tự thay placeholder `${VAR:default}`, nên thay bằng biến môi trường
(hoặc giá trị mặc định) trước khi đăng ký trên stack local (Kafka Connect publish cổng host
`8084`):

Root stack/PowerShell: `pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -Services inventory`
từ gốc repo; `-CheckOnly` kiểm tra connector và mọi task RUNNING, `-ValidateOnly`
kiểm tra template không gọi mạng. Script thay DB fields, giữ literal EventRouter
expression và không log password. Các lệnh Bash bên dưới dành cho môi trường có Perl.

```bash
perl -pe 's/\$\{(\w+):([^}]*)\}/$ENV{$1} \/\/ $2/ge' debezium-connector-inventory-outbox.json > /tmp/inventory-connector.json
```

```bash
curl -sS -X POST -H "Content-Type: application/json" --data @/tmp/inventory-connector.json http://localhost:8084/connectors
```

Cấu hình này đã được smoke test với `debezium/connect:3.0.0.Final`: fact từ
`published_event` tới đúng topic, đúng key và giữ nguyên CloudEvent có `sequence`.

Kiểm tra trạng thái:

```bash
curl -sS http://localhost:8084/connectors/inventory-outbox-connector/status
```

Connector dừng thì WAL bị giữ lại, fact vẫn nằm bền trong `published_event` và cảnh báo
"oldest unpublished" của Inventory tăng; command vẫn tiếp tục cho tới ngưỡng backpressure.
