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
| [decisions.md](./decisions.md) | Các quyết định kiến trúc đã chốt trong thảo luận, kèm hệ quả. |

Mỗi file đứng độc lập được, nhưng đọc theo thứ tự trên sẽ dễ hơn vì các khái niệm được xây dần.

## 4. Nguồn đầu vào đã có contract

Các producer sau đã "hứa" gửi cho Notification trong `contracts/events/`. Đây là đầu vào thực tế của thiết kế, không phải giả định.

| Producer | Topic hiện tại | Message | Key / ordering | Ghi chú |
|---|---|---|---|---|
| Auth | `t.auth.notification.events` | `userOtpRequested`, `passwordResetRequested`, `userEmailChanged`, `userPasswordChanged`, `userAccountLocked`, `userUnlocked` | `user_id` | Chỉ chứa `delivery_id`; recipient email resolve qua internal API. Publish trực tiếp sau commit, retry 3 lần **cùng CloudEvent id**, không outbox. Retention 24h. |
| Cart | `t.cart.events` | `cartAbandonedV1` | `cart_id` | Một abandonment cycle mới cần Notification xử lý. |
| Review | `t.review.events` | `reviewReplyNotificationRequested`, `reviewRemovalNotificationRequested` | `product_id` | Không mang nội dung reply hay lý do pháp lý. |
| User | `t.user.events` | `preferences-updated` | `user_id` | Ba notification preference mà Notification replicate cục bộ. Có daily reconciliation. |
| Order / Payment / Delivery | `t.order.events`, ... | domain facts (`order.paid`, `order-line.delivered`, ...) | `order_id` | Notification tự suy ra "gửi gì" từ fact — xem [decisions.md](./decisions.md) mục command vs fact. |

Nhận xét quan trọng: các producer đang dùng **hai mô hình khác nhau** (Auth/Review gửi *yêu cầu*, Order gửi *sự thật*) và **bốn topic khác nhau**. Việc Notification xếp chúng vào ba lane như thế nào là quyết định trong [01-lanes.md](./01-lanes.md) §6.

## 5. Giả định về tải (cần chốt)

Các con số dưới đây được dùng xuyên suốt tài liệu để minh hoạ và tính toán. Chúng là **giả định**, không phải kết quả đo.

| Đại lượng | Giả định | Dùng để tính |
|---|---|---|
| Tải ổn định | ~40 notification/s | Kích thước pool, DB write |
| Burst chiến dịch | 300.000 message trong 2 phút | Thời gian xả bulk, số partition |
| Quota email provider | 150 email/s, 200.000/ngày | Chia quota theo lane |
| Độ trễ provider bình thường / sự cố | 200 ms / 8 s | Lease, timeout, batch size |
| SLO critical | p99 < 5 s từ event tới provider accepted | Thiết kế lane critical |
| SLO transactional | p99 < 60 s | |
| Bulk | phút → giờ, có thể tạm dừng | Load shedding |

## 6. Quyết định đang mở

Những câu hỏi dưới đây **chưa được chốt** trong thảo luận và ảnh hưởng tới tài liệu chi tiết. Mỗi câu đều đã có khuyến nghị; cần người sở hữu quyết định.

| # | Câu hỏi | Khuyến nghị hiện tại | Ảnh hưởng |
|---|---|---|---|
| 1 | Kênh trong phạm vi phase 1? | Email + in-app; push/SMS ở phase sau | Số pool ở ranh giới 2, số "cửa" Track |
| 2 | Provider email? | Provider có idempotency key cho lane critical (Postmark/SendGrid); SES cho bulk nếu rẻ hơn | Lớp C của idempotency, cơ chế Track (SNS/SQS hay HTTP) |
| 3 | Command-style hay fact-style cho Order lifecycle? | Hybrid: command cho security/moderation, fact cho order lifecycle | Notification giữ bao nhiêu logic nghiệp vụ |
| 4 | Runtime Go hay Java 21+? | Cả hai đều phù hợp (I/O-bound); chọn theo năng lực team | Không ảnh hưởng thiết kế trong bộ tài liệu này |
| 5 | Auth giữ `t.auth.notification.events` hay chuyển sang `t.notification.critical.v1`? | Chuyển; nếu không, Notification consume topic Auth như một lane critical ngoại lệ | Contract Auth, số consumer group |

## 7. Thuật ngữ

| Thuật ngữ | Nghĩa trong tài liệu này |
|---|---|
| **Lane** | Một đường đi vật lý riêng từ topic → consumer group → worker pool → quota provider cho một lớp ưu tiên. |
| **Ranh giới 1 / 2** | Hai điểm tách vật lý của pipeline: Kafka → DB và DB → provider. |
| **Delivery** | Một lần gửi cụ thể trên một kênh. Một request có thể sinh nhiều delivery (email + push). |
| **Head-of-line blocking** | Message ở đầu hàng chặn mọi message phía sau, bất kể độ quan trọng. |
| **Exactly-once effect** | Hậu quả nhìn từ user như thể xử lý đúng một lần, dù nội bộ xử lý nhiều lần. |
| **Suppression list** | Danh sách địa chỉ không được gửi (bounce, complaint, unsubscribe). |
| **Lease** | Khoảng thời gian một worker được giữ một delivery; hết lease mà chưa xong thì coi như worker chết. |
