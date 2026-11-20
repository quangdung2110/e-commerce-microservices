---
doc_id: user-operations
service: user
scope: current
status: implemented
owners:
  - User Service team
evidence:
  - go/services/user-service/configs/application.yaml
  - go/services/user-service/internal/common/config/config.go
  - go/services/user-service/internal/workers
  - go/services/user-service/internal/infrastructure/kafka/consumer.go
verified_against_commit: null
---

# User Service — Vận hành

> Cấu hình, worker và hành vi lỗi dưới đây khớp code. Mục tiêu SLO, ngưỡng cảnh báo và
> metric Prometheus **chưa** được định nghĩa hay export (service chỉ có structured log):
> không xem chúng là cam kết production cho đến khi owner chốt giá trị đo.

## 1. SLO, health và cấu hình

Runtime local hiện có trong [development.md](./development.md): Go service, root
`user-migrate`, public listener 8082 và internal 8083. `/health/live` dùng GET;
readiness/startup kiểm tra PostgreSQL required, Redis optional. Docker probe dùng
GET readiness; Kafka consumer healthy phải kiểm tra riêng qua lag/bootstrap, không
suy ra từ probe. SLO/metric/production acceptance bên dưới vẫn cần bằng chứng đo.

Cần định nghĩa SLI cho HTTP availability/latency/error rate, Postgres pool,
Redis fallback/cache error, Kafka producer failure, event consumer lag/DLQ (cả hai topic),
age/count retention record quá hạn và projection reconciliation duration/failure.
SLO, error budget và owner chưa được chốt.

### Cấu hình

Mọi key của `configs/application.yaml` override được bằng biến `USER_` (dấu chấm thành gạch
dưới, ví dụ `USER_APP_PORT`); danh sách phân tách bằng dấu phẩy. Secret chỉ lấy từ
`DB_PASSWORD`, `REDIS_PASSWORD`. Đổi env cần recreate process. `Validate()` chặn khởi động
khi cấu hình vi phạm invariant.

| Nhóm | Key (mặc định) | Ghi chú |
|---|---|---|
| Listener | `app.port` (8082), `internal.port` (8083), `app.api_enabled`, `internal.enabled` | Hai port phải khác nhau; internal không đi qua Gateway |
| Caller internal | `internal.allowed_callers` (`checkout-service`) | Env `USER_INTERNAL_ALLOWED_CALLERS`; rỗng khi internal bật là lỗi khởi động. Không còn cấu hình TLS ở app ([ADR-008](./decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md)) |
| Rate limit | `security.rate_limit.read_per_identity` (120/phút), `write_per_identity` (30/phút), `internal_per_user` (120/phút theo cặp caller + `user_id`) | Fail closed: Redis lỗi thì request bị từ chối |
| Cache | `cache.address_ttl` (12h), `cache.address_empty_ttl` (15m, không được lớn hơn TTL thường) | |
| Kafka | `kafka.auth_topic` (`t.auth.events`), `kafka.auth_dlq_topic` (`t.auth.events.dlq`), `kafka.notification_topic` (`t.notification.outcomes.v1`), `kafka.notification_dlq_topic` (`t.notification.outcomes.v1.dlq`), `kafka.user_topic` (`t.user.events`), `kafka.consumer_group` (`user-service`) | Hai topic đầu vào bắt buộc khi `kafka.consumer_enabled` |
| Retry consumer | `kafka.consume_max_attempts` (10), `consume_min_backoff` (1s), `consume_max_backoff` (5m) | Retry tại chỗ, giữ ordering theo user |
| Publish | `kafka.publish_max_attempts` (3), `publish_backoff` (200ms), `publish_timeout` (5s) | Direct publish, retry cạn thì để reconciliation |
| Workers | `workers.retention_purge` (1h, batch 100, timeout 10m), `workers.projection_reconciliation` (24h, batch 500, `run_at_hour_utc` 18, `batch_gap` 200ms, timeout 2h), `workers.lock_ttl` (5m) | Lock Redis giữ mỗi worker singleton toàn service |
| Database | `database.max_open_conns` (25), `min_conns` (5), `statement_timeout` (5s) | |

Liveness chỉ xác nhận process còn chạy. Readiness phải kiểm tra các dependency
cần thiết cho API ghi (PostgreSQL); Kafka consumer readiness và Redis cache nên
được báo riêng để tránh loại API healthy chỉ vì cache lỗi. Cache unavailable phải
degrade sang DB; PostgreSQL unavailable không ready. Bảng cấu hình ở trên liệt kê
env var, default và ràng buộc khởi động; secret chỉ đi qua `DB_PASSWORD` và
`REDIS_PASSWORD`.

## 2. Quan sát hệ thống và cảnh báo

Metrics tối thiểu: HTTP request/error/latency theo route/status; DB pool/timeout;
address cache hit/miss/fallback/invalidation failure; Kafka consume/publish result,
lag, retry và DLQ; event duplicate; anonymization/hard-delete result, oldest due
record, reconciliation scanned/published/failed/duration. Log cấu trúc cần
correlation/request ID, event ID và hashed/redacted user reference; không log
profile, phone hoặc full address. Trace cần bao phủ HTTP command, DB transaction,
Redis, Kafka consume/publish và worker batch.

| Cảnh báo | Điều kiện cần chốt bằng số đo | Mức độ | Runbook |
|---|---|---|---|
| Event consumer lag (`t.auth.events`, `t.notification.outcomes.v1`) | Lag/oldest event vượt SLO | Cao | Consumer lag |
| Internal API `401`/`403`/`429` tăng | Tỉ lệ vượt ngưỡng trong cửa sổ | Trung bình | Internal API |
| Profile/preference event publish failure | Retry cạn hoặc reconciliation backlog | Cao | Event publishing |
| Cache invalidation failure | Error rate vượt ngưỡng trong cửa sổ | Trung bình | Cache degradation |
| DB pool exhaustion | Pool saturation/timeout vượt ngưỡng | Cao | Database exhaustion |
| GDPR purge overdue | Oldest due retention record vượt SLO | Cao | Compliance worker |
| Projection reconciliation failed | Job không hoàn tất hoặc publish failure cạn | Cao | Event publishing |
| DLQ non-empty | Bất kỳ message chưa triage | Cao | DLQ handling |

## 3. Hành vi khi lỗi và suy giảm

| Lỗi | Hành vi dự kiến |
|---|---|
| PostgreSQL unavailable | Reject read/write phụ thuộc DB; consumer không ack; worker retry theo policy |
| Redis unavailable/corrupt | Address read fallback DB; mutation commit vẫn thành công, invalidation lỗi được ghi metric/alert |
| Kafka producer unavailable | DB command profile/preference vẫn commit theo direct-publish design; retry/reconciliation phải phát hiện projection/event gap |
| Kafka consumer unavailable/lag | API còn hoạt động; profile bootstrap/deletion và việc tắt marketing sau complaint bị trì hoãn (Notification đã chặn marketing ở bản local), lag/DLQ alert |
| Redis unavailable cho rate limiter | Public và internal API từ chối request (limiter fail closed) trong lúc Redis lỗi; cache address riêng thì degrade sang DB |
| Reconciliation worker unavailable | Projection có thể stale lâu hơn một ngày; alert và chạy lại job, không phát sự kiện suy đoán từ log |
| Mixed-version deployment | Chỉ rollout field/event backward compatible; consumer bỏ qua optional unknown field |

## 4. Scaling, triển khai và rollback

API scale theo RPS, latency và PostgreSQL pool. Consumer scale theo Kafka
partition/lag, không phá ordering cùng `user_id`. Compliance worker không chạy
chồng cùng một retention row nhờ row claim/lock; reconciliation worker chỉ có một
run active toàn service để tránh full sweep trùng. Deployment cần migration
backward-compatible trước application; event field mới optional trước; rollback
không được quay về version không đọc được data/event mới. Chưa có load test,
partition count hoặc capacity baseline được xác minh.

## 5. Worker và tác vụ định kỳ

| Tác vụ | Trigger | Concurrency/idempotency | Quan sát hệ thống |
|---|---|---|---|
| `user-event-consumer` (group `user-service`) | Kafka `t.auth.events` (`USER_VERIFIED_SUCCESS`, `USER_DELETED`) và `t.notification.outcomes.v1` (`complaint-received`) liên tục | Keyed `user_id`; PK/unique business key hoặc UPDATE có điều kiện; ACK chỉ sau commit; retry tối đa 10 lần theo exponential backoff 1s→5m, sau đó DLQ của topic nguồn: `t.auth.events.dlq` hoặc `t.notification.outcomes.v1.dlq` | Log `processed ...` (duplicate/skipped_deleted/no_change), retry, DLQ; lag và metric chưa export |
| `user-retention-purge` | Mỗi giờ theo UTC | Batch tối đa 100; `FOR UPDATE SKIP LOCKED`; deadline check và transaction từng record | Due/processed/failed/oldest due, lock contention |
| `user-projection-reconciliation` | Mỗi ngày ngoài giờ cao điểm | Một run active; full sweep active profile/preferences theo batch; event corrective có ID mới, `updated_at` giữ nguyên | Scanned/published/failed/duration, Kafka throttle |
| DLQ redrive | Thao tác vận hành có phê duyệt | Không tự động redrive; sửa nguyên nhân, đánh giá poison message rồi replay có kiểm soát | DLQ age/count, replay result |

## 6. Runbook bắt buộc

- **Consumer lag/DLQ:** kiểm tra broker health, partition assignment, oldest event
  và error class; không bỏ message hoặc auto-redrive. User dùng hai DLQ theo topic nguồn.
  `t.auth.events.dlq` còn nhận message lỗi của Notification (consumer group riêng của họ
  cũng đẩy vào đó), nên khi redrive chỉ replay các message có header
  `x-dead-letter-service=user-service`. `t.notification.outcomes.v1.dlq` chỉ chứa complaint
  mà User không xử lý được. Sau khi sửa nguyên nhân, đánh giá poison message và replay có
  phê duyệt: mọi command đều idempotent nên replay an toàn. Xác nhận lag/DLQ giảm và
  profile/deletion/marketing được reconcile.
- **Internal API:** `401` nghĩa là caller thiếu `X-Service-Identity` (lỗi contract phía
  caller); `403` là service ngoài `internal.allowed_callers` (kiểm tra cấu hình và
  AuthorizationPolicy của mesh); `429` là một cặp (caller, user) vượt
  `internal_per_user`. Không hạ allowlist hoặc tắt limiter để "thông" lỗi.
- **Marketing lệch giữa User và Notification:** User là nguồn sự thật. Nếu một user báo
  spam mà `marketing_enabled` ở User vẫn `true`, kiểm tra DLQ `t.notification.outcomes.v1.dlq`
  và lag của group `user-service` trên topic đó; sau khi consumer bắt kịp, event
  `USER_PREFERENCES_UPDATED` được phát lại và reconciliation hằng ngày sửa phần còn lại.
- **Cache degradation/corruption:** xác nhận Redis error, bypass hoặc xóa key
  phạm vi một user khi an toàn, không flush namespace rộng; kiểm tra fallback DB
  và invalidation metric.
- **Database exhaustion:** dừng scale mù quáng, kiểm tra pool/query/lock, giảm
  traffic khi cần, khôi phục pool rồi xác nhận write và consumer retry thành công.
- **Direct event publishing failure:** xác định metric publish failure; để
  `user-projection-reconciliation` phát current snapshot của profile/preferences
  trong sweep kế tiếp hoặc chạy lại job có phê duyệt. Không tự phát event suy đoán
  từ log PII và không phát lại `USER_DELETED`.
- **Compliance worker backlog:** kiểm tra clock/deadline, lock và DB errors; chạy
  batch có giới hạn sau khi phê duyệt, xác nhận không delete trước hạn.
