# Debezium connector của Promotion outbox

`debezium-connector-promotion-outbox.json` là artifact vận hành, không phải file
được application đọc. Đăng ký bằng Kafka Connect REST API (worker ở `kafka-connect`
của docker-compose, cổng 8084):

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
(`max_replication_slots` ở compose là 4: Product, Inventory, Promotion và một slot dự
phòng). Không cần plugin thêm cho Kafka Connect.

## Kiểm tra nhanh

```bash
curl -sS http://localhost:8084/connectors/promotion-outbox-connector/status
```

Connector dừng thì WAL bị giữ lại và outbox oldest-age tăng (docs/promotion/operations.md §5).
