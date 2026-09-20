# notification-service

Runtime Go cho Notification Service — **chưa có mã nguồn**; thiết kế ở
[docs/notification](../../../docs/notification/README.md).

Thư mục này hiện chỉ chứa tài sản hạ tầng dev mà `docker-compose.yml` (profile
`notification`) và hướng dẫn phát triển tham chiếu:

| Đường dẫn | Dùng cho |
|---|---|
| `deploy/localstack/init-ses-events.sh` | LocalStack dựng SNS `ses-events` → SQS `notification-ses-events` (+ DLQ) cho đường Track |
| `deploy/debezium/notification-outbox-connector.json` | Debezium outbox connector đăng ký vào `kafka-connect` (`t.notification.outcomes.v1`) |

Layout dự kiến khi bắt đầu code theo `docs/common/golang/golang-common-layout.md`:
xem [docs/notification/13-development.md](../../../docs/notification/13-development.md).
