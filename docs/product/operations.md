---
doc_id: product-operations
service: product
scope: current
status: implemented
owners:
  - Product Service team
evidence:
  - java/services/product-service/src/main/resources/application.yml
---

# Product Service — Operations

## Cấu hình

Giá trị mặc định lấy từ `application.yml`; profile `prod` chỉ đổi Hikari `maximum-pool-size` lên
20 và `spring.kafka.listener.concurrency` lên 3. Compose đặt `SPRING_PROFILES_ACTIVE=prod`.

| Key (`product.*` nếu không ghi khác) | Biến môi trường | Mặc định |
|---|---|---|
| `server.port` | `SERVER_PORT` | `8081` (compose map host `8082:8081`) |
| `grpc.server.port` | `GRPC_PORT` | `9091` |
| `spring.datasource.url` / `username` / `password` | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:15432/product_service` / `postgres` / `postgres` (chỉ cho dev) |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `admin.required-role` / `user-id-header` / `roles-header` | — | `ADMIN` / `X-User-Id` / `X-User-Roles` |
| `internal.identity-header` | — | `X-Service-Identity` |
| `internal.reindex-clients` | — | `search-service` (hai export) |
| `internal.snapshot-clients` | — | `search-service`, `promotion-service` (snapshot một Product) |
| `internal.buyer-read-clients` | — | `product-detail-bff` |
| `export.cursor-secret` | `EXPORT_CURSOR_SECRET` | giá trị dev; môi trường thật lấy từ secret manager |
| `export.cursor-ttl` / `default-limit` / `max-limit` | — | `30m` / `200` / `500` |
| `export.product-topic` / `category-topic` | `PRODUCT_EVENTS_TOPIC` / `CATEGORY_EVENTS_TOPIC` | `t.product.events` / `t.category.events` |
| `export.offset-timeout` | — | `5s` (quá hạn là `503 CATALOG_UNAVAILABLE`) |
| `markdown.topic` / `dlq-topic` / `group-id` | `MARKDOWN_TOPIC` / `MARKDOWN_DLQ_TOPIC` / `MARKDOWN_GROUP_ID` | `t.promotion.markdown.events` / `t.promotion.markdown.events.dlq` / `product-markdown-consumer` |
| `markdown.lock-timeout` | — | `3s` (`SET LOCAL lock_timeout`; timeout là lỗi tạm thời) |
| `markdown.retry-attempts` / `retry-backoff` | — | `4` / `500ms`, nhân đôi mỗi lần; hết retry hoặc event sai contract thì sang DLQ |

Danh sách allowlist đổi được bằng cấu hình nhưng nhóm route cố định trong code; mesh vẫn là lớp
thực thi chính (xem [Interfaces](./interfaces.md)). Debezium connector
`product-outbox-connector` (slot `product_outbox_slot`, publication `dbz_product_outbox_publication`)
định nghĩa ở `src/main/resources/kafka`; compose chưa đăng ký nó tự động.

## SLI và telemetry

Theo dõi p95/p99 của admin API và gRPC, error rate, PostgreSQL pool/lock timeout,
outbox depth/age, Debezium connector health và replication-slot lag, Kafka consumer lag
của markdown consumer, retry/DLQ age và số snapshot replay, catalog reindex request age/
coalesce/failure, và số SKU fan-out bởi media mutation. Metric không dùng `product_id`,
`sku_id` hay rule ID làm label.

Search tự theo dõi stock-event/export lag của Inventory; Product Detail BFF theo dõi
latency/error của Inventory Availability Read API. Cả hai không phải consumer của Product.

## Health và degraded behavior

Readiness fail khi PostgreSQL không truy cập được. Consumer readiness phản ánh Kafka.
Kafka/Debezium lỗi không làm mất catalog write đã commit vì event nằm trong outbox, nhưng
làm Search/Promotion trễ và có nguy cơ WAL tăng; alert connector/slot lag là critical.

Khi Inventory không sẵn sàng, Product vẫn phục vụ catalog read/write; Checkout phải
trả lỗi/không hoàn tất reservation theo chính sách của Inventory. Product không có cache
hay local stock fallback.

## Workers và external workloads

| Workload | Owner | Trigger | An toàn |
|---|---|---|---|
| Markdown Consumer | Product | Kafka Promotion snapshots | Validate ACTIVE/INACTIVE shape, lock Product, guard `source_revision`, retry transient; missing Product ack no-op; ack sau DB commit |
| Debezium connector | Platform | Liên tục đọc WAL | At-least-once; theo dõi slot lag |
| Search catalog reindex | Search | Consume `CATALOG_REINDEX_REQUESTED` | Coalesce request; Product export category/Product kèm replay offsets, Search import rồi catch-up và alias swap |
| PDP buyer-read | Product Detail BFF | Synchronous internal query | Latency kỳ vọng p99 ≤ 250 ms vì BFF cắt ở tối đa 400 ms ([Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md)), đã gồm join category path, định nghĩa attribute/option và media giới hạn ([ADR-018](./decisions/ADR-018-resolved-buyer-product-detail-projection.md)), phải load test lại; alert latency/error của endpoint; stock freshness thuộc Inventory, không phải Product hay BFF |

Product không có scheduled job. Promotion chịu trách nhiệm ghi ACTIVE full payload hoặc INACTIVE tombstone qua
outbox tại logical campaign boundary và tự phục hồi schedule; Product theo dõi propagation
delay. Product Consumer và runbook replay xử lý sự cố delivery. Outbox retention thuộc
Platform/DB lifecycle và chưa được chạy trong Product application.


## Alerts và runbooks

Alert tối thiểu: PostgreSQL unavailable/pool exhausted, lock timeout tăng, consumer lag,
DLQ có message, Debezium connector fail hoặc slot lag cao, outbox tăng bất thường,
catalog reindex request quá SLA. Runbook markdown phải nêu owner Promotion, cách xác định
snapshot mới nhất và replay sau retry/DLQ; runbook outbox phải nêu owner Platform khi tuổi
hoặc dung lượng outbox vượt ngưỡng. Mọi runbook có chẩn đoán chỉ đọc, khắc phục, xác nhận
phục hồi và owner escalate.

Runbook markdown phải kiểm tra inactive tombstone đã advance revision và clear projection
configuration. Event tới Product đã hard delete được ack no-op; đối chiếu terminal
catalog-revision watermark phía Promotion, không yêu cầu Promotion phát reverse INACTIVE
sau khi consume Product tombstone.

Runbook export cho Search phải kiểm tra Kafka chụp đủ mọi partition và offsets giữ nguyên
trong cả export session. Kafka lỗi, export session mất nhất quán hoặc Debezium/outbox lag
không hội tụ là điều kiện thất bại reindex; Search giữ alias cũ và không cutover.

## Deploy và rollback

Migration phải additive và tương thích rollback trong ít nhất một chu kỳ deploy. Rollout
contract theo thứ tự: consumer chấp nhận schema mới → producer phát song song → chuyển
consumer → gỡ schema cũ sau thời gian quan sát. Không rollback migration destructive;
kiểm tra Debezium connector và offset trước/sau rollback để tránh replay ngoài ý muốn.
