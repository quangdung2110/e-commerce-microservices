# Debezium connector của Promotion outbox

`debezium-connector-promotion-outbox.json` là artifact vận hành, không phải file
được application đọc. Đăng ký bằng Kafka Connect REST API (worker ở `kafka-connect`
của docker-compose, cổng 8084). Với root docker-compose, service one-shot `kafka-connect-init`
(`infra/kafka-connect/register-connectors.sh`) tự đăng ký connector này mỗi lần `docker compose up`;
chạy lại tay bằng `docker compose up kafka-connect-init` (idempotent). Đăng ký thủ công:

```bash
curl -sS -X PUT http://localhost:8084/connectors/promotion-outbox-connector/config \
  -H 'Content-Type: application/json' \
  -d "$(python -c "import json;print(json.dumps(json.load(open('debezium-connector-promotion-outbox.json'))['config']))")"
```

`PUT .../config` chỉ nhận phần `config`; dùng `POST /connectors` với cả file khi
tạo mới. Placeholder `${DB_HOSTNAME:postgres}` được Connect phân giải từ biến môi
trường của worker.

## Cách route topic

`EventRouter` đọc `promotion_outbox_events` và dựng message:

- message key lấy từ cột `partition_key` (= `product_id`, ordering scope của markdown snapshot);
- `payload` được parse thành JSON object (`expand.json.payload=true`) nên message value
  là CloudEvent structured JSON đúng như `contracts/events/promotion/v1/asyncapi.yaml`;
  `CloudEvents.id` = `event_id` nên ổn định qua mọi lần CDC retry;
- `event_type`, `aggregate_type`, `aggregate_id`, `source_revision` được copy sang header
  `ce_type`, `ce_aggregate_type`, `ce_aggregate_id`, `ce_source_revision`.

| event_type | topic |
|---|---|
| `com.ecommerce.promotion.product-markdown-snapshot.v1` | `t.promotion.markdown.events` |

Đây là message type duy nhất ở v1 (ADR-016). Claim, confirm và `cancel-confirmed`
không ghi outbox; finalizer Redis chạy từ `promotion_command_receipts.redis_finalized_at`.

## Yêu cầu hạ tầng

PostgreSQL phải chạy với `wal_level = logical` và còn replication slot trống
(`max_replication_slots=10` ở docker-compose, đủ cho các connector outbox của Product,
Inventory, Order, Payment, Promotion…). Không cần plugin thêm cho Kafka Connect.

Với root stack, có thể đăng ký bằng script chung (PowerShell 7):
`pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -Services promotion`
(thêm `-CheckOnly` để chỉ kiểm trạng thái). Script không nằm trong tập mặc định của Checkout.

## Bằng chứng

`OutboxCdcIntegrationTest` chạy đúng file connector này trên PostgreSQL `wal_level=logical`, Kafka và
`debezium/connect:3.0.0.Final` thật: mỗi dòng outbox lên `t.promotion.markdown.events` với key
`product_id`, header `ce_*` và thứ tự theo `source_revision`; envelope là JSON object khớp payload đã lưu
(field `null` tường minh như `discount.max_discount_vnd` bị EventRouter bỏ) và hợp lệ với JSON Schema.

## Kiểm tra nhanh

```bash
curl -sS http://localhost:8084/connectors/promotion-outbox-connector/status
```

Connector dừng thì WAL bị giữ lại và outbox oldest-age tăng (docs/promotion/operations.md §5).
