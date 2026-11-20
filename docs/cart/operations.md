---
doc_id: cart-operations
service: cart
scope: current
status: implemented
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/configs/application.yaml
  - go/services/cart-service/internal/infrastructure/config/config.go
  - go/services/cart-service/internal/infrastructure/observability/metrics.go
verified_against_commit: null
---

# Cart Service — Operations

## 1. SLO và health

| SLI | Mục tiêu / alert |
|---|---|
| API availability | TBD; Redis lỗi là lỗi Cart toàn phần |
| API write latency | Theo dõi Redis Lua latency riêng |
| Relay lag | Age entry cũ nhất, pending count và Kafka produce failures |
| Marker lag | Deadline ZSET quá hạn chưa xử lý |
| Redis durability | AOF health, replication lag, memory headroom |
| Product degraded read rate | Tỷ lệ `catalog_freshness = DEGRADED` và circuit breaker open |
| User-deleted purge | `cart_user_deleted_outcomes_total{outcome="error"}` tăng liên tục nghĩa là consumer đang kẹt vì Redis; consumer lag của group `cart-auth-consumer` |
| Rate limit | Tỷ lệ HTTP `429 RATE_LIMITED` theo route |

`/health/live` chỉ xác nhận process; `/health/ready` chỉ kiểm tra Redis vì đó là
dependency đồng bộ bắt buộc để phục vụ HTTP. Product và Kafka có metric/alert riêng:
Product lỗi vẫn cho GET suy giảm mềm, Kafka lỗi chỉ làm relay chậm. Không có
PostgreSQL health check.

## 2. Required configuration

Cấu hình nằm ở `configs/application.yaml`; mọi khóa có thể ghi đè bằng biến môi trường
`CART_` + đường dẫn khóa, dấu `.` thành `_`. Vì phần `cart:` của YAML có tên `cart`, các
khóa của nó thành `CART_CART_*` (không phải `CART_*`).

| Khóa YAML | Biến môi trường | Giá trị mặc định / khởi đầu |
|---|---|---|
| `cart.bucket_count` | `CART_CART_BUCKET_COUNT` | `64`, bất biến trong namespace `v2`; khác giá trị biên dịch thì service không khởi động |
| `cart.abandoned_after` | `CART_CART_ABANDONED_AFTER` | `720h` (30 ngày) |
| `cart.active_ttl` | `CART_CART_ACTIVE_TTL` | `888h` (37 ngày); phải lớn hơn `abandoned_after` |
| `cart.abandoned_ttl` | `CART_CART_ABANDONED_TTL` | `168h` (7 ngày) |
| `cart.product_validation_ttl` | `CART_CART_PRODUCT_VALIDATION_TTL` | `45s` (khoảng `30–60s`); cận trên độ cũ catalog khi positive delta |
| `cart.idempotency_ttl` | `CART_CART_IDEMPOTENCY_TTL` | `24h`; áp dụng cho record idempotency của cả POST và DELETE |
| `cart.internal_callers` | `CART_CART_INTERNAL_CALLERS` | `[checkout-service]`; allowlist `X-Service-Identity` cho `/internal/v1/**` |
| `cart.rate_limit.requests_per_second` / `burst` / `idle_timeout` | `CART_CART_RATE_LIMIT_*` | `20` / `40` / `10m`; token bucket theo user trên mỗi pod (0 = tắt) |
| `kafka.topic_cart_events` / `topic_cart_events_dlq` | `CART_KAFKA_TOPIC_CART_EVENTS` / `…_DLQ` | `t.cart.events` / `t.cart.events.dlq` |
| `kafka.topic_order_events` / `consumer_group_order` | `CART_KAFKA_TOPIC_ORDER_EVENTS` / `CART_KAFKA_CONSUMER_GROUP_ORDER` | `t.order.events` / `cart-order-consumer` |
| `kafka.topic_auth_events` / `consumer_group_auth` | `CART_KAFKA_TOPIC_AUTH_EVENTS` / `CART_KAFKA_CONSUMER_GROUP_AUTH` | `t.auth.events` / `cart-auth-consumer` |
| `kafka.sasl_username` / `sasl_password` | `CART_KAFKA_SASL_USERNAME` / `CART_KAFKA_SASL_PASSWORD` | rỗng = PLAINTEXT; compose: principal SCRAM-SHA-512 `cart-service` ([mesh ADR-003](../mesh/decisions/ADR-003-kafka-sasl-scram-and-acls.md)) |
| `kafka.initial_offset` | `CART_KAFKA_INITIAL_OFFSET` | `oldest` cho cả hai consumer; replay vô hại vì cả hai thao tác idempotent |
| `kafka.event_data_schema` | `CART_KAFKA_EVENT_DATA_SCHEMA` | `/contracts/events/cart/v1/schemas/cart-abandoned.schema.json` |
| `worker.<name>.enabled` | `CART_WORKER_<NAME>_ENABLED` | `true` cho `abandoned_marker`, `outbox_relay`, `stream_trimmer`, `order_consumer`, `user_deleted_consumer` |
| `worker.abandoned_marker` | `CART_WORKER_ABANDONED_MARKER_*` | `interval 1m`, `batch_size 200`, `operation_timeout 5s` |
| `worker.outbox_relay` | `CART_WORKER_OUTBOX_RELAY_*` | `idle_interval 2s`, `batch_size 64`, `claim_min_idle 30s`, `max_publish_attempts 3`, `publish_backoff 200ms`, `operation_timeout 10s` |
| `worker.stream_trimmer` | `CART_WORKER_STREAM_TRIMMER_*` | `interval 5m`, `operation_timeout 5s` |
| `product.*` | `CART_PRODUCT_*` | `address`, `get_sku_timeout 200ms`, `get_batch_skus_timeout 200ms`, `cache_size 4096`, `cache_ttl 45s`, breaker `failure_threshold 5` / `half_open_max_calls 1` / `open_timeout 30s` |
| `media.cdn_base_url` | `CART_MEDIA_CDN_BASE_URL` | Bắt buộc; URL tuyệt đối, không `/` cuối; bằng `PDP_MEDIA_CDN_BASE_URL` và `CDN_BASE_URL` của Search. Thiếu hoặc sai thì service không khởi động |
| `media.placeholder_url` | `CART_MEDIA_PLACEHOLDER_URL` | Bắt buộc; URL ảnh cho SKU không có ảnh, bằng `PDP_MEDIA_PLACEHOLDER_URL` |
| `media.require_https` | `CART_MEDIA_REQUIRE_HTTPS` | `true`; chỉ local docker-compose đặt `false` vì MinIO được phát qua gateway bằng `http` |

| Hạ tầng | Yêu cầu |
|---|---|
| Redis eviction | `noeviction` |
| Redis persistence | AOF `appendfsync everysec`, ít nhất một replica, TLS |
| Redis memory reserve | Dành chỗ cho forecast cart active 37 ngày, abandoned 7 ngày và outbox trong Kafka outage 24 giờ ở peak |

## 2.1 Durability, recovery và capacity policy

Cart chấp nhận dữ liệu ephemeral nhưng không được che giấu mức mất dữ liệu. Production
dùng AOF `everysec`: acknowledged write từ lần fsync gần nhất có thể mất khi primary
chết; replica bất đồng bộ cũng không tạo hard RPO guarantee. Mục tiêu vận hành là
khôi phục service trong **15 phút** (RTO), còn data-loss envelope phải được báo trong
incident thay vì rehydrate từ một store khác.

`noeviction` là fail-closed: khi memory không còn headroom, Cart từ chối write thay
vì âm thầm xóa giỏ. Page khi Redis memory vượt 70% hoặc oldest outbox pending age
vượt 15 phút; xử lý khẩn cấp trước 85% memory. Capacity review trước mỗi peak phải
tính cả 24 giờ Kafka outage. Kafka outage không làm pod HTTP unready: relay retry và
outbox tăng; Redis/full outbox là lý do alert và mở rộng capacity, không phải lý do
đổi sang direct publish.

## 3. Failure modes

| Failure | Hành vi |
|---|---|
| Redis unavailable | API read/write lỗi và pod HTTP not-ready; marker/relay dừng; không fallback |
| Redis data loss | Cart bị mất vĩnh viễn; API sau đó trả rỗng; incident báo data-loss envelope theo AOF/replication |
| Product lỗi lúc write | Từ chối SKU mới/refresh stale, cart không đổi |
| Product lỗi lúc GET | `200` degraded với snapshot |
| Kafka lỗi | Stream entry không ACK, relay retry; HTTP vẫn ready, stream/memory alert theo policy |
| Relay crash | `XAUTOCLAIM` lấy pending entry khi khởi động lại |
| Redis lỗi khi xử lý `USER_DELETED` / `order.paid` | Consumer trả error, offset không commit, Kafka giao lại; partition của consumer đó đứng cho tới khi Redis hồi phục |
| `USER_DELETED` bị mất (Auth publish trực tiếp, không outbox) | Giỏ vẫn tự hết hạn theo TTL (tối đa 37 ngày); không có reconciliation riêng của Cart |
| Marker chậm | TTL buffer 7 ngày; entry stale được dọn không phát event |

## 4. Workers và scheduled jobs

### Abandoned marker loop

Một loop quét tuần tự 64 ZSET; batch nhỏ và gọi `mark_abandoned.lua` từng cart. Không dùng distributed lease; deployment hiện tại chạy một active marker owner. Alert khi overdue deadline age vượt ngưỡng.

### Relay loop

Một loop round-robin 64 stream và chỉ dùng Redis call một stream mỗi lượt. Stream rỗng được bỏ qua; stream được tạo lazy. Relay `XAUTOCLAIM` trước rồi đọc mới (chỉ khi bucket hết pending, kiểm bằng `XPENDING`; lỗi kiểm được đếm ở `cart_relay_outcomes_total{outcome="error_pending_count"}`), produce xong mới ACK. Bucket có entry pending sẽ không có entry mới được relay cho đến khi entry đó được claim lại sau `claim_min_idle` và gửi xong. Khi scale, chia bucket ownership tĩnh hoặc qua scheduler.

### Stream trimmer loop

Một loop round-robin 64 stream. Chỉ trim khi group đã tồn tại, mốc delivered tiến và pending entry cũ nhất được giữ lại. Không trim theo `MAXLEN` tại `XADD`.

### Kafka consumers

Hai consumer group độc lập, mỗi group một topic, offset chỉ commit sau khi xử lý xong:

- `cart-order-consumer` trên `t.order.events`: `com.ecommerce.order.paid.v1` xóa SKU đã mua.
- `cart-auth-consumer` trên `t.auth.events`: `com.ecommerce.auth.user-deleted.v1` xóa cả giỏ
  ([ADR-014](./decisions/ADR-014-purge-cart-on-user-deleted.md)). Redelivery vô hại.

Message không parse được hoặc thiếu trường bắt buộc bị log, đếm `skipped_*` và commit để không chặn partition; Cart không
dùng DLQ cho hai consumer này. `t.auth.events.dlq` là DLQ phía consumer của User/Notification, không phải của Cart.

## 5. Observability

Log/traces mang request ID, cart ID dạng privacy-safe và outbox bucket. Không đưa user ID làm metric label. Metric cần có: script result/error, cache-validation hit/miss, marker marked/stale, stream length, pending age, relay retry, Kafka error và trim count.

Tên metric (`/metrics`, Prometheus): `cart_script_results_total`, `cart_product_validation_cache_total`,
`cart_product_calls_total`, `cart_product_breaker_rejections_total`, `cart_marker_outcomes_total`,
`cart_marker_overdue_age_seconds`, `cart_relay_outcomes_total`, `cart_trimmer_outcomes_total`,
`cart_trimmed_entries_total`, `cart_order_paid_outcomes_total`, `cart_order_paid_deleted_items_total`,
`cart_user_deleted_outcomes_total` (outcome `purged`, `no_cart`, `skipped_*`, `error`). Nhãn `bucket` có
đúng 64 giá trị; không có nhãn nào theo user hay cart.

## 6. Deployment và rollback

Deploy script mới trước application gọi nó (script được nhúng vào image; `upsert_item.lua` nhận thêm
`ARGV[13]` là TTL idempotency, và `purge_cart.lua` là script thứ tư). Namespace `v2` không tương thích key layout cũ; rollout cần migration/dual-read plan riêng nếu có dữ liệu `v1` thật. Rollback không được đổi số bucket hoặc xóa stream pending. Không còn database migration cho Cart.
