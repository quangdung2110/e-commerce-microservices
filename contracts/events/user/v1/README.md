# User Events v1

Đây là nguồn chuẩn cho event Kafka do User Service publish. Event dùng
CloudEvents 1.0 JSON structured; schema wire nằm trong [`schemas/`](schemas/)
và topic/message nằm trong [AsyncAPI](asyncapi.yaml).

## Topic và consumer

| Topic | Consumer được phép | Mục đích |
|---|---|---|
| `t.user.events` | Chat Service | Đồng bộ `sender_name` tối thiểu cho projection chat. |
| `t.user.events` | Notification Service | Đồng bộ ba notification preference. |

Kafka key là `user_id`; thứ tự chỉ được cam kết trong cùng `user_id`. Consumer
phải xử lý delivery trùng và chỉ áp dụng update mới hơn theo `updated_at`.

`USER_DELETED` không thuộc contract này. Auth Service là nguồn chân lý cho việc
xóa account và phát event đó trực tiếp cho User, Notification, Review, Chat và Cart
trên [`Auth Events v1`](../../auth/v1/asyncapi.yaml).

## Dữ liệu và độ tin cậy

`USER_PROFILE_UPDATED` chỉ chứa `user_id`, `sender_name` và `updated_at`; không
đưa ngày sinh, giới tính, phone hay address vào Kafka. `USER_PREFERENCES_UPDATED`
chỉ chứa ba flag được Notification replicate; `language=vi` và `currency=VND`
không được publish.

User publish trực tiếp sau PostgreSQL commit với retry có giới hạn và không dùng
outbox. Vì vậy, khi retry cạn, event có thể mất và reconciliation vận hành là bắt
bắt buộc; contract không tuyên bố exactly-once hoặc durable delivery. Một job hằng
ngày ngoài giờ quét profile/preferences active và phát corrective current snapshot
với CloudEvent `id` mới, giữ nguyên `updated_at`; không republish `USER_DELETED`
và không tự động redrive DLQ.

Thêm field optional là tương thích. Bất kỳ thay đổi breaking nào với payload,
Kafka key, ordering scope hoặc event type phải tạo major version mới.
