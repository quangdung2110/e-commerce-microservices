# Debezium connector của Product outbox

`debezium-connector-product-outbox.json` là artifact vận hành, không phải file
được application đọc. Với root docker-compose, service one-shot `kafka-connect-init`
(`infra/kafka-connect/register-connectors.sh`) tự đăng ký connector này cùng các outbox connector
khác mỗi lần `docker compose up`; chạy lại tay bằng `docker compose up kafka-connect-init`
(idempotent: bỏ qua connector đang RUNNING, tạo lại connector FAILED). Đăng ký thủ công bằng
Kafka Connect REST API:

```bash
curl -sS -X PUT http://localhost:8084/connectors/product-outbox-connector/config \
  -H 'Content-Type: application/json' \
  -d @<(python -c "import json,sys;print(json.dumps(json.load(open('debezium-connector-product-outbox.json'))['config']))")
```

`PUT .../config` chỉ nhận phần `config`; dùng `POST /connectors` với cả file khi
tạo mới. Placeholder `${DB_HOSTNAME:postgres}` được Connect phân giải từ biến môi
trường của worker.

## Yêu cầu hạ tầng

PostgreSQL phải chạy với `wal_level = logical` và còn replication slot trống.
Compose ở repo root đã bật sẵn qua `command` của service `postgres`.

Không cần plugin thêm cho Kafka Connect: cấu hình chỉ dùng `EventRouter` của
Debezium và `RegexRouter` có sẵn trong Kafka.

## Cách route topic

`EventRouter` đọc bảng outbox và dựng message:

- message key lấy trực tiếp từ cột `partition_key` (ADR-012) — `product_id` cho
  event Product/SKU, `category_id` cho Category, root category ID cho control
  event;
- `payload` được parse thành JSON object (`expand.json.payload=true`) nên message
  value là CloudEvent structured JSON đúng như AsyncAPI mô tả; connector không tự
  dựng envelope nghiệp vụ;
- `event_type`, `aggregate_type`, `aggregate_id` được copy sang header
  `ce_type`, `ce_aggregate_type`, `ce_aggregate_id`.

`route.by.field = event_type` đưa mọi record sang topic tạm
`outbox.<event_type>`, rồi ba `RegexRouter` đổi sang topic cuối:

| event_type | topic |
|---|---|
| `com.ecommerce.catalog.sku-upserted.v1`, `...sku-deleted.v1`, `...product-deleted.v1` | `t.product.events` |
| `com.ecommerce.catalog.category-upserted.v1`, `...category-deleted.v1` | `t.category.events` |
| `com.ecommerce.catalog.reindex-requested.v1` | `t.catalog.control.events` |

Ba regex là full-match và không giao nhau, nên thứ tự transform không ảnh hưởng
kết quả. Thêm event type mới thì phải thêm/đổi regex tương ứng, nếu không message
sẽ nằm lại ở topic `outbox.*`.

Đổi tên/kiểu cột `partition_key`, hay đổi mapping topic/key ở đây, là breaking
change hạ tầng và phải rollout phối hợp với consumer (ADR-012).

## Kiểm tra nhanh

```bash
curl -sS http://localhost:8084/connectors/product-outbox-connector/status
```

Replication slot lag và connector state là alert critical: connector dừng thì WAL
bị giữ lại và có thể làm đầy đĩa PostgreSQL (docs/product/operations.md).
