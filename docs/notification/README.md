# Notification Service

**Trạng thái:** `implemented` (phase 1) — mã nguồn tại `go/services/notification-service` (Go), unit test và integration test (Testcontainers: Postgres, Redis, Kafka, Mailpit, LocalStack) chạy được; **chưa** có load test và adapter SES thật chưa được kiểm với credential (chỉ test với endpoint giả). Contract Notification vẫn `proposed` trong [`contracts/ADOPTION.md`](../../contracts/ADOPTION.md) vì chưa có consumer contract test. Mọi con số tải trong bộ tài liệu này là giả định thiết kế và được đánh dấu rõ; chúng phải được chốt lại bằng đo đạc trước khi coi là cam kết. Những chỗ tài liệu được làm rõ hoặc sửa khi implement gom ở [decisions.md](./decisions.md) D-20…D-24.

## 1. Vấn đề cần giải quyết

Các service nghiệp vụ (Auth, Order, Review, Cart, ...) cần gửi thông báo tới người dùng qua email, push, SMS, in-app. Nếu mỗi service tự gọi provider thì: quota provider bị chia nhỏ và không ai kiểm soát; preferences, suppression list và template bị nhân bản; và lúc provider gặp sự cố thì mọi service đều chậm theo.

Notification Service gom việc đó về một chỗ. Nó nhận **yêu cầu** hoặc **sự kiện nghiệp vụ** qua Kafka, quyết định gửi gì / cho ai / qua kênh nào, gửi qua provider, theo dõi kết quả, và bảo vệ phần còn lại của hệ thống khỏi các đặc tính khó chịu của provider bên ngoài (chậm, chập chờn, có quota, có hình phạt).

## 2. Ba nguyên tắc xuyên suốt

Toàn bộ thiết kế đều suy ra từ ba câu này; khi có tranh luận về một chi tiết, quay về đây để phân xử.

1. **Thứ không quan trọng không được chặn thứ quan trọng.** OTP không được xếp hàng sau email khuyến mãi. → [Lane ưu tiên](./01-lanes.md).
2. **Thứ chậm không được chặn thứ nhanh.** Consumer Kafka không được chờ provider. → [Pipeline hai ranh giới](./02-pipeline.md).
3. **At-least-once ở mọi nơi, idempotent ở mọi nơi.** Trùng lặp là đường đi bình thường, không phải ngoại lệ. → [Idempotency](./04-idempotency.md).

## 3. Bản đồ tài liệu

| File | Trả lời câu hỏi |
|---|---|
| [01-lanes.md](./01-lanes.md) | Vì sao tách ba lane critical / transactional / bulk, tách ở những tầng nào, ai chọn lane. |
| [02-pipeline.md](./02-pipeline.md) | Pipeline logic sáu stage thu về hai ranh giới vật lý; Kafka consumer và deliver worker poll DB hoạt động ra sao. |
| [03-tracking.md](./03-tracking.md) | Nhận webhook / feedback từ provider, cập nhật trạng thái, nuôi suppression list. |
| [04-idempotency.md](./04-idempotency.md) | Trùng đến từ đâu và bốn lớp chặn nó; "exactly-once effect" nghĩa là gì. |
| [05-ordering.md](./05-ordering.md) | Thứ tự nào thực sự cần, mất ở đâu, bảo vệ tính đúng đắn bằng gì. |
| [06-backpressure.md](./06-backpressure.md) | Áp lực dồn về đâu, bulkhead, rate limiter, circuit breaker, retry budget, timeout, load shedding. |
| [07-templates.md](./07-templates.md) | Catalog 15 template phase 1: quy ước, thuộc tính, chính sách theo lane, `template_data` từng template. |
| [08-decide.md](./08-decide.md) | Stage Decide chạy ở đâu, thứ tự 13 bước, mã lý do, mặc định khi thiếu preference, suppression theo `user_id` + `email_hash`. |
| [09-data-model.md](./09-data-model.md) | DDL các bảng, sổ dedup vì ràng buộc partition + unique của Postgres, partition/retention, outbox CDC, xử lý `user.deleted`. |
| [10-interfaces.md](./10-interfaces.md) | Bản đồ mọi bề mặt vào/ra: Kafka request và outcome, Auth internal API, inbox API, status/ops API; link tới contract thật. |
| [11-privacy.md](./11-privacy.md) | PII ở đâu, sống bao lâu, ai xoá; quy tắc log/metric/trace; checklist khi thêm template hoặc nguồn mới. |
| [12-render.md](./12-render.md) | `html/template` vì escaping theo ngữ cảnh; template trong repo với `embed`; `template_version` từ hash; validate và render thử lúc khởi động; snapshot test. |
| [13-development.md](./13-development.md) | Layout Go, môi trường dev (Mailpit, LocalStack, Debezium connector), cấu hình, khởi tạo lần đầu, vòng lặp phát triển. |
| [14-observability.md](./14-observability.md) | Metric theo `stage × lane × channel`, alert theo mức, trace xuyên event → provider, timeline support, quy tắc log. |
| [15-verification.md](./15-verification.md) | Test tích hợp bắt buộc gắn với từng quyết định; phép đo thay giả định; chaos nightly. |
| [16-operations.md](./16-operations.md) | SLO, công tắc vận hành, 15 runbook, việc định kỳ, triển khai/rollback, bảo mật vận hành. |
| [decisions.md](./decisions.md) | Các quyết định kiến trúc đã chốt trong thảo luận, kèm hệ quả. |

Mỗi file đứng độc lập được, nhưng đọc theo thứ tự trên sẽ dễ hơn vì các khái niệm được xây dần.

## 4. Nguồn đầu vào

Notification nhận hai loại đầu vào — **request** (producer chỉ định template và đóng gói dữ liệu nó đang sở hữu) và **fact** (sự kiện nghiệp vụ, Notification tự ánh xạ sang template). Cả hai đi qua cùng pipeline; quy tắc phân loại ở [decisions.md](./decisions.md) D-07.

| Producer | Mô hình | Topic | Message | Key | Lane | Trạng thái contract |
|---|---|---|---|---|---|---|
| Auth | Request (dạng đặc biệt: resolve content theo `delivery_id`) | `t.auth.notification.events` | `userOtpRequested`, `passwordResetRequested`, `userEmailChanged`, `userPasswordChanged`, `userAccountLocked`, `userUnlocked` | `user_id` | critical | **Đã có.** Chỉ chứa `delivery_id` + metadata; template gán từ `type` ngay ở Ingest, email + secret resolve qua `POST /internal/v1/auth/notification-deliveries/{id}/content` lúc gửi. Retry 3 lần cùng CloudEvent id, không outbox, retention 24h. |
| Review | Request | `t.review.events` | `reviewReplyNotificationRequested`, `reviewRemovalNotificationRequested` | `product_id` | transactional | **Đã có.** Không mang nội dung reply hay lý do pháp lý. |
| **Order** | **Request** | `t.notification.transactional.v1` | `notification.requested.v1` với 5 template `ORDER_CONFIRMED`, `ORDER_PAYMENT_FAILED`, `ORDER_CANCELLED`, `ORDER_COMPLETED`, `ORDER_REFUNDED` | `subject` (= `orders/{order_id}`) | transactional | **Contract đã viết** (§7, mục 1). Order đóng gói immutable item snapshot (Checkout bắt buộc `snapshot_name`), tổng tiền và địa chỉ từ row đơn hàng của chính nó — kể cả các mốc thanh toán/hoàn tiền/giao hàng mà Order đã consume từ Payment và Delivery; không runtime-enrich từ Product/User. |
| Cart | Fact | `t.cart.events` | `cartAbandonedV1` | `cart_id` | bulk | **Đã có.** Payload đủ (`cart_id, user_id, abandonment_cycle`). |
| User | Fact (state replication) | `t.user.events` | `preferences-updated` (`email_enabled`, `in_app_enabled`, `marketing_enabled`) | `user_id` | — (consumer riêng) | **Đã có.** Có daily reconciliation. |
| Payment, Delivery | **Không consume** | — | — | — | — | Fact của họ **không có `user_id`** (Payment cấm tường minh trong schema; Delivery chỉ có `order_id`/`order_line_id`). Notification không biết gửi cho ai nếu không replicate Order. Order đã consume các fact này để chạy state machine và phát request thay. |

Ba điều rút ra từ bảng:

- **Không có event nào mang email người nhận.** Email là PII, không đi qua Kafka (bản ghi Kafka tồn tại theo retention, được replicate, không xoá được khi user xoá tài khoản, và có thể lỗi thời khi user đổi email). Notification resolve email **lúc gửi** qua Auth internal API (listener 8081) — với Auth là API `content` đã có, với mọi nguồn khác là API `notification-contact` (§7, mục 2). In-app không cần resolve gì vì chỉ cần `user_id`.
- **Fact chỉ dùng được khi có `user_id` và đủ dữ liệu render.** Cart thoả; Payment và Delivery không — nên mọi mốc đơn hàng đi qua Order. Đây là cách áp dụng đúng quy tắc D-07 sau khi soi schema thật.
- Preference hiện chỉ có `email_enabled`, `in_app_enabled`, `marketing_enabled` — khớp với phạm vi kênh phase 1 (D-08).

Danh sách đầy đủ 15 template phase 1 và chính sách theo lane: [07-templates.md](./07-templates.md).

## 5. Giả định về tải (cần chốt)

Các con số dưới đây được dùng xuyên suốt tài liệu để minh hoạ và tính toán. Chúng là **giả định**, không phải kết quả đo.

| Đại lượng | Giả định | Dùng để tính |
|---|---|---|
| Tải ổn định | ~40 notification/s | Kích thước pool, DB write |
| Burst chiến dịch | 300.000 message trong 2 phút | Thời gian xả bulk, số partition |
| Quota email provider | SES sau khi ra khỏi sandbox: giả định 50 email/s, 50.000/ngày (free tier 3.000/tháng năm đầu); sandbox chỉ gửi tới địa chỉ đã verify | Chia quota theo lane |
| Độ trễ provider bình thường / sự cố | 200 ms / 8 s | Lease, timeout, batch size |
| SLO critical | p99 < 5 s từ event tới provider accepted | Thiết kế lane critical |
| SLO transactional | p99 < 60 s | |
| Bulk | phút → giờ, có thể tạm dừng | Load shedding |

## 6. Quyết định đã chốt và đang mở

### Đã chốt

| # | Câu hỏi | Quyết định | Chi tiết |
|---|---|---|---|
| A1 | Command-style hay fact-style? | **Hybrid.** Request khi producer phải đóng gói dữ liệu nó sở hữu hoặc quyết định điều kiện gửi (Auth, Review, **Order**); fact khi payload sẵn có `user_id` và đủ để render (Cart). Payment/Delivery không consume vì fact của họ không có `user_id`; Order phát request thay. Email luôn do Notification resolve lúc gửi. | [decisions.md](./decisions.md) D-07 |
| A2 | Kênh phase 1? | **Email + in-app.** Push/SMS để phase sau; thiết kế để thêm push chỉ là thêm adapter + pool + preference flag. | D-08 |
| A3 | Provider email? | **Amazon SES** (rẻ nhất, mục tiêu học tập) + **Mailpit** cho dev/CI. Tracking qua SNS → SQS. **Không** có provider dự phòng ở phase 1. SES không có idempotency key → lớp C của idempotency dựa vào compare-and-set + chính sách theo lane. | D-09 |
| A5 | Runtime? | **Go.** Nhất quán với Auth/User/Cart; goroutine + worker pool khớp workload I/O-bound; footprint nhỏ cho đồ án nhiều container. Vị trí `go/services/notification-service`. | D-15 |
| A4 | Topic của Auth? | **Giữ `t.auth.notification.events`.** Mô hình resolve-content-theo-`delivery_id` của Auth khác request thường; Notification consume bằng consumer group riêng `notification-auth-critical`, xử lý như lane critical thứ hai (pool và quota critical dùng chung). Channel `t.notification.critical.v1` vẫn khai báo sẵn cho producer critical khác sau này. | D-19 |

### Đang mở

Không còn. Mọi quyết định thiết kế phase 1 đã chốt (D-01…D-19); phần còn lại là implement và đo ([15-verification.md](./15-verification.md) §3).

## 7. Contract

Contract Notification sở hữu đã được viết và provider đã chạy theo nó, nhưng `x-adoption-status` vẫn là `proposed` (xem `contracts/ADOPTION.md`: ba dòng Notification Events v1, HTTP v1, Internal HTTP v1 ghi khoảng cách còn lại). Các thay đổi cần thiết ở contract của service khác đã được áp trực tiếp trong repo. Tổng hợp ở [10-interfaces.md](./10-interfaces.md) §7.

| # | Contract | Trạng thái | Ghi chú |
|---|---|---|---|
| 1 | [`contracts/events/notification/v1/asyncapi.yaml`](../../contracts/events/notification/v1/asyncapi.yaml) + `schemas/` + [`templates/catalog.yaml`](../../contracts/events/notification/v1/templates/catalog.yaml) + `templates/ORDER_*.schema.json` | **Đã viết** | 3 channel request, 1 channel outcome, 15 template, 5 schema `template_data`; example đã validate |
| 2 | `GET /internal/v1/auth/users/{user_id}/notification-contact` trong [Auth internal OpenAPI](../../contracts/http/auth/internal/v1/openapi.yaml) | **Đã thêm và đã implement trong auth-service** (listener internal 8081; `locale` luôn `null` ở phase 1) | Test tích hợp `TestHTTPInternalNotificationContact` của Auth |
| 3 | Snapshot đơn hàng của Order có `name` sản phẩm | **Đã chốt ở target contract** | Checkout bắt buộc `items[].snapshot_name`; Order persist `order_item_lines.snapshot_name` và map sang notification `items[].name`, không runtime RPC |
| 4 | [Notification OpenAPI v1](../../contracts/http/notification/v1/openapi.yaml) (inbox) và [internal](../../contracts/http/notification/internal/v1/openapi.yaml) (status, ops) | **Đã viết** | Route Gateway và mesh policy đã khai báo |
| 5 | User Service consume `notification.complaint-received.v1`; Analytics consume `t.notification.outcomes.v1` | **User đã implement** ([User ADR-009](../user/decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md): group `user-service`, DLQ `t.notification.outcomes.v1.dlq`); **Analytics còn mở** (service chưa có runtime) | Đã ghi `x-consumers`; owner đăng ký phía họ |

Hạ tầng dev: **Mailpit**, **LocalStack** và container **notification-service** (host 8091 inbox, 8092 internal) trong `docker-compose.yml` dưới profile `notification`; DB `notification_db` trong `init-db.sh`; connector Debezium và script LocalStack ở `go/services/notification-service/deploy/`. Xem [13-development.md](./13-development.md).

## 8. Thuật ngữ

| Thuật ngữ | Nghĩa trong tài liệu này |
|---|---|
| **Lane** | Một đường đi vật lý riêng từ topic → consumer group → worker pool → quota provider cho một lớp ưu tiên. |
| **Ranh giới 1 / 2** | Hai điểm tách vật lý của pipeline: Kafka → DB và DB → provider. |
| **Delivery** | Một lần gửi cụ thể trên một kênh. Một request có thể sinh nhiều delivery (email + push). |
| **Head-of-line blocking** | Message ở đầu hàng chặn mọi message phía sau, bất kể độ quan trọng. |
| **Exactly-once effect** | Hậu quả nhìn từ user như thể xử lý đúng một lần, dù nội bộ xử lý nhiều lần. |
| **Suppression list** | Danh sách địa chỉ không được gửi (bounce, complaint, unsubscribe). |
| **Lease** | Khoảng thời gian một worker được giữ một delivery; hết lease mà chưa xong thì coi như worker chết. |
