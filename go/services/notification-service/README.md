# notification-service

Runtime Go của Notification Service — nhận request/fact qua Kafka, quyết định gửi gì cho ai qua kênh nào (email, in-app), gửi qua provider (Mailpit ở dev, Amazon SES ở production), theo dõi kết quả qua SES → SNS → SQS, và phát outcome event. Thiết kế đầy đủ ở [docs/notification](../../../docs/notification/README.md); layout, cấu hình và vòng lặp phát triển ở [13-development.md](../../../docs/notification/13-development.md).

## Chạy nhanh

```bash
# hạ tầng + service (profile notification)
docker compose up -d postgres redis kafka
docker compose --profile notification up -d mailpit localstack notification-service

# schema
bash scripts/go.sh run ./cmd/notifctl migrate up --dsn "postgres://postgres:postgres@localhost:5432/notification_db?sslmode=disable"

# thử một request end-to-end rồi xem Mailpit ở http://localhost:8025
bash scripts/go.sh run ./cmd/notifctl publish-example --file ../../../contracts/examples/events/notification-requested.v1.json
```

| Cổng host | Cổng container | Dùng cho |
|---|---|---|
| `8091` | `8080` | Inbox API (`/api/v1/notifications/inbox/**`, qua Traefik với `X-User-Id`) |
| `8092` | `8081` | Internal: status, ops, `/metrics`, `/livez`, `/readyz` — không qua Gateway |

## Một binary, nhiều vai trò

`NOTIFICATION_APP_ROLE` = `all` (dev) | `api` | `consumer` | `deliver` | `track` | `jobs`; `NOTIFICATION_APP_LANE` giới hạn consumer/deliver về một lane. Mọi key trong `configs/application.yaml` override được với tiền tố `NOTIFICATION_`.

Secret bắt buộc ngoài `development`: `NOTIFICATION_PRIVACY_HMAC_KEY` (≥ 32 byte) — khoá HMAC cho ledger sequence của Order, ordering lease và tombstone user đã xoá; `development` tự dùng một dev key cố định. Quy trình rotate (`..._HMAC_KEY_VERSION`, `..._PREVIOUS_HMAC_KEYS`) ở [13-development.md](../../../docs/notification/13-development.md) §3.

## Build và test

Máy dev không cần Go: `scripts/go.sh` chạy lệnh trong container `golang:1.25` với module cache trên Docker volume.

```bash
bash scripts/go.sh build ./...
bash scripts/go.sh vet ./...
bash scripts/go.sh test ./internal/...                       # unit + golden + contract
bash scripts/go.sh test -tags integration ./test/integration/... ./internal/...   # testcontainers
bash scripts/go.sh test ./internal/templates/ -update         # cập nhật golden sau khi sửa template
```

## Thư mục

| Đường dẫn | Dùng cho |
|---|---|
| `cmd/server`, `cmd/notifctl` | Binary service và CLI ops/dev |
| `internal/catalog` | Bản sao byte-equal của catalog template + JSON Schema `template_data` |
| `internal/domain` | Entity, port, use case (decide, ingest, deliver, track, inbox, status, ops, lifecycle) |
| `internal/infrastructure` | Postgres, Redis, Kafka, Auth client, SES/SMTP, SQS, CloudEvents |
| `internal/templates` | 15 template (vi), layout, golden test |
| `internal/workers` | deliver, reaper, track, outbox, partition, purge |
| `migrations` | golang-migrate, embed vào binary (001 schema, 002 partitions, 003 publication, 004 ordering/privacy) |
| `deploy/localstack/init-ses-events.sh` | LocalStack dựng SNS `ses-events` → SQS `notification-ses-events` (+ DLQ) |
| `deploy/debezium/notification-outbox-connector.json` | Debezium outbox connector (tuỳ chọn; dev dùng `outbox.publisher: poller`) |
