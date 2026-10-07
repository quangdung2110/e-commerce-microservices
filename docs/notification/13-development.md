# Development

**Trạng thái:** `proposed` — mã nguồn phase 1 đã có tại `go/services/notification-service`; file này mô tả layout thật, môi trường dev và cách chạy.

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
      middleware/               -- request id, gateway identity, service identity (mTLS / X-Service-Identity ở dev), Idempotency-Key, audit
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
      deliver/ reaper/ track/ outbox/ partition/ purge/
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

Env prefix `NOTIFICATION_` (viper `SetEnvPrefix`, giống Auth); mọi key trong `configs/application.yaml` đều override được, ví dụ `NOTIFICATION_DATABASE_HOST`. Nhóm chính:

| Nhóm | Ví dụ key | Dev mặc định |
|---|---|---|
| Vai trò | `NOTIFICATION_APP_ENV`, `NOTIFICATION_APP_ROLE`, `NOTIFICATION_APP_LANE` | `development`, `all`, rỗng (mọi lane) |
| Listener | `NOTIFICATION_SERVER_PORT`, `NOTIFICATION_INTERNAL_PORT`, `..._INTERNAL_TLS_*`, `..._INTERNAL_STATUS_PRINCIPALS`, `..._INTERNAL_OPS_PRINCIPALS` | `8080`, `8081`, không TLS, `order-service,finance-ops`, `notification-ops` |
| Postgres | `NOTIFICATION_DATABASE_HOST/PORT/NAME/USERNAME`, `DB_PASSWORD`, `NOTIFICATION_DATABASE_POOLS_*` (bulkhead theo role) | `postgres:5432/notification_db`; pool consumer 10, deliver 5/10/15, track 5, api 10, jobs 3 |
| Redis | `NOTIFICATION_REDIS_HOST/PORT`, `REDIS_PASSWORD` | `redis:6379` |
| Kafka | `NOTIFICATION_KAFKA_BROKERS`; `kafka.sources.<nguồn>.{topic,group,dlq}` | `kafka:9092`; group `notification-{critical,transactional,bulk}`, `notification-auth-critical`, `notification-review-transactional`, `notification-cart-bulk`, `notification-preferences`, `notification-user-lifecycle` |
| Email provider | `NOTIFICATION_EMAIL_PROVIDER` = `smtp` \| `ses`; `NOTIFICATION_EMAIL_SMTP_HOST/PORT`; `NOTIFICATION_EMAIL_SES_REGION`, `..._SES_CONFIGURATION_SET_CRITICAL_TRANSACTIONAL`, `..._SES_CONFIGURATION_SET_BULK`; `NOTIFICATION_EMAIL_UNSUBSCRIBE_URL` | `smtp` → `mailpit:1025` |
| Track | `NOTIFICATION_TRACK_QUEUE_URL`, `NOTIFICATION_TRACK_AWS_ENDPOINT` (LocalStack), `NOTIFICATION_TRACK_VERIFY_SIGNATURE`, `..._TRACK_ALLOWED_CERT_HOSTS`, `..._TRACK_CORRELATE_PROVIDER` (`ses` | `mailpit` — dev bơm event SES mẫu cho mail Mailpit đã gửi) | `http://localstack:4566/000000000000/notification-ses-events`, compose: `mailpit` |
| Auth | `NOTIFICATION_AUTH_BASE_URL`, timeout, `auth.rate_per_second.<lane>`, `auth.max_conns.<lane>`, breaker | `http://auth-service:8081`, `2s` |
| Lane | `lanes.<lane>.{email_workers,in_app_workers,batch_size,email_quota_per_second,breaker_open_retry,max_attempts}` | Bảng ở [01-lanes.md](./01-lanes.md) §5 chia 10 cho dev |
| Deliver | `deliver.{lease,heartbeat_interval,ordering_lease,ordering_heartbeat_interval,ordering_busy_retry,retry_base,retry_max,retry_jitter,rate_limit_wait,retry_budget_ratio,limiter_fallback_replicas,use_listen_notify}` | `60s`, `20s`, `45s`, `10s`, `2s`, `30s`, `1h`, `30s`, `5s`, `0.2`, `4`, `true`; `config.Validate()` đòi `ordering_lease` > `email.connect_timeout + email.send_timeout + auth.timeout + rate_limit_wait + 5s`, `ordering_heartbeat_interval` ≤ `ordering_lease/3`, `ordering_busy_retry` > 0 |
| Privacy | `NOTIFICATION_PRIVACY_HMAC_KEY` (`privacy.hmac_key`), `NOTIFICATION_PRIVACY_HMAC_KEY_VERSION` (`privacy.hmac_key_version`), `NOTIFICATION_PRIVACY_PREVIOUS_HMAC_KEYS` (`privacy.previous_hmac_keys`, dạng `1:<key>,2:<key>`) | rỗng → ở `app.env = development` dùng dev key cố định (không phải secret); version `1`; không có key cũ |
| Frontend | `NOTIFICATION_FRONTEND_BASE_URL`, `..._FRONTEND_CART_PATH` (chỉ để lắp `cart_url`, `review_url` — §7 của [07-templates.md](./07-templates.md)) | `http://localhost:3000`, `/cart` |
| Quiet hours / cap | `NOTIFICATION_BULK_QUIET_FROM/TO`, `..._BULK_FREQUENCY_CAP_PER_DAY`, `..._BULK_DEFAULT_TIMEZONE` | `22:00`/`08:00`, `2`, `Asia/Ho_Chi_Minh` |
| Outbox | `NOTIFICATION_OUTBOX_PUBLISHER` = `debezium` \| `poller` | compose dev: `poller`; `application.yaml`: `debezium` |
| Jobs | `jobs.{partition_interval,partition_ahead_months,purge_interval,purge_batch,retention_days.*}` | `1h`, `2`, `1h`, `10000`, 90/90/30/90/30/30/14/7 |

Secret (`DB_PASSWORD`, `REDIS_PASSWORD`, AWS credential) không có prefix, giống quy ước Auth trong compose. Không có secret nào trong `application.yaml`. `config.Validate()` từ chối chạy production nếu `email.provider != ses`, thiếu mTLS internal, hoặc `track.verify_signature = false`.
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
| Xem trạng thái | `notifctl status --subject orders/1000234` (gọi status API ở `8092`, header `X-Service-Identity: order-service` ở dev) |
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
- Không có mesh/mTLS ở compose; internal API chấp nhận `X-Service-Identity` không xác thực **chỉ khi** `NOTIFICATION_APP_ENV=development`, và từ chối ở mọi env khác.
- Không có LocalStack SES: SES API gửi được LocalStack giả lập nhưng không sinh event thật; đường gửi dùng Mailpit, đường Track dùng event mẫu bơm vào SNS.
- Debezium là tuỳ chọn ở dev: compose đặt `NOTIFICATION_OUTBOX_PUBLISHER=poller` để không phải đăng ký connector; schema outbox không đổi giữa hai chế độ (D-12).
