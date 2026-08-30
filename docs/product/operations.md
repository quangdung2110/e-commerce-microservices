---
doc_id: product-operations
service: product
scope: current
status: proposed
owners:
  - Product Service team
evidence: []
verified_against_commit: null
---

# Product Service — Operations

> File này trả lời: “Service hỏng thì phát hiện và xử lý thế nào?”

## 1. SLO và SLI

Các SLI cần đo. Giá trị mục tiêu và error budget là `TBD` cho tới khi có số liệu
production; không khẳng định mức hiệu năng khi chưa đo được.

| SLI | Cách đo | Vì sao quan trọng |
|---|---|---|
| Latency point-read gRPC (p95, p99) | Từ adapter gRPC, tách riêng `GetSku` và `GetBatchSkus` | Nằm trên đường mua hàng của Cart/Checkout |
| Cache hit ratio của `product:sku:*` | Hit / (hit + miss) | Miss cao đẩy tải xuống PostgreSQL |
| Availability và error rate của admin API | Theo endpoint và theo mã lỗi | Ảnh hưởng vận hành nội dung |
| Consumer lag từng group | `product-event-handler-group`, `product-markdown-group`, `product-inventory-fast-sync-group` | Lag = dữ liệu hiển thị cũ |
| Độ sâu và tuổi của `product:inventory:pending` | `ZCARD` và score nhỏ nhất so với hiện tại | Phản ánh sức khỏe Phase 2 |
| Độ trễ event → DB (Phase 2) | Từ `event_timestamp` tới thời điểm commit | Quyết định độ trễ auto-hide |
| Độ trễ outbox → Kafka | Từ `created_at` tới thời điểm forward | Sức khỏe của CDC |
| Replication slot lag | Từ PostgreSQL | Rủi ro đầy đĩa |
| Thời gian hoàn tất từng job định kỳ | Theo job | Phát hiện job treo |

## 2. Health endpoints

Liveness, readiness và startup probe cần được định nghĩa trong runtime contract.
Nguyên tắc: readiness phải fail khi PostgreSQL không truy cập được (service không thể
phục vụ đúng bất kỳ chức năng nào), nhưng **không** nên fail chỉ vì Redis lỗi — point-read
vẫn phục vụ được từ DB ở chế độ suy giảm. Consumer process nên có readiness riêng phản
ánh khả năng kết nối Kafka. Cấu hình cụ thể hiện là `TBD`.

## 3. Configuration

| Variable/Property | Purpose | Required | Default | Secret | Restart required |
|---|---|---|---|---|---|
| Kết nối PostgreSQL (host, database, user, pool size) | Write store | Yes | `TBD` | Có, phần credential | Yes |
| Kết nối Redis | Cache và hàng đợi | Yes | `TBD` | Có, phần credential | Yes |
| Kafka bootstrap và `group.id` từng consumer | Event | Yes | Xem tên group ở [Interfaces](./interfaces.md#5-events-tiêu-thụ) | No | Yes |
| TTL cache point-read | Vòng đời `product:sku:*` | Yes | 3600 s | No | No |
| TTL ZSET pending | Bảo vệ hàng đợi rác | Yes | 86400 s | No | No |
| Chu kỳ Phase 2 | Tần suất drain | Yes | 500–1000 ms | No | No |
| Kích thước lô Phase 2 | Item mỗi lượt drain | Yes | 100–500 | No | No |
| Số thread consumer Phase 1 | Song song hóa | Yes | 8–16 | No | Yes |
| `lock_timeout` cho worker | Chống chờ lock vô hạn | Yes | `TBD` | No | No |
| Chu kỳ markdown pending scheduler | Drain job chờ | Yes | 1–5 phút | No | No |
| Lịch markdown expiry cronjob | Self-healing giá | Yes | 1 lần/ngày, giờ thấp điểm | No | No |
| TTL lock cronjob | Chạy singleton | Yes | ~60 s, có gia hạn | No | No |
| Cửa sổ retention outbox | Giữ log CDC | Yes | ~30 ngày | No | No |
| Giới hạn `sku_ids` mỗi batch gRPC | Bảo vệ provider | Yes | 100 | No | No |

Các giá trị `TBD` phải được lấy từ cấu hình runtime thật, không suy ra từ thiết kế.
Không ghi giá trị secret vào tài liệu.

## 4. Observability

Metric bắt buộc: latency và error rate theo endpoint/RPC; cache hit/miss và số lần
rebuild; mã trả về của Lua script Phase 1 (`1`/`0`/`-1`); độ sâu và tuổi ZSET pending;
số product xử lý mỗi lượt Phase 2; số lần FSM auto-hide/auto-reactivate; số SKU áp/gỡ
markdown; số lần lock timeout và requeue; consumer lag; độ trễ và trạng thái CDC.

Label phải có cardinality an toàn: dùng `event_type`, `change_source`, `consumer_group`,
kết quả xử lý. **Không** dùng `sku_id`, `product_id` hay `rule_id` làm label metric.

Log có cấu trúc phải mang `correlation_id`, `event_id`, `product_id`, `sku_id` và
`event_type` để truy vết một thay đổi xuyên suốt API → outbox → Kafka → consumer →
cache. Trace nên bao phủ: request admin (bao gồm transaction và refresh cache in-band),
point-read gRPC (bao gồm nhánh cache miss), và mỗi lượt xử lý của từng worker.

## 5. Alerts

| Alert | Condition | Severity | Owner | Runbook |
|---|---|---|---|---|
| Consumer lag cao | Lag của một group vượt ngưỡng trong N phút | High | Product on-call | Runbook consumer lag |
| DLQ có message | Số message DLQ > 0 | High | Product on-call | Runbook DLQ |
| Pending queue tồn đọng | `ZCARD` hoặc tuổi item vượt ngưỡng | High | Product on-call | Runbook Phase 2 |
| Replication slot lag | Slot lag vượt ngưỡng hoặc connector không chạy | Critical | Product on-call + hạ tầng | Runbook CDC |
| Cache hit ratio sụt | Hit ratio dưới ngưỡng kéo dài | Medium | Product on-call | Runbook cache |
| Lock timeout tăng đột biến | Tỉ lệ lock timeout vượt ngưỡng | Medium | Product on-call | Runbook lock contention |
| Markdown expiry job thất bại | Job không hoàn tất hoặc không chạy | Medium | Product on-call | Runbook markdown |
| DB pool cạn | Thời gian chờ pool vượt ngưỡng | High | Product on-call | Runbook database |

Ngưỡng cụ thể là `TBD`.

## 6. Failure modes

| Sự cố | Hành vi | Hệ quả |
|---|---|---|
| PostgreSQL không sẵn sàng | Mọi ghi thất bại; point-read chỉ phục vụ được phần còn trong cache | Ngừng quản trị; đọc suy giảm dần theo TTL |
| Connection pool cạn | Admin API và worker tranh nhau connection | Latency tăng, lock timeout nhiều hơn |
| Redis không sẵn sàng | Point-read rơi về DB; Phase 1 không ack và Kafka redeliver; marker markdown và lock không hoạt động | Tải DB tăng mạnh; tồn kho hiển thị đứng yên; cronjob mất bảo vệ singleton |
| Redis mất dữ liệu (flush, eviction) | Cache miss hàng loạt | Nguy cơ stampede; lock fetch và PER là cơ chế giảm sốc |
| Kafka không sẵn sàng | Consumer dừng; outbox tiếp tục tích lũy trong DB | Dữ liệu vẫn đúng ở DB, downstream trễ |
| Kafka Connect / Debezium dừng | Không event nào được phát; replication slot giữ WAL | **Nguy cơ đầy đĩa PostgreSQL** — đây là failure mode nghiêm trọng nhất |
| Inventory Service phát event sai thứ tự | Guard theo timestamp chặn bản cũ | Không hỏng dữ liệu |
| Promotion Service không phát `MARKDOWN_ENDED` | Giá khuyến mãi kẹt | Cronjob expiry dọn trong vòng một ngày |
| Search Service lag | Read model cũ | Không ảnh hưởng luồng mua hàng vì Cart đọc point-read |
| Deploy lẫn phiên bản | Consumer cũ và mới cùng chạy | An toàn khi chỉ thay đổi additive |

## 7. Degraded behavior

Khi Redis lỗi: point-read vẫn phục vụ từ PostgreSQL với latency cao hơn; đồng bộ tồn
kho tạm dừng và `display_quantity` trở nên cũ; nên hạn chế thao tác quản trị diện rộng.

Khi Kafka lỗi: quản trị sản phẩm vẫn hoạt động bình thường vì event nằm lại ở outbox;
Search và Notification sẽ bắt kịp sau. Không được chuyển sang publish trực tiếp để "cứu"
tình huống này — sẽ phá vỡ thứ tự và tính atomic.

Khi PostgreSQL lỗi: từ chối toàn bộ thao tác ghi thay vì ghi vào Redis; Redis không bao
giờ được dùng như write store thay thế.

Dữ liệu được phép cũ: `display_quantity`, document Elasticsearch, và thời điểm
auto-hide/auto-reactivate. Dữ liệu không được phép sai: giá, trạng thái sản phẩm và sự
tồn tại của SKU.

## 8. Scaling và capacity

API scale theo RPS và connection pool. Consumer scale tối đa tới số partition của
topic tương ứng; vượt quá là lãng phí. Phase 1 dùng 8–16 thread trong một group. Phase
2, markdown pending scheduler và các cronjob là singleton hoặc phải dùng distributed
lock nên không scale tự do.

Bottleneck đã biết: lock trên row product khi nhiều luồng cùng chạm một sản phẩm hot;
connection pool khi worker và admin API chạy chung; số partition của
`t.inventory.events` giới hạn thông lượng Phase 1; và tốc độ ghi WAL khi có batch lớn.

Chưa có kết quả load test. Giả định năng lực hiện tại đều là `TBD`.

## 9. Deployment và rollback

Thứ tự triển khai: chạy migration trước (migration phải backward compatible với
phiên bản đang chạy), rồi deploy API và worker, sau đó mới cập nhật cấu hình Debezium
connector nếu có thay đổi.

Ràng buộc: thay đổi schema phải additive trong ít nhất một chu kỳ deploy để rollback
không làm hỏng phiên bản cũ; thay đổi cấu trúc event phải additive vì consumer có thể
đang ở phiên bản cũ; đổi tên cột được dùng bởi Debezium (đặc biệt `partition_key`) là
breaking change và cần kế hoạch riêng.

Rollback: quay lại phiên bản ứng dụng trước là an toàn nếu migration còn tương thích;
không rollback migration đã xóa dữ liệu. Khi rollback, kiểm tra lại connector và offset
để tránh phát lại event hàng loạt.

## 10. Runbooks

Runbook bắt buộc, mỗi cái cần có triệu chứng, bước chẩn đoán chỉ đọc, cách khắc phục,
cách xác nhận đã phục hồi và người escalate:

1. Consumer lag cao ở một trong ba group.
2. DLQ có message (phân loại event lạ, payload sai, lỗi hạ tầng).
3. `product:inventory:pending` tồn đọng hoặc item quá cũ.
4. Debezium connector dừng và replication slot phình.
5. Cache hit ratio sụt hoặc nghi ngờ cache lệch với DB.
6. Lock contention và deadlock trên bảng product/SKU.
7. Giá markdown kẹt sau khi rule đã kết thúc.
8. PostgreSQL connection pool cạn.
9. Search yêu cầu full reindex qua internal export API.

Chưa runbook nào được viết; đây là gap vận hành lớn nhất hiện tại.

## 11. Workers và scheduled jobs

| Worker/Job | Trigger | Concurrency và an toàn | Idempotency | Telemetry bắt buộc |
|---|---|---|---|---|
| Debezium CDC connector | Liên tục, đọc WAL | Một connector cho một slot | At-least-once theo `event_id` | Trạng thái connector, slot lag, độ trễ forward |
| Event Handler Worker | Kafka `t.product.events`, `t.category.events` | Theo partition; dedupe theo `product_id` trong poll-batch | Guard theo mốc thời gian; cascade `HIDDEN` là idempotent | Lag, số cache refresh, số skip do stale, lỗi theo nhánh |
| Markdown Worker | Kafka `t.promotion.markdown.events` | Transaction theo từng product, lock product với `lock_timeout` | Unique `(rule_id, sku_id)`; `ENDED` no-op khi không có mapping | Số SKU áp/gỡ, lock timeout, thời gian mỗi product |
| Inventory Phase 1 | Kafka `t.inventory.events` | 8–16 thread; atomic bằng Lua | Lua CAS theo `last_inventory_sync_at` | Mã trả về script, lag, độ sâu ZSET |
| Inventory Phase 2 | Scheduler 500 ms – 1 s | Một transaction cho mỗi product; lock product | Conditional update; chỉ ack sau commit | Độ trễ event → DB, số requeue, số lần FSM trigger |
| Markdown pending scheduler | Scheduler 1–5 phút | Drain ZSET, `ZREM` sau khi xử lý | Job còn trong ZSET nếu chưa xong | Số job đến hạn, số job trễ hạn |
| Markdown expiry cronjob | Hằng ngày, giờ thấp điểm | Distributed lock; lô 100 record; `ORDER BY product_id ASC` | Reset là idempotent ở mức dữ liệu | Số SKU được dọn, thời gian chạy, kết quả lock |
| Outbox retention trimmer | Hằng tuần, giờ thấp điểm | Xóa theo lô 10.000 theo `created_at` | Xóa là idempotent | Số record xóa, kích thước bảng còn lại |

Cách chạy lại thủ công an toàn cho từng job (đặc biệt là markdown expiry và outbox
trimmer) cần được viết thành runbook trước khi coi phần vận hành là hoàn chỉnh.
