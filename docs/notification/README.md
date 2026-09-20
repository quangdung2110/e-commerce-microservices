# Notification Service

**Trạng thái:** `proposed` — chưa có runtime, chưa có load test. Mọi con số trong bộ tài liệu này là giả định thiết kế và được đánh dấu rõ; chúng phải được chốt lại bằng đo đạc trước khi coi là cam kết.

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
| [decisions.md](./decisions.md) | Các quyết định kiến trúc đã chốt trong thảo luận, kèm hệ quả. |

Mỗi file đứng độc lập được, nhưng đọc theo thứ tự trên sẽ dễ hơn vì các khái niệm được xây dần.

## 4. Nguồn đầu vào

Notification nhận hai loại đầu vào — **request** (producer chỉ định template và đóng gói dữ liệu nó đang sở hữu) và **fact** (sự kiện nghiệp vụ, Notification tự ánh xạ sang template). Cả hai đi qua cùng pipeline; quy tắc phân loại ở [decisions.md](./decisions.md) D-07.

| Producer | Mô hình | Topic | Message | Key | Lane | Trạng thái contract |
|---|---|---|---|---|---|---|
| Auth | Request (dạng đặc biệt: resolve content theo `delivery_id`) | `t.auth.notification.events` | `userOtpRequested`, `passwordResetRequested`, `userEmailChanged`, `userPasswordChanged`, `userAccountLocked`, `userUnlocked` | `user_id` | critical | **Đã có.** Chỉ chứa `delivery_id`; email + template + secret resolve qua `POST /internal/v1/auth/notification-deliveries/{id}/content`. Retry 3 lần cùng CloudEvent id, không outbox, retention 24h. |
| Review | Request | `t.review.events` | `reviewReplyNotificationRequested`, `reviewRemovalNotificationRequested` | `product_id` | transactional | **Đã có.** Không mang nội dung reply hay lý do pháp lý. |
| **Order** | **Request** | `t.notification.transactional.v1` | `notification.requested.v1` với 5 template `ORDER_CONFIRMED`, `ORDER_PAYMENT_FAILED`, `ORDER_CANCELLED`, `ORDER_COMPLETED`, `ORDER_REFUNDED` | `subject` (= `orders/{order_id}`) | transactional | **Cần mở** (§7, mục 1). Order đóng gói item snapshot, tổng tiền, địa chỉ từ row đơn hàng của chính nó — kể cả các mốc thanh toán/hoàn tiền/giao hàng mà Order đã consume từ Payment và Delivery. |
| Cart | Fact | `t.cart.events` | `cartAbandonedV1` | `cart_id` | bulk | **Đã có.** Payload đủ (`cart_id, user_id, abandonment_cycle`). |
| User | Fact (state replication) | `t.user.events` | `preferences-updated` (`email_enabled`, `in_app_enabled`, `marketing_enabled`) | `user_id` | — (consumer riêng) | **Đã có.** Có daily reconciliation. |
| Payment, Delivery | **Không consume** | — | — | — | — | Fact của họ **không có `user_id`** (Payment cấm tường minh trong schema; Delivery chỉ có `order_id`/`order_line_id`). Notification không biết gửi cho ai nếu không replicate Order. Order đã consume các fact này để chạy state machine và phát request thay. |

Ba điều rút ra từ bảng:

- **Không có event nào mang email người nhận.** Email là PII, không đi qua Kafka (bản ghi Kafka tồn tại theo retention, được replicate, không xoá được khi user xoá tài khoản, và có thể lỗi thời khi user đổi email). Notification resolve email **lúc gửi** qua Auth internal API — với Auth là API `content` đã có, với mọi nguồn khác là API `notification-contact` cần mở (§7, mục 2). In-app không cần resolve gì vì chỉ cần `user_id`.
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

### Đang mở

| # | Câu hỏi | Khuyến nghị hiện tại | Ảnh hưởng |
|---|---|---|---|
| A4 | Auth giữ `t.auth.notification.events` hay chuyển sang `t.notification.critical.v1`? | Giữ ở phase 1 (mô hình resolve-content-theo-`delivery_id` của Auth khác với request thường); Notification consume bằng consumer group riêng như lane critical thứ hai. Xem xét gộp khi Auth sẵn sàng sửa contract. | Số consumer group |
| A5 | Runtime Go hay Java 21+? | Cả hai phù hợp (I/O-bound); chọn theo năng lực team | Development guide, vị trí thư mục |

## 7. Contract cần mở với service khác

Ba việc dưới đây là **điều kiện tiên quyết** để thiết kế chạy được; chúng nằm ngoài `docs/notification` và cần đàm phán với owner tương ứng.

| # | Contract | Owner | Nội dung | Nếu thiếu |
|---|---|---|---|---|
| 1 | `contracts/events/notification/v1/asyncapi.yaml` + `templates/*.schema.json` | Notification | Ba channel `t.notification.{critical,transactional,bulk}.v1`; message `com.ecommerce.notification.requested.v1` với `template` (enum từ catalog Notification sở hữu), `user_id`, `dedup_key?`, `expires_at?`, `template_data` (oneOf theo template). Message key = `subject` (aggregate id, ví dụ `order_id`) để ordering theo aggregate và tránh hot key theo user. `x-access-policy` liệt kê producer được ghi. Producer đầu tiên: Order với 5 template ([07-templates.md](./07-templates.md) §5.2). | Order không có cách gửi thông báo; catalog template không có chỗ khai báo |
| 2 | `GET /internal/v1/auth/users/{user_id}/notification-contact` trong `contracts/http/auth/internal/v1/openapi.yaml` | Auth | Trả `email`, `email_verified`, `locale?`. mTLS, `Cache-Control: no-store`, rate limit riêng cho `notification-service`. `404` khi user không tồn tại/đã xoá → delivery `FAILED (no_recipient)`. | Chỉ Auth gửi được email; Order/Review/Cart chỉ gửi được in-app |
| 3 | Snapshot đơn hàng của Order có `name` sản phẩm | Order (và Checkout nếu snapshot được tạo ở đó) | `ORDER_CONFIRMED` và `ORDER_COMPLETED` cần tên sản phẩm trong `items[]`. `order.completed.v1` hiện chỉ có `product_id`, `sku_id`, `variant_name`; nếu snapshot nội bộ cũng thiếu `name`, Checkout ghi thêm một field lúc tạo snapshot. Thay đổi ở nguồn, không phải RPC lúc gửi. | Email xác nhận đơn chỉ hiện SKU — dùng được nhưng kém |

Ngoài ra, hạ tầng dev cần thêm **Mailpit** vào `docker-compose.yml` (SMTP + web UI + REST API để test tự động đọc email đã gửi).

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
