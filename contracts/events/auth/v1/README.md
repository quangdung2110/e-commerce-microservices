# Auth Events v1

Đây là nguồn chuẩn cho event Kafka do Auth Service publish. Event dùng
CloudEvents 1.0 JSON structured; schema wire nằm trong [`schemas/`](schemas/)
và topic/message nằm trong [AsyncAPI](asyncapi.yaml).

## Topic và consumer

| Topic | Consumer được phép | Mục đích |
|---|---|---|
| `t.auth.events` | User Service | Khởi tạo profile khi account verified; cleanup dữ liệu User khi account deleted. |
| `t.auth.events` | Notification, Review, Chat Service | Tự cleanup/anonymize projection khi nhận `USER_DELETED` trực tiếp từ Auth. |
| `t.auth.notification.events` | Notification Service | Yêu cầu gửi email xác minh và security notification. |

Kafka key là `user_id`; thứ tự chỉ được cam kết trong cùng `user_id`. Consumer
phải deduplicate bằng CloudEvent `id` và chịu được delivery trùng.

## Giới hạn delivery hiện tại

`USER_DELETED` được Auth publish trực tiếp sau khi transaction account đã commit và
retry tối đa ba lần với cùng CloudEvent `id`. Đây là best-effort, không dùng outbox:
sau khi retry cạn event có thể bị mất. Auth phải phát metric/alert và reconciliation
vận hành phải redrive event bị mất cho mọi PII-owning consumer. Không có payload hay
delivery guarantee mới được thêm bởi Review.

## Delivery content an toàn

Event notification chỉ chứa `delivery_id`, `user_id`, loại sự kiện và các mốc
thời gian. Nó không chứa email, OTP, opaque reset token, reset URL, password,
refresh token hay private key.

Notification Service resolve nội dung gửi qua
[Internal Notification Delivery API](../../../http/auth/internal/v1/openapi.yaml)
bằng mTLS/service identity. API trả cùng nội dung cho cùng `delivery_id` trong
thời gian TTL để Notification có thể retry; OTP và reset token chỉ single-use ở
bước xác minh/reset của user, không bị consume khi Notification đọc nội dung.

## Reliability và versioning

Auth publish trực tiếp sau PostgreSQL commit với retry có giới hạn và không dùng
outbox. Vì vậy, khi retry cạn, event có thể mất và reconciliation vận hành là bắt
buộc; không được diễn giải contract này là exactly-once hoặc durable delivery.

Thêm field optional là tương thích. Bất kỳ thay đổi breaking nào với payload,
Kafka key, ordering scope hoặc event type phải tạo major version mới.
