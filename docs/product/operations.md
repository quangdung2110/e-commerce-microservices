---
doc_id: product-operations
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Operations

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
| PDP buyer-read | Product Detail BFF | Synchronous internal query | Latency kỳ vọng p99 ≤ 250 ms vì BFF cắt ở 400 ms ([Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md)); alert latency/error của endpoint; stock freshness thuộc Inventory, không phải Product hay BFF |

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
