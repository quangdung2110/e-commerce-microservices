# Development

**Trạng thái:** `proposed` — chưa có mã nguồn. File này mô tả layout, môi trường dev và cách chạy để người bắt đầu implement không phải suy diễn từ các file thiết kế.

## 1. Vị trí và layout

`go/services/notification-service`, theo [golang-common-layout](../common/golang/golang-common-layout.md) như Auth/User/Cart:

```
go/services/notification-service/
  cmd/
    server/main.go              -- một binary; chạy các thành phần theo --role hoặc env ROLE
    notifctl/main.go            -- CLI ops/dev: render preview, redrive, pause lane (gọi cùng handler với ops API)
  configs/
    application.yaml            -- override qua env prefix NOTIFICATION_ (viper, giống Auth)
  migrations/                   -- golang-migrate; 001 schema (09-data-model.md), 002 partitions, 003 publication
  internal/
    api/
      handler/  inbox.go, status.go, ops.go
      router/
      middleware/               -- gateway identity, mTLS principal allowlist, Idempotency-Key, audit
    domain/
      entity/                   -- Request, Delivery, Preference, Suppression, InboxItem
      port/                     -- RequestRepo, DeliveryQueue, PreferenceRepo, ContactResolver, Sender, Renderer, Limiter
      service/
        decide/                 -- 08-decide.md: 13 bước, thuần, test bằng bảng
        deliver/                -- vòng lặp claim → resolve → render → send → CAS
        track/                  -- correlate, state ranking, suppression
    infrastructure/
      kafka/                    -- consumer request (order, review, auth), consumer fact (cart), consumer preference, consumer user-deleted
      postgres/                 -- repo, claim query, reaper, partition job, purge job
      redis/                    -- cache contact, frequency cap, unread count, token bucket
      auth/                     -- client content + notification-contact (bulkhead theo lane, breaker)
      ses/                      -- SES v2 client; configuration set theo lane
      smtp/                     -- Mailpit adapter (dev/CI), cùng interface Sender
      sqs/                      -- poller SES events, verify chữ ký SNS, hash email trước INSERT
    templates/                  -- 12-render.md: embed, registry, funcs, testdata golden
    workers/
      deliver/  reaper/  track/  partition/  purge/
    common/
      config/  logging/  reqctx/  clock/  errors/
  deploy/
    localstack/init-ses-events.sh
    debezium/notification-outbox-connector.json
  test/
    integration/                -- testcontainers: Postgres, Redis, Kafka, Mailpit, LocalStack
```

Một binary, nhiều vai trò: `ROLE=all` ở dev chạy mọi thứ trong một process; production chạy các deployment tách theo `ROLE=consumer LANE=critical`, `ROLE=deliver LANE=bulk`, `ROLE=track`, `ROLE=api` để bulkhead ở mức process ([06-backpressure.md](./06-backpressure.md) §3). Cùng code, khác env — không có hai đường code.

## 2. Môi trường dev

### 2.1 Những gì `docker-compose.yml` cung cấp

| Thành phần | Có sẵn (profile mặc định) | Thêm cho Notification (profile `notification`) |
|---|---|---|
| Kafka, Zookeeper | ✓ | |
| Postgres 16 (`wal_level=logical`) | ✓ | DB `notification_db` tạo bởi `init-db.sh` |
| Redis | ✓ | |
| Kafka Connect (Debezium 3.0) | ✓ | Connector `notification-outbox-connector` đăng ký bằng REST (§4) |
| **Mailpit** (SMTP + UI + REST) | | `mailpit` — SMTP `1025`, UI/API `http://localhost:8025` |
| **LocalStack** (SNS, SQS) | | `localstack` — `http://localhost:4566`; script `deploy/localstack/init-ses-events.sh` dựng topic `ses-events` → queue `notification-ses-events` (+ DLQ) |
| Traefik | ✓ | Route `/api/v1/notifications/**` khi service có Docker label (chưa thêm vì chưa có image) |

Bật:

```bash
docker compose --profile notification up -d mailpit localstack
```

Hai container này nằm sau profile nên `docker compose up` mặc định của các service khác không đổi.

### 2.2 Vì sao Mailpit và LocalStack, không phải SES sandbox

- SES sandbox chỉ gửi tới địa chỉ đã verify và giới hạn 200/ngày — không chạy được test tích hợp lặp lại, và mọi dev đều cần AWS credential.
- Mailpit nhận mọi email qua SMTP, hiển thị HTML/text/headers, và có REST API (`GET /api/v1/messages`, `GET /api/v1/message/{id}`) để test tự động đọc lại email vừa gửi — đúng thứ test tích hợp cần.
- LocalStack cho đường Track: test bơm một event SES mẫu (`Delivery`, `Bounce`, `Complaint`) vào SNS bằng `awslocal sns publish`, poller nhận từ SQS như production. Adapter SES thật chỉ được test bằng contract test riêng khi có credential.

### 2.3 Cổng

| Thành phần | Cổng host | Ghi chú |
|---|---|---|
| notification-service HTTP | `8091` | `8090` đã là dashboard Traefik; OpenAPI `servers` dùng `8091` |
| Mailpit SMTP / UI | `1025` / `8025` | |
| LocalStack | `4566` | |

## 3. Cấu hình

Env prefix `NOTIFICATION_` (viper `SetEnvPrefix`, giống Auth). Nhóm chính:

| Nhóm | Ví dụ key | Dev mặc định |
|---|---|---|
| Vai trò | `NOTIFICATION_ROLE`, `NOTIFICATION_LANE` | `all`, `-` |
| Postgres | `NOTIFICATION_DATABASE_HOST/PORT/NAME/USERNAME`, `DB_PASSWORD` | `postgres:5432/notification_db` |
| Redis | `NOTIFICATION_REDIS_HOST/PORT`, `REDIS_PASSWORD` | `redis:6379` |
| Kafka | `NOTIFICATION_KAFKA_BROKERS`, consumer group theo lane | `kafka:9092` |
| Email provider | `NOTIFICATION_EMAIL_PROVIDER` = `smtp` \| `ses`; `NOTIFICATION_SMTP_HOST/PORT`; `NOTIFICATION_SES_REGION`, `..._CONFIGURATION_SET_CRITICAL_TRANSACTIONAL`, `..._CONFIGURATION_SET_BULK` | `smtp` → `mailpit:1025` |
| Track | `NOTIFICATION_SQS_QUEUE_URL`, `NOTIFICATION_AWS_ENDPOINT` (LocalStack) | `http://localstack:4566/000000000000/notification-ses-events` |
| Auth | `NOTIFICATION_AUTH_BASE_URL`, timeout, bulkhead theo lane | `http://auth-service:8080`, `2s` |
| Lane | pool size, quota/s, breaker threshold theo lane | Bảng ở [01-lanes.md](./01-lanes.md) §5 chia 10 cho dev |
| Frontend | `NOTIFICATION_FRONTEND_BASE_URL` (chỉ cho `cart_url`) | `http://localhost:3000` |
| Quiet hours / cap | `NOTIFICATION_BULK_QUIET_FROM/TO`, `..._FREQUENCY_CAP_PER_DAY` | `22:00`/`08:00`, `2` |

Secret (`DB_PASSWORD`, AWS credential) không có prefix, giống quy ước Auth trong compose. Không có secret nào trong `application.yaml`.

## 4. Khởi tạo lần đầu

```bash
# 1. hạ tầng
docker compose up -d postgres redis kafka kafka-connect
docker compose --profile notification up -d mailpit localstack

# 2. schema (golang-migrate; migrations/ gồm 09-data-model.md + partition 3 tháng + publication)
notifctl migrate up

# 3. Debezium outbox connector (publication.autocreate.mode = disabled → migration 003 đã CREATE PUBLICATION)
curl -X POST -H 'Content-Type: application/json' \
  --data @go/services/notification-service/deploy/debezium/notification-outbox-connector.json \
  http://localhost:8084/connectors

# 4. topic Kafka (dev: auto-create bật; production: tạo trước với số partition ở 01-lanes.md §5)
#    t.notification.critical.v1 (3) · transactional.v1 (12) · bulk.v1 (24) · outcomes.v1 (12) · *.dlq

# 5. chạy
NOTIFICATION_ROLE=all go run ./go/services/notification-service/cmd/server
```

Migration `003_publication.up.sql`:

```sql
CREATE PUBLICATION notification_outbox_publication FOR TABLE outbox;
```

(chỉ bảng `outbox`; không publish bảng có PII).

## 5. Vòng lặp phát triển hằng ngày

| Việc | Cách |
|---|---|
| Sửa template | Sửa file trong `internal/templates/<ID>/vi/`, chạy `go test ./internal/templates/ -update` để cập nhật golden, xem diff; `notifctl render --template ORDER_CONFIRMED --data testdata/ORDER_CONFIRMED/basic.json --send-to mailpit` rồi mở `http://localhost:8025` |
| Thử một request end-to-end | `notifctl publish-example --file contracts/examples/events/notification-requested.v1.json` (publish lên đúng topic theo lane của template) → xem email ở Mailpit, row ở `notification_delivery`, event ở `t.notification.outcomes.v1` |
| Giả lập bounce | `awslocal sns publish --topic-arn arn:aws:sns:ap-southeast-1:000000000000:ses-events --message file://testdata/ses/bounce-permanent.json` → kiểm tra `suppression` |
| Xem trạng thái | `notifctl status --subject orders/1000234` (gọi status API) |
| Pause/resume | `notifctl lane bulk pause --reason "..."` |
| Test | `go test ./...` (unit + golden); `go test -tags integration ./test/integration/...` (testcontainers — xem [15-verification.md](./15-verification.md)) |

## 6. Điều kiện để lên `implemented`

Theo `contracts/catalog/services.yaml`, trạng thái chỉ được nâng khi:

- Consumer 3 lane + Auth + Review + Cart + preference + user-deleted chạy được với contract test chống lại example trong `contracts/examples/events/`.
- Deliver worker gửi được qua Mailpit và SES (sandbox) với configuration set theo lane.
- Track poller xử lý được `Delivery`/`Bounce`/`Complaint` từ LocalStack, ghi suppression.
- Inbox API đi qua Traefik với `X-User-Id`.
- Outbox → Debezium → `t.notification.outcomes.v1` có consumer test đọc được.
- Bộ test bắt buộc ở [15-verification.md](./15-verification.md) §2 pass trong CI.

## 7. Những gì cố ý chưa làm ở dev

- Không có SES thật trong compose; adapter SES chỉ có contract test khi có credential (không chạy mặc định).
- Không có mesh/mTLS ở compose; internal API chấp nhận `X-Service-Identity` không xác thực **chỉ khi** `NOTIFICATION_ENV=dev`, và từ chối ở mọi env khác.
- Không có LocalStack SES: SES API gửi được LocalStack giả lập nhưng không sinh event thật; đường gửi dùng Mailpit, đường Track dùng event mẫu bơm vào SNS.
