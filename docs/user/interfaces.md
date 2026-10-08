---
doc_id: user-interfaces
service: user
scope: current
status: implemented
owners:
  - User Service team
evidence:
  - contracts/http/user/v1/openapi.yaml
  - contracts/events/user/v1/asyncapi.yaml
  - go/services/user-service/internal/api/router
  - go/services/user-service/internal/infrastructure/kafka/consumer.go
  - go/services/user-service/test/integration/http_flow_test.go
  - go/services/user-service/test/integration/internal_flow_test.go
verified_against_commit: null
---

# User Service — Giao diện tích hợp

> Tài liệu giải thích semantics. [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml)
> là nguồn chuẩn cho HTTP; [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml)
> là nguồn chuẩn cho event Kafka do User phát.

## 1. Danh mục hợp đồng

Cột trạng thái là trạng thái **runtime** (`implemented` = provider/consumer đã có code và
test); metadata contract (`x-adoption-status`) vẫn là `proposed` cho tới khi có contract
test tự động.

| Tương tác | Hướng | Giao thức | Provider | Consumer | Contract | Trạng thái |
|---|---|---|---|---|---|---|
| API profile/address/preferences | Đầu vào | HTTP | User | Client/API Gateway | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) | implemented |
| Checkout address snapshot | Đầu vào (listener internal `:8083`) | HTTP; mTLS do mesh thực thi | User | Checkout | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) | implemented |
| Identity đã xác minh | Đầu vào | Kafka | Auth | User | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) | implemented |
| Xóa account | Đầu vào | Kafka | Auth | User | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) | implemented |
| Profile/preference event | Đầu ra | Kafka | User | Chat, Notification | [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) | implemented (chưa xác minh phía Chat) |
| Complaint từ Notification | Đầu vào | Kafka | Notification | User | [Notification AsyncAPI v1](../../contracts/events/notification/v1/asyncapi.yaml) `com.ecommerce.notification.complaint-received.v1` | implemented ([ADR-009](./decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md)) |

## 2. HTTP API

Gateway xác thực caller và chuyển identity đáng tin cậy tới User API. Public
client không được gửi `user_id` hay trusted identity header trực tiếp. OpenAPI v1
là contract HTTP chuẩn; mọi public response chứa PII trả `Cache-Control: private,
no-store` và mọi error dùng `application/problem+json` theo RFC 9457.

| Nhóm | Mục đích | Caller | Điều kiện trước / hành vi |
|---|---|---|---|
| `GET /api/v1/users/me/profile` | Đọc profile của caller | Chủ sở hữu đã xác thực | Profile phải tồn tại và không trong retention window (nếu không `404`); không cache |
| `PATCH /api/v1/users/me/profile` | Cập nhật một phần profile | Chủ sở hữu đã xác thực | JSON Merge Patch (`application/merge-patch+json`); field lạ như `display_name` là `400`; không có phone; phát event sau commit chỉ khi có thay đổi thật |
| `GET /api/v1/users/me/preferences` | Đọc preferences | Chủ sở hữu đã xác thực | `language=vi`, `currency=VND` là read-only; `404` khi không còn preferences (account đã xóa) |
| `GET /api/v1/users/me/addresses` | Đọc address book checkout | Chủ sở hữu đã xác thực | Cache-aside, chỉ trả address của owner, tối đa 50; `200` với danh sách rỗng khi chưa có address |
| `POST /api/v1/users/me/addresses` | Tạo address | Chủ sở hữu đã xác thực | Address đầu tiên thành default; address thứ 51 là `422 INVALID_ADDRESS` |
| `PUT /api/v1/users/me/addresses/{address_id}` | Cập nhật address | Chủ sở hữu đã xác thực | Lookup phải chứa owner `user_id`; không đổi cờ default |
| `PUT /api/v1/users/me/addresses/{address_id}/default` | Đặt address default | Chủ sở hữu đã xác thực | Atomic và idempotent; tối đa một default |
| `DELETE /api/v1/users/me/addresses/{address_id}` | Hard-delete address | Chủ sở hữu đã xác thực | Default chỉ xóa được khi là address duy nhất còn lại; nếu không `409` |
| `PUT /api/v1/users/me/preferences` | Cập nhật preferences | Chủ sở hữu đã xác thực | Ghi cả ba cờ; field lạ là `400`; phát event sau commit chỉ khi có thay đổi thật |
| `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` | Resolve address snapshot | Checkout, listener `:8083` | Xác minh ownership trước khi trả province/district/ward snapshot; không đọc cache; `401`/`403` theo `X-Service-Identity` |

Mọi route public có rate limit theo identity (đọc 120/phút, ghi 30/phút mặc định;
`429 RATE_LIMITED` kèm `Retry-After`). `PATCH`, `PUT` và `DELETE` idempotent khi gửi lại
cùng trạng thái mong muốn. `POST /addresses` không có idempotency key hoặc storage ở v1
nên client không được retry mù quáng sau timeout. `ADDRESS_NOT_FOUND` trả 404 cho cả
address không tồn tại và address không thuộc owner.

Internal snapshot endpoint chỉ dành cho Checkout trên network nội bộ, không được public
Gateway expose. Service mesh kết thúc mTLS và chỉ cho principal `checkout-service` chạm
path này; User không nhìn thấy chứng chỉ caller. Ứng dụng kiểm thêm `X-Service-Identity`
(bắt buộc, phải nằm trong `internal.allowed_callers`) như defense-in-depth, và rate limit
theo cặp (service gọi, `user_id`), mặc định 120/phút
([ADR-008](./decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md)). Checkout coi
`429` của User là lỗi phụ thuộc, không phải kết quả nghiệp vụ.

## 3. Event phát ra

User dùng topic `t.user.events`, key/partition key `user_id`; thứ tự chỉ được cam
kết trong cùng một user. Producer phát trực tiếp sau PostgreSQL commit (retry tối đa 3
lần với cùng CloudEvent `id`) theo [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml);
delivery cần giả định at-least-once. Không được xem direct publish là reliable
tuyệt đối; xem [ADR-004](./decisions/ADR-004-direct-event-publication.md).

| Event | Trigger và ngữ nghĩa | Consumer |
|---|---|---|
| `USER_PROFILE_UPDATED` | Profile update đã commit và có thay đổi thật; payload chỉ có `user_id`, `sender_name` (tên hiển thị suy ra, `Người dùng` khi chưa có tên) và `updated_at` | Chat |
| `USER_PREFERENCES_UPDATED` | Preferences thay đổi thật: owner `PUT /preferences`, hoặc User tắt `marketing_enabled` sau complaint; payload chỉ có ba flag Notification replicate và `updated_at`. Không phát lúc bootstrap | Notification |

`USER_DELETED` không được User republish. Auth là nguồn chân lý của việc xóa
account và phát event đó trực tiếp cho User, Notification, Review, Chat và Cart; xem
[ADR-006](./decisions/ADR-006-auth-owns-account-deletion-event.md). Event User
không chứa credential, email do Auth sở hữu, raw address, ngày sinh, giới tính
hoặc PII không cần cho consumer. Chat và Notification chỉ áp dụng update mới hơn
theo `updated_at` và chịu được delivery trùng.

## 4. Event tiêu thụ

| Event | Producer/topic | Hành động | Idempotency và xử lý lỗi |
|---|---|---|---|
| `USER_VERIFIED_SUCCESS` | Auth / `t.auth.events` | Tạo profile rỗng và preferences default (marketing tắt); bỏ qua nếu user đã có retention record | PK/FK theo `user_id`; duplicate là no-op và không ghi đè chỉnh sửa |
| `USER_DELETED` | Auth / `t.auth.events` | Tạo retention record, anonymize PII và xóa address/preferences | Unique deletion `user_id`; duplicate không kéo dài retention hoặc phát event mới |
| `com.ecommerce.notification.complaint-received.v1` | Notification / `t.notification.outcomes.v1` | Đặt `marketing_enabled = false` cho `user_id` rồi phát `USER_PREFERENCES_UPDATED` | Idempotent: cờ đã `false`, user không có preferences hoặc đã xóa thì no-op, không phát event; Notification đã tắt ở bản replicate cục bộ nên đây là đồng bộ nguồn sự thật, không phải điều kiện để ngừng gửi. Các outcome khác (`delivered`, `bounced`, `failed`) được ack không xử lý |

Cả hai topic dùng chung một consumer group `user-service`; offset commit chỉ sau khi
state change đã bền. Lỗi tạm (`DEPENDENCY_UNAVAILABLE`, `INTERNAL_ERROR`) được retry tại
chỗ tối đa 10 lần, backoff 1s nhân đôi tới 5 phút, giữ nguyên ordering theo user; lỗi
vĩnh viễn (không giải mã được, sai `source`, `user_id` không phải UUID) hoặc hết lượt
retry vào DLQ của topic nguồn: `t.auth.events.dlq` cho Auth và
`t.notification.outcomes.v1.dlq` cho Notification. Hai DLQ tách riêng để redrive không
trộn producer; không có redrive tự động. Event có `type` hoặc field optional lạ được
bỏ qua thay vì làm hỏng consumer.

User chỉ sử dụng `user_id` và field business đã được Auth contract định nghĩa;
không lưu email. Ba cờ preference là nguồn sự thật cho Notification (replicate qua
`USER_PREFERENCES_UPDATED`); khi chưa có preferences thì cả User lẫn Notification coi
email/in-app bật, marketing tắt ([Notification 08](../notification/08-decide.md) §5,
[ADR-009](./decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md)). Notification
chưa nhận timezone/locale từ User; khi User bổ sung vào payload preference, Notification
sẽ dùng cho quiet hours và i18n.

## 5. Tương thích

HTTP và event contract ưu tiên thêm field optional; không xóa/đổi nghĩa field,
enum hoặc event type khi consumer cũ còn hỗ trợ. Breaking change phải tạo version
mới, có deprecation window và compatibility check trong CI. Trạng thái chỉ nâng
lên `implemented`/`verified` khi có OpenAPI/AsyncAPI, contract test và evidence.
