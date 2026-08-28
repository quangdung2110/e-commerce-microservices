---
doc_id: user-interfaces
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence:
  - contracts/http/user/v1/openapi.yaml
  - contracts/events/user/v1/asyncapi.yaml
verified_against_commit: null
---

# User Service — Giao diện tích hợp

> Tài liệu giải thích semantics. [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml)
> là nguồn chuẩn cho HTTP; [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml)
> là nguồn chuẩn cho event Kafka do User phát.

## 1. Danh mục hợp đồng

| Tương tác | Hướng | Giao thức | Provider | Consumer | Contract | Trạng thái |
|---|---|---|---|---|---|---|
| API profile/address/preferences | Đầu vào | HTTP | User | Client/API Gateway | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) | proposed |
| Checkout address snapshot | Đầu ra | Internal HTTP/mTLS | User | Checkout | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) | proposed |
| Identity đã xác minh | Đầu vào | Kafka | Auth | User | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) | proposed |
| Xóa account | Đầu vào | Kafka | Auth | User | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) | proposed |
| Profile/preference event | Đầu ra | Kafka | User | Chat, Notification | [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) | proposed |

## 2. HTTP API

Gateway xác thực caller và chuyển identity đáng tin cậy tới User API. Public
client không được gửi `user_id` hay trusted identity header trực tiếp. OpenAPI v1
là contract HTTP chuẩn; mọi public response chứa PII trả `Cache-Control: private,
no-store` và mọi error dùng `application/problem+json` theo RFC 9457.

| Nhóm | Mục đích | Caller | Điều kiện trước / hành vi |
|---|---|---|---|
| `GET /api/v1/users/me/profile` | Đọc profile của caller | Chủ sở hữu đã xác thực | Profile phải tồn tại; không cache theo thiết kế |
| `PATCH /api/v1/users/me/profile` | Cập nhật một phần profile | Chủ sở hữu đã xác thực | JSON Merge Patch; không có phone; phát event sau commit |
| `GET /api/v1/users/me/preferences` | Đọc preferences | Chủ sở hữu đã xác thực | `language=vi`, `currency=VND` là read-only |
| `GET /api/v1/users/me/addresses` | Đọc address book checkout | Chủ sở hữu đã xác thực | Cache-aside, chỉ trả address của owner |
| `POST /api/v1/users/me/addresses` | Tạo address | Chủ sở hữu đã xác thực | Address đầu tiên thành default |
| `PUT /api/v1/users/me/addresses/{address_id}` | Cập nhật address | Chủ sở hữu đã xác thực | Lookup phải chứa owner `user_id` |
| `PUT /api/v1/users/me/addresses/{address_id}/default` | Đặt address default | Chủ sở hữu đã xác thực | Atomic và idempotent; tối đa một default |
| `DELETE /api/v1/users/me/addresses/{address_id}` | Hard-delete address | Chủ sở hữu đã xác thực | Không xóa address đang là default |
| `PUT /api/v1/users/me/preferences` | Cập nhật preferences | Chủ sở hữu đã xác thực | Validate enum/flag; phát event sau commit |
| `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` | Resolve address snapshot | Checkout qua mTLS | Xác minh ownership trước khi trả province/district/ward snapshot |

`PATCH`, `PUT` và `DELETE` idempotent khi gửi lại cùng trạng thái mong muốn.
`POST /addresses` không có idempotency key hoặc storage ở v1 nên client không
được retry mù quáng sau timeout. `ADDRESS_NOT_FOUND` trả 404 cho cả address không
tồn tại và address không thuộc owner. Internal snapshot endpoint chỉ dành cho
Checkout trên network nội bộ qua mTLS, không được public Gateway expose.

## 3. Event phát ra

Thiết kế mục tiêu dùng topic `t.user.events`, key/partition key `user_id`; thứ tự
chỉ được cam kết trong cùng một user. Producer phát trực tiếp sau PostgreSQL
commit theo [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml);
delivery cần giả định at-least-once. Không được xem direct publish là reliable
tuyệt đối; xem [ADR-004](./decisions/ADR-004-direct-event-publication.md).

| Event | Trigger và ngữ nghĩa | Consumer dự kiến |
|---|---|---|
| `USER_PROFILE_UPDATED` | Profile update đã commit; payload chỉ có `user_id`, `sender_name` và `updated_at` | Chat |
| `USER_PREFERENCES_UPDATED` | Preferences update đã commit; payload chỉ có ba flag Notification replicate và `updated_at` | Notification |

`USER_DELETED` không được User republish. Auth là nguồn chân lý của việc xóa
account và phát event đó trực tiếp cho User, Notification, Review và Chat; xem
[ADR-006](./decisions/ADR-006-auth-owns-account-deletion-event.md). Event User
không chứa credential, email do Auth sở hữu, raw address, ngày sinh, giới tính
hoặc PII không cần cho consumer. Chat và Notification chỉ áp dụng update mới hơn
theo `updated_at` và chịu được delivery trùng.

## 4. Event tiêu thụ

| Event | Producer/topic | Hành động | Idempotency và xử lý lỗi |
|---|---|---|---|
| `USER_VERIFIED_SUCCESS` | Auth / `t.auth.events` | Tạo profile rỗng và preferences default | PK/FK theo `user_id`; duplicate là no-op; retry/DLQ policy cần chốt |
| `USER_DELETED` | Auth / `t.auth.events` | Tạo retention record, anonymize PII và xóa address/preferences | Unique deletion `user_id`; duplicate không kéo dài retention hoặc phát event mới |

User chỉ sử dụng `user_id` và field business đã được Auth contract định nghĩa;
không lưu email. Consumer phải chịu được unknown optional field, từ chối/đưa DLQ
version không hỗ trợ, giữ ordering theo user và chỉ acknowledge sau state change
thành công. Consumer group, retry backoff, max attempt và DLQ topic chưa có
contract/configuration được xác minh.

## 5. Tương thích

HTTP và event contract ưu tiên thêm field optional; không xóa/đổi nghĩa field,
enum hoặc event type khi consumer cũ còn hỗ trợ. Breaking change phải tạo version
mới, có deprecation window và compatibility check trong CI. Trạng thái chỉ nâng
lên `implemented`/`verified` khi có OpenAPI/AsyncAPI, contract test và evidence.
