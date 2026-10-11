# Development

**Trạng thái:** `implemented` (phase 1) — mã nguồn tại `go/services/notification-service`; file này mô tả layout thật, môi trường dev và cách chạy.

## 1. Vị trí và layout

`go/services/notification-service`, theo [golang-common-layout](../common/golang/golang-common-layout.md) như Auth/User/Cart:

```
go/services/notification-service/
  cmd/
    server/main.go              -- một binary; chạy các thành phần theo app.role / NOTIFICATION_APP_ROLE
    notifctl/main.go            -- CLI ops/dev: migrate, render preview, publish-example, status, lane pause, partitions
  configs/
    application.yaml            -- override qua env prefix NOTIFICATION_ (viper, giống Auth)
  migrations/                   -- golang-migrate, embed vào binary: 001 schema (09-data-model.md), 002 partition function + 3 tháng, 003 publication, 004 ordering/privacy (ledger sequence, ordering lease, tombstone)
  internal/
    catalog/                    -- bản sao byte-equal của contracts/.../templates/catalog.yaml + 15 JSON Schema template_data
                                   (5 ORDER_* copy nguyên từ contracts; 10 còn lại viết tại đây vì contract nguồn của Auth/Review/Cart
                                   không có schema template_data riêng); test khẳng định hai bản giống nhau
    telemetry/                  -- mọi metric Prometheus (14-observability.md §2) khai báo một chỗ
    api/
      handler/  inbox.go, status.go, ops.go, health.go
      router/   public.go (8080), internal.go (8081)
      middleware/               -- request id, gateway identity, service identity (allowlist X-Service-Identity, D-21), Idempotency-Key, audit
      httpx/                    -- problem+json, decode nghiêm
    domain/
      entity/                   -- Request, Delivery, Preference, Suppression, InboxItem, ProviderEvent, OutboxEvent, LaneState…
      port/                     -- Store/Tx + repository; ContactResolver, EmailSender, Renderer, RateLimiter, FrequencyCap, …
      service/
        decide/                 -- 08-decide.md: 13 bước, thuần, test bằng bảng
        ingest/                 -- ranh giới 1: dedup → tombstone → ledger sequence Order (sequence.go) → Decide → INSERT delivery, một transaction
        deliver/                -- ranh giới 2: một delivery đã claim → ordering lease Order (ordering.go) → resolve → render → send → CAS
        track/                  -- correlate, state ranking, suppression, complaint
        outcome/                -- dựng 4 CloudEvents outcome thành row outbox
        preference/ lifecycle/  -- áp preferences-updated (chặn user có tombstone); tombstone-first user.deleted flow
        inbox/ status/ ops/     -- use case cho ba bề mặt HTTP
    infrastructure/
      cloudevents/              -- parse envelope CloudEvents structured JSON
      kafka/                    -- consumer group runner, producer (DLQ + outbox poller), handlers/ theo nguồn
      postgres/                 -- repo, claim query, reaper, partition, LISTEN/NOTIFY, control cache, ordering_repo (ledger, lease, tombstone)
      redis/                    -- token bucket, frequency cap, contact cache, unread count, daily quota, retry budget
      auth/                     -- client content + notification-contact (bulkhead theo lane, breaker, cache)
      ses/  smtp/               -- hai adapter cùng interface EmailSender (SES production, Mailpit dev/CI)
      sqs/                      -- poller SES events: verify chữ ký SNS, hash email trước INSERT
    templates/                  -- 12-render.md: embed, registry, funcs, 15 thư mục template, testdata golden
    workers/
      deliver/ reaper/ providerwatch/ track/ outbox/ partition/ purge/   -- providerwatch: health probe + auto-pause bulk (D-23)
    common/
      config/ logging/ reqctx/ clock/ errors/ constants/ pii/ breaker/   -- pii/hmac.go: keyring HMAC có version
  deploy/
    localstack/init-ses-events.sh
    debezium/notification-outbox-connector.json
  scripts/go.sh                 -- chạy go build/test trong container golang:1.25 (máy dev không cần cài Go)
  test/
    integration/                -- testcontainers: Postgres, Redis, Kafka, Mailpit, LocalStack; e2e_ordering_test.go (IT-ORD/IT-CTR-07…09/IT-DEL-02), rotation_test.go (IT-DEL-03)
```

Một binary, nhiều vai trò: `NOTIFICATION_APP_ROLE=all` ở dev chạy mọi thứ trong một process; production chạy các deployment tách theo `ROLE=consumer LANE=critical`, `ROLE=deliver LANE=bulk`, `ROLE=track`, `ROLE=api`, `ROLE=jobs` để bulkhead ở mức process ([06-backpressure.md](./06-backpressure.md) §3). Cùng code, khác env — không có hai đường code.

Hai listener HTTP trong một process (giống Auth/User): **8080** public chỉ có inbox API (qua Traefik, `X-User-Id`); **8081** internal có status, ops, `/metrics`, `/livez`, `/readyz` — không bao giờ publish qua Gateway.

## 2. Môi trường dev

### 2.1 Những gì `docker-compose.yml` cung cấp

| Thành phần | Có sẵn (profile mặc định) | Thêm cho Notification (profile `notification`) |
|---|---|---|
| Kafka, Zookeeper | ✓ | |
| Postgres 16 (`wal_level=logical`) | ✓ | DB `notification_db` tạo bởi `init-db.sh` |
| Redis | ✓ | |
| Kafka Connect (Debezium 3.0) | ✓ | Connector `notification-outbox-connector` đăng ký bằng REST (§4) — tuỳ chọn, dev mặc định dùng outbox poller |
| **Mailpit** (SMTP + UI + REST) | | `mailpit` — SMTP `1025`, UI/API `http://localhost:8025` |
| **LocalStack** (SNS, SQS) | | `localstack` — `http://localhost:4566`; script `deploy/localstack/init-ses-events.sh` dựng topic `ses-events` → queue `notification-ses-events` (+ DLQ) |
| **notification-service** | | `notification-service` — host `8091` → 8080 (inbox), `8092` → 8081 (internal); Traefik route `/api/v1/notifications/**` |

Bật:

```bash
docker compose --profile notification up -d mailpit localstack notification-service
```

Các container này nằm sau profile nên `docker compose up` mặc định của các service khác không đổi.

### 2.2 Vì sao Mailpit và LocalStack, không phải SES sandbox

- SES sandbox chỉ gửi tới địa chỉ đã verify và giới hạn 200/ngày — không chạy được test tích hợp lặp lại, và mọi dev đều cần AWS credential.
- Mailpit nhận mọi email qua SMTP, hiển thị HTML/text/headers, và có REST API (`GET /api/v1/messages`, `GET /api/v1/message/{id}`) để test tự động đọc lại email vừa gửi — đúng thứ test tích hợp cần.
- LocalStack cho đường Track: test bơm một event SES mẫu (`Delivery`, `Bounce`, `Complaint`) vào SNS bằng `awslocal sns publish`, poller nhận từ SQS như production. Adapter SES thật chỉ được test bằng contract test riêng khi có credential.

### 2.3 Cổng

| Thành phần | Cổng container | Cổng host | Ghi chú |
|---|---|---|---|
| notification-service public (inbox) | `8080` | `8091` | `8090` đã là dashboard Traefik; OpenAPI inbox `servers` dùng `localhost:8091` |
| notification-service internal (status, ops, metrics, health) | `8081` | `8092` | OpenAPI internal `servers` dùng `notification-service:8081`; không qua Gateway |
| Auth internal API | `8081` | — | `notification-contact` và `content` nằm trên listener internal của Auth, **không phải** public 8080 |
| Mailpit SMTP / UI | `1025` / `8025` | như nhau | |
| LocalStack | `4566` | `4566` | |

## 3. Cấu hình

Env prefix `NOTIFICATION_` (viper `SetEnvPrefix`, giống Auth; dấu `.` của key thành `_`); mọi key trong `configs/application.yaml` đều override được, ví dụ `NOTIFICATION_DATABASE_HOST`, `NOTIFICATION_DELIVER_BREAKER_AUTO_PAUSE_AFTER`. Bảng liệt kê **mọi** nhóm key (nguồn sự thật: `internal/common/config/config.go`, test `TestShippedApplicationYAMLIsValid`):

| Nhóm | Key (`application.yaml`) | Dev mặc định |
|---|---|---|
| Vai trò | `app.env`, `app.role`, `app.lane`, `app.instance_id` | `development`, `all`, rỗng (mọi lane), hostname |
| Listener public | `server.{host,port,read_timeout,write_timeout,idle_timeout,shutdown_timeout,trusted_proxies}` | `0.0.0.0:8080`, `15s`/`15s`/`120s`/`20s`, không proxy tin cậy |
| Listener internal | `internal.{host,port}`, `internal.status_principals`, `internal.ops_principals` | `0.0.0.0:8081`; `order-service,finance-ops`; `notification-ops`. Không có cấu hình TLS: mesh lo mTLS (D-21); allowlist rỗng là lỗi khởi động |
| Postgres | `database.{host,port,name,username,sslmode,conn_max_lifetime,connect_timeout}`, `DB_PASSWORD`, `database.pools.{consumer,deliver_critical,deliver_transactional,deliver_bulk,track,api,jobs}` (bulkhead theo role) | `postgres:5432/notification_db`; pool 10 / 5 / 10 / 15 / 5 / 10 / 3 |
| Redis | `redis.{host,port,db,pool_size,min_idle_conns,dial_timeout,read_timeout,write_timeout,key_prefix}`, `REDIS_PASSWORD` | `redis:6379`, prefix `notification`, timeout 1 s |
| Kafka | `kafka.{brokers,client_id,version,session_timeout,heartbeat_interval,max_processing_time,consume_max_attempts,consume_min_backoff,consume_max_backoff}`; `kafka.sources.<nguồn>.{enabled,topic,group,dlq}` (nguồn: `critical`, `transactional`, `bulk`, `auth`, `review`, `cart`, `preferences`, `user_lifecycle`); `kafka.outcomes.topic` | `kafka:9092`, 30 s/3 s/60 s, 5 lần, 200 ms–5 s; group `notification-{critical,transactional,bulk}`, `notification-auth-critical`, `notification-review-transactional`, `notification-cart-bulk`, `notification-preferences`, `notification-user-lifecycle`; DLQ = topic nguồn + `.dlq` (dùng chung với service khác, [10-interfaces.md](./10-interfaces.md) §9) |
| Email chung | `email.{provider,from_address,from_name,unsubscribe_url,connect_timeout,send_timeout}` | `smtp`, `no-reply@ecommerce.local`, `E-Commerce`, `http://localhost:3000/profile/preferences`, `2s`/`10s` |
| Email SMTP (Mailpit) | `email.smtp.{host,port,username,password,max_conns_per_lane}` | `mailpit:1025` (compose), 5 kết nối/lane |
| Email SES | `email.ses.{region,endpoint,configuration_set_critical_transactional,configuration_set_bulk,max_conns_per_lane,max_24_hour_send,bulk_stop_daily_ratio}` | `ap-southeast-1`, endpoint rỗng, `notification-critical-transactional`/`notification-bulk`, 10, `50000`, `0.8` (bulk dừng khi tổng ngày đạt 80 % `max_24_hour_send`) |
| Track | `track.{enabled,queue_url,aws_endpoint,aws_region,wait_time,batch_size,verify_signature,allowed_cert_hosts,max_clock_skew,worker_batch,worker_poll,unmatched_retry,unmatched_alert_after,unmatched_give_up,soft_bounce_suppression,correlate_provider}` | LocalStack `notification-ses-events`, `20s`, 10, verify `true`, skew `10m`, batch 50 / poll `1s`, retry `30s`, alert sau 3, bỏ sau `24h`, soft bounce `48h`; `correlate_provider`: `ses` (production) \| `mailpit` (dev — bơm event SES mẫu cho mail Mailpit đã gửi; compose đặt `mailpit`) |
| Auth client | `auth.{base_url,timeout,service_identity}`, `auth.rate_per_second.<lane>`, `auth.max_conns.<lane>`, `auth.breaker.{window_size,failure_ratio,open_duration,half_open_successes}` | `http://auth-service:8081`, `2s`, `notification-service`; 0/30/20 req/s (critical không giới hạn); 10/10/5 kết nối; breaker 20 / 0.5 / 30 s / 3 |
| Lane | `lanes.<lane>.{email_workers,in_app_workers,batch_size,email_quota_per_second,breaker_open_retry,max_attempts}` | critical 2/1/5/10/`30s`/5; transactional 2/1/10/20/`5m`/8; bulk 3/1/20/15/`30m`/8. Quota theo [06-backpressure.md](./06-backpressure.md) §4.1 (SES 50/s); số worker và batch là cỡ dev |
| Deliver | `deliver.{lease,heartbeat_interval,idle_min_sleep,idle_max_sleep,reaper_interval,retry_base,retry_max,retry_jitter,rate_limit_wait,retry_budget_ratio,retry_budget_defer,limiter_fallback_replicas,control_poll_interval,use_listen_notify,ordering_lease,ordering_heartbeat_interval,ordering_busy_retry}`, `deliver.breaker.{window_size,failure_ratio,open_duration,half_open_successes}` | `60s`, `20s`, `50ms`, `1s`, `30s`, `30s`, `1h`, `30s`, `5s`, `0.2`, `15m`, `4`, `5s`, `true`, `45s`, `10s`, `2s`; breaker provider 20 / 0.5 / 30 s / 3. `Validate()` đòi `lease` > `email.send_timeout + auth.timeout`, `ordering_lease` > `email.connect_timeout + email.send_timeout + auth.timeout + rate_limit_wait + 5s`, `ordering_heartbeat_interval` ≤ `ordering_lease/3`, `ordering_busy_retry` > 0 |
| Watchdog provider | `deliver.breaker_auto_pause_after`, `deliver.health_probe_interval`, `deliver.health_probe_idle` | `1h` (0 = tắt auto-pause), `10s` (0 = tắt cả watchdog), `15s` — [06-backpressure.md](./06-backpressure.md) §5 |
| Privacy | `privacy.hmac_key` (`NOTIFICATION_PRIVACY_HMAC_KEY`), `privacy.hmac_key_version`, `privacy.previous_hmac_keys` (dạng `1:<key>,2:<key>`) | rỗng → ở `app.env = development` dùng dev key cố định (không phải secret); version `1`; không có key cũ |
| Frontend | `frontend.{base_url,cart_path}` (chỉ để lắp `cart_url`, `review_url` — [07-templates.md](./07-templates.md) §2) | `http://localhost:3000`, `/cart` |
| Bulk | `bulk.{quiet_from,quiet_to,default_timezone,frequency_cap_per_day}` | `22:00`/`08:00`, `Asia/Ho_Chi_Minh`, `2` |
| Outbox | `outbox.{publisher,poll_interval,batch}` (`publisher` = `debezium` \| `poller`) | `application.yaml`: `debezium`, `500ms`, 100; compose dev: `poller` |
| Jobs | `jobs.{partition_interval,partition_ahead_months,purge_interval,purge_batch,metrics_interval}`, `jobs.retention_days.{request,delivery,provider_event,inbox,ingest_dedup,delivery_dedup,provider_event_dedup,outbox,ops_idempotency}` | `1h`, `2`, `1h`, `10000`, `15s`; 90/90/30/90/30/30/14/7/1 |
| Khác | `contact.cache_ttl`, `logging.{level,format}`, `telemetry.metrics_path`, `templates.default_locale`, `extensions.{replay_mode_default_ttl,idempotency_ttl}` | `15m`, `info`/`json`, `/metrics`, `vi`, `6h`, `24h` |

Secret (`DB_PASSWORD`, `REDIS_PASSWORD`, AWS credential) không có prefix, giống quy ước Auth trong compose. Không có secret nào trong `application.yaml`. `config.Validate()` từ chối chạy production nếu `email.provider != ses` hoặc `track.verify_signature = false`; không đòi mTLS vì mesh lo transport (D-21).
`NOTIFICATION_PRIVACY_HMAC_KEY` là secret bắt buộc ngoài development (≥ 32 byte; `Validate()` từ chối khởi động nếu thiếu). Compose dev không cần đặt: `app.env = development` tự dùng dev key cố định. Rotation, không bao giờ đổi nóng một bước (sẽ tách sequence stream và làm mất tombstone):

1. Khai báo key mới ở chế độ chỉ-đọc trên **mọi** process: giữ active = version N, đặt `..._PREVIOUS_HMAC_KEYS` = `N+1:<key mới>`. Chưa có gì được ghi bằng N+1, nhưng từ đây mọi process đã đọc được cả hai version.
2. Đổi active (rolling deploy được): `NOTIFICATION_PRIVACY_HMAC_KEY` = key mới, `..._HMAC_KEY_VERSION` = N+1, `..._PREVIOUS_HMAC_KEYS` = `N:<key cũ>`. Trong lúc rollout, process cũ (active N) và process mới (active N+1) cùng đọc cả hai version nên không tách sequence stream; ledger ghi version active của từng process và gộp dần row cũ khi subject được chạm lại; tombstone luôn ghi dưới mọi version.
3. Chỉ bỏ key cũ khỏi `previous_hmac_keys` khi đã qua replay horizon của Order **và** không còn cần chặn replay cho user bị xoá trước rotation (tombstone của họ chỉ có dưới key cũ — raw user_id không còn để ghi lại dưới key mới).

Bỏ qua bước 1 (đổi active thẳng) là lỗi: trong rolling deploy, process còn keyring cũ không đọc được row ghi bằng key mới.

## 4. Khởi tạo lần đầu

```bash
# 1. hạ tầng
docker compose up -d postgres redis kafka
docker compose --profile notification up -d mailpit localstack

# 2. schema (migrations embed trong binary; notifctl chạy golang-migrate)
go run ./go/services/notification-service/cmd/notifctl migrate up \
  --dsn "postgres://postgres:postgres@localhost:5432/notification_db?sslmode=disable"

# 3. (tuỳ chọn) Debezium outbox connector — chỉ khi NOTIFICATION_OUTBOX_PUBLISHER=debezium
docker compose up -d kafka-connect
curl -X POST -H 'Content-Type: application/json' \
  --data @go/services/notification-service/deploy/debezium/notification-outbox-connector.json \
  http://localhost:8084/connectors

# 4. topic Kafka (dev: auto-create bật; production: tạo trước với số partition ở 01-lanes.md §5)
#    t.notification.critical.v1 (3) · transactional.v1 (12) · bulk.v1 (24) · outcomes.v1 (12) · *.dlq

# 5. chạy (cách A: container trong compose; cách B: go run tại chỗ)
docker compose --profile notification up -d notification-service
NOTIFICATION_APP_ROLE=all go run ./go/services/notification-service/cmd/server
```

Migration `003_publication.up.sql`:

```sql
CREATE PUBLICATION notification_outbox_publication FOR TABLE outbox;
```

(chỉ bảng `outbox`; không publish bảng có PII). Migration `002_partitions.up.sql` định nghĩa các hàm `notification_ensure_partitions`, `notification_partition_future_months`, `notification_expired_partitions` và tạo sẵn partition tháng trước → +2 tháng; `workers/partition` gọi lại các hàm này hằng ngày.

## 5. Vòng lặp phát triển hằng ngày

Máy dev không cần Go: `scripts/go.sh <lệnh go>` chạy trong `golang:1.25` với module cache trên Docker volume (`bash scripts/go.sh test ./internal/...`).

| Việc | Cách |
|---|---|
| Sửa template | Sửa file trong `internal/templates/<ID>/vi/`, chạy `go test ./internal/templates/ -update` để cập nhật golden, xem diff; `notifctl render --template ORDER_CONFIRMED --data internal/templates/testdata/ORDER_CONFIRMED/basic.json --send-to mailpit` rồi mở `http://localhost:8025` |
| Thử một request end-to-end | `notifctl publish-example --file contracts/examples/events/notification-requested.v1.json` (publish lên đúng topic theo lane của template) → xem email ở Mailpit, row ở `notification_delivery`, event ở `t.notification.outcomes.v1` |
| Giả lập bounce | `awslocal sns publish --topic-arn arn:aws:sns:ap-southeast-1:000000000000:ses-events --message file://internal/infrastructure/sqs/testdata/bounce-permanent.json` → kiểm tra `suppression` |
| Xem trạng thái | `notifctl status --subject orders/1000234` (gọi status API ở `8092`; `notifctl` gửi `X-Service-Identity: order-service` cho status và `notification-ops` cho ops, đúng allowlist) |
| Pause/resume | `notifctl lane bulk pause --reason "..."` |
| Test | `go test ./internal/...` (unit + golden + contract); `go test -tags integration ./test/integration/... ./internal/...` (testcontainers — xem [15-verification.md](./15-verification.md)) |

## 6. Điều kiện để lên `implemented`

Theo `contracts/catalog/services.yaml`, trạng thái chỉ được nâng khi:

- Consumer 3 lane + Auth + Review + Cart + preference + user-deleted chạy được với contract test chống lại example trong `contracts/examples/events/`.
- Deliver worker gửi được qua Mailpit và SES (sandbox) với configuration set theo lane.
- Track poller xử lý được `Delivery`/`Bounce`/`Complaint` từ LocalStack, ghi suppression.
- Inbox API đi qua Traefik với `X-User-Id`.
- Outbox → (Debezium hoặc poller) → `t.notification.outcomes.v1` có consumer test đọc được.
- Bộ test bắt buộc ở [15-verification.md](./15-verification.md) §2 pass trong CI.

## 7. Những gì cố ý chưa làm ở dev

- Không có SES thật trong compose; adapter SES chỉ có contract test khi có credential (không chạy mặc định).
- Không có mesh/mTLS ở compose: header `X-Service-Identity` của internal API không được xác thực ở đó. Ở cluster mesh xác thực caller; ứng dụng kiểm allowlist header ở **mọi** môi trường, không phụ thuộc `app.env` (D-21).
- Không có LocalStack SES: SES API gửi được LocalStack giả lập nhưng không sinh event thật; đường gửi dùng Mailpit, đường Track dùng event mẫu bơm vào SNS.
- Debezium là tuỳ chọn ở dev: compose đặt `NOTIFICATION_OUTBOX_PUBLISHER=poller` để không phải đăng ký connector; schema outbox không đổi giữa hai chế độ (D-12).
