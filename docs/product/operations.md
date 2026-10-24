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
| `grpc.server.reflection-service-enabled` | `PRODUCT_GRPC_REFLECTION` | `false` (`true` ở profile `dev`); bật tạm trên Compose để dùng `grpcurl` |
| `product.internal.grpc-clients` | (yml) | `cart-service`, `checkout-service`: allowlist `x-service-identity` của `ProductService`; counter `product.grpc.server.identity_rejected{reason}` |
| `warmup.timeout` / `warmup.rounds` | `PRODUCT_WARMUP_TIMEOUT` / `PRODUCT_WARMUP_ROUNDS` | `30s` / `3` (1–10 vòng đọc khi startup) |
| `spring.datasource.url` / `username` / `password` | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:15432/product_service` / `postgres` / `postgres` (chỉ cho dev) |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `spring.kafka.security.protocol` / `spring.kafka.properties.sasl.*` | `SPRING_KAFKA_SECURITY_PROTOCOL` / `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM`, `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG` (+ `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`) | PLAINTEXT; compose: SASL/SCRAM principal `product-service` — `READ` markdown + group, `WRITE` DLQ, `DESCRIBE` topic catalog cho export ([mesh ADR-003](../mesh/decisions/ADR-003-kafka-sasl-scram-and-acls.md)) |
| `admin.required-role` / `user-id-header` / `roles-header` | — | `ADMIN` / `X-User-Id` / `X-User-Roles` |
| `internal.identity-header` | — | `X-Service-Identity` |
| `internal.reindex-clients` | — | `search-service` (hai export) |
| `internal.snapshot-clients` | — | `search-service`, `promotion-service` (snapshot một Product) |
| `internal.buyer-read-clients` | — | `product-detail-bff` |
| `export.cursor-secret` | `EXPORT_CURSOR_SECRET` | không có mặc định trong `application.yml`; profile `dev` và docker-compose đặt giá trị dev. Ngoài profile `dev`/`local`/`test`, `StartupSecretGuard` dừng service nếu secret trống, ngắn hơn 32 ký tự, là giá trị dev đã commit hoặc chứa dấu hiệu placeholder ([mesh ADR-004](../mesh/decisions/ADR-004-secrets-client-ip-network-split-and-internal-signing.md)). docker-compose chạy profile `prod,local` |
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

Readiness của đường catalog fail khi PostgreSQL không truy cập được hoặc warm-up chưa
hoàn tất. Tình trạng consumer Kafka được theo dõi bằng metric/log.

Product chạy warm-up **chỉ đọc, một lần khi startup**, trước khi Spring chuyển sang
`ACCEPTING_TRAFFIC`: chọn một SKU/product `ACTIVE` từ DB, gọi GetSku và GetBatchSkus qua
gRPC loopback, rồi buyer-read qua HTTP loopback (giữ identity filter với một caller trong
allowlist cấu hình). Các vòng đọc làm nóng transport, protobuf, ORM và HTTP serialization;
không tạo dữ liệu mẫu, cache, event hay sửa revision. Catalog rỗng dùng UUID/slug không tồn
tại và chấp nhận 404; khi chưa có catalog thì không thể làm nóng toàn bộ mapping entity
thực tế. Mock/non-network test context không chạy transport warm-up.

`catalogWarmup` health indicator giữ DOWN cho đến khi toàn bộ vòng đọc hoàn tất. Endpoint
`/actuator/health/readiness` gồm `readinessState`, `db`, `catalogWarmup`; Compose và health
check của router Product dùng endpoint này. Liveness giữ riêng, không gọi catalog. Budget
warm-up bao gồm tìm mẫu DB và các request; timeout hoặc lỗi transport/HTTP (trừ 404) làm
startup thất bại và không báo ready. Xem log `Catalog warm-up completed in ... ms` để xác
nhận hoàn tất. Caller đi thẳng vào port vẫn cần tôn trọng readiness; lúc service đang
restart, Cart/PDP tiếp tục dùng chính sách dependency unavailable hiện hành.

Warm-up giảm chi phí lần gọi đầu sau readiness, không thay thế load test p95/p99 hoặc
bảo đảm latency của mọi category/media trong catalog. Không tăng deadline Cart/PDP để
che độ chậm; tiếp tục theo dõi latency sau idle và dưới tải.
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

## Đối chiếu timeout GetBatchSkus với Checkout

Interceptor `ProductRpcTiming` ghi `Product RPC handled` cho `GetBatchSkus` với request_id,
trace_id, grpc_status và duration_ms; metric `product_grpc_server_handling_seconds{method,status}`
đo từ lúc server interceptor nhận call tới lúc handler close call. Không log SKU/body và
không dùng request/trace ID làm metric label. Đối chiếu với `Product RPC completed` phía
Checkout cùng request ID. Client duration còn gồm queue/connect/network/deserialization;
server handling không bao gồm thời gian trước khi call tới server, và status close là
status handler gửi (cancellation trên transport vẫn phải xem metric gRPC server chuẩn).
Không kết luận DB chậm hoặc cold start chỉ bằng chênh lệch hai timer. Contract gRPC và
startup warm-up Product không thay đổi.

## Deploy và rollback

### Nội dung catalog trình diễn local

Bộ lọc được cải thiện tiếp theo bằng [chuyển thông số SKU seed](../frontend/catalog-filter-fix-2026-10-17.md): 28 Product/84 SKU giữ ID/code, giá và status, chỉ thay `variant_attributes` từ Cấu hình gộp sang thuộc tính có nghĩa và thêm mapping tùy chọn qua API admin/ETag. Product revision/timestamp và outbox tăng như cập nhật metadata thông thường; Inventory vẫn sở hữu tồn kho. Script `frontend/scripts/prepare-catalog-filters.mjs` hỗ trợ dry-run, `--apply`, `--verify`, `--restore`; snapshot nằm trong evidence của runbook. Không ghi SQL/ES, không viết lại snapshot Order, không xóa mapping legacy cần cho rollback. Fixture QA được tách ở Search discovery bằng cấu hình ID; không đổi trạng thái Product/SKU QA.

Ngày 17/10/2026 bổ sung script biên tập 8 Product seed: mô tả và media minh họa SVG namespace `showcase-v1/` trong MinIO. Script sử dụng API admin/ETag và CDC bình thường; không đổi schema, giá, SKU, tồn kho hoặc xóa fixture. Xem [hướng dẫn chạy lại, snapshot và hoàn nguyên](../frontend/storefront-ux-fix-2026-10-17.md#chuẩn-bị-hoặc-chạy-lại-nội-dung-local). Chỉ dùng cho môi trường local; ảnh được ghi rõ là minh họa, cần thay bằng nội dung SKU đã xác nhận trước triển khai thương mại.

Migration phải additive và tương thích rollback trong ít nhất một chu kỳ deploy. Rollout
contract theo thứ tự: consumer chấp nhận schema mới → producer phát song song → chuyển
consumer → gỡ schema cũ sau thời gian quan sát. Không rollback migration destructive;
kiểm tra Debezium connector và offset trước/sau rollback để tránh replay ngoài ý muốn.

## Request ID, trace ID và deadline của caller

Theo [contracts ADR-002](../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md) và [ADR-003](../../contracts/decisions/ADR-003-deadline-propagation.md), thêm [ADR-021](./decisions/ADR-021-request-correlation-and-caller-deadline.md). Log trong
phạm vi một request có `request_id` và `trace_id` (MDC do `RequestCorrelationFilter` đặt, hiển thị bằng `logging.pattern.console`); log của
tác vụ nền không có hai trường này. `X-Request-Id` sai hoặc thiếu bị thay, không bao giờ `400`, và được
trả lại trong response.

Counter `http.server.caller_deadline_exceeded{route}` đếm request kết thúc sau khi caller đã thôi chờ
(`X-Request-Timeout-Ms`), kèm một dòng WARN `request outlived the caller deadline`. Số này tăng đều nghĩa là
Product trả lời chậm hơn timeout của caller: xem p99 của route trước, rồi mới xét timeout của caller. Ngưỡng
cảnh báo đặt theo số đo p99 từ load test, không đặt trước. Log gRPC `GetBatchSkus` ghi `request_id` và `trace_id` từ metadata `x-request-id` và `traceparent` (định dạng ADR-002, ID có `.` và `:` hợp lệ).
