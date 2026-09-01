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
của markdown consumer, markdown safety-check age/failure, và thời gian retention job.
Metric không dùng `product_id`, `sku_id` hay rule ID làm label.

Search cần tự theo dõi stock-event lag của Inventory; đây không phải consumer của Product.

## Health và degraded behavior

Readiness fail khi PostgreSQL không truy cập được. Consumer readiness phản ánh Kafka.
Kafka/Debezium lỗi không làm mất catalog write đã commit vì event nằm trong outbox, nhưng
làm Search/Promotion trễ và có nguy cơ WAL tăng; alert connector/slot lag là critical.

Khi Inventory không sẵn sàng, Product vẫn phục vụ catalog read/write; Checkout phải
trả lỗi/không hoàn tất reservation theo chính sách của Inventory. Product không có cache
hay local stock fallback.

## Workers và jobs

| Process | Trigger | An toàn |
|---|---|---|
| Markdown consumer | Kafka Promotion snapshots | Lock Product, guard `source_revision`, retry transient |
| Markdown safety check | Schedule | Quét projection quá hạn, transaction idempotent |
| Debezium connector | Liên tục đọc WAL | At-least-once; theo dõi slot lag |
| Outbox retention | Schedule | Xóa theo lô sau retention đã xác nhận |


## Alerts và runbooks

Alert tối thiểu: PostgreSQL unavailable/pool exhausted, lock timeout tăng, consumer lag,
DLQ có message, Debezium connector fail hoặc slot lag cao, outbox tăng bất thường,
markdown safety check không chạy và retention job fail. Runbook phải có chẩn đoán chỉ
đọc, khắc phục, xác nhận phục hồi và owner escalate.

## Deploy và rollback

Migration phải additive và tương thích rollback trong ít nhất một chu kỳ deploy. Rollout
contract theo thứ tự: consumer chấp nhận schema mới → producer phát song song → chuyển
consumer → gỡ schema cũ sau thời gian quan sát. Không rollback migration destructive;
kiểm tra Debezium connector và offset trước/sau rollback để tránh replay ngoài ý muốn.
