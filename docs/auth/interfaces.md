---
doc_id: auth-interfaces
service: auth
scope: target
status: proposed
owners:
  - Auth Service team
evidence: []
verified_against_commit: null
---

# Auth Service — Giao diện tích hợp

> File này giải thích ngữ nghĩa. [OpenAPI v1](../../contracts/http/auth/v1/openapi.yaml)
> là nguồn chuẩn cho HTTP public; [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml)
> là nguồn chuẩn cho event Kafka.

## 1. Danh mục contract

| Tương tác | Hướng | Giao thức | Provider | Consumer | Contract | Trạng thái |
|---|---|---|---|---|---|---|
| API xác thực và session | Inbound | HTTP | Auth | Client/Gateway | [OpenAPI v1](../../contracts/http/auth/v1/openapi.yaml) | proposed |
| JWKS | Outbound | HTTP | Auth | API Gateway | [OpenAPI v1](../../contracts/http/auth/v1/openapi.yaml) | proposed |
| Identity event | Outbound | Kafka | Auth | User, Notification, Review, Chat, Cart | [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml) | proposed |
| Security notification request | Outbound | Kafka | Auth | Notification | [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml) | proposed |
| Resolve notification delivery | Inbound | Internal HTTP/mTLS | Auth | Notification | [Internal OpenAPI v1](../../contracts/http/auth/internal/v1/openapi.yaml) | proposed |
| Resolve notification contact | Inbound | Internal HTTP/mTLS | Auth | Notification | [Internal OpenAPI v1](../../contracts/http/auth/internal/v1/openapi.yaml) `GET /internal/v1/auth/users/{user_id}/notification-contact` | proposed |
| JWKS invalidation | Outbound | Redis Pub/Sub | Auth | API Gateway | Cần tạo: schema | proposed |

## 2. Nhóm HTTP API

| Nhóm | Mục đích | Caller | Điều kiện trước / idempotency |
|---|---|---|---|
| Đăng ký và xác minh | Tạo account chờ xác minh; yêu cầu/xác minh OTP | Anonymous client | Có rate limit; OTP chỉ dùng một lần và chưa hết hạn |
| Đăng nhập | Xác minh credential và cấp token pair | Anonymous client | Account phải `ACTIVE`; áp dụng lockout policy |
| Làm mới token | Rotate một refresh token và cấp token pair mới | Client | Một token cụ thể chỉ thành công một lần |
| Đăng xuất và session | Revoke session hiện tại/được chọn; liệt kê session của mình | Authenticated account owner | Không giới hạn số thiết bị; phân trang cursor; JWT `jti` hiện tại có thể vào denylist |
| Quản lý mật khẩu | Đổi mật khẩu hoặc bắt đầu/hoàn tất reset | Owner/anonymous reset flow | Xác minh bảo mật và chính sách revoke session |
| Đổi email | Yêu cầu bằng mật khẩu hiện tại, sau đó xác nhận email mới qua OTP | Account owner | Thu hồi toàn bộ session sau khi đổi thành công |
| Xóa account | Yêu cầu GDPR hoặc admin deletion | Owner/authorized admin | Anonymize ngay; không hỗ trợ restore |

Path, authentication scheme, status code/error mapping và schema request/response
được xác định trong OpenAPI. Refresh token dùng cookie `auth_refresh` với
`HttpOnly; Secure; SameSite=Strict`; access token chỉ xuất hiện trong JSON response
và token response bắt buộc `Cache-Control: no-store`. HTTP adapter phải ánh xạ
nhất quán domain error tại [Domain](./domain.md).

`session_id` trên HTTP là `family_id` ổn định, không phải ID của từng refresh-token
record thay đổi theo rotation. Khi thu hồi một session hiện tại, Gateway phải inject
`X-Token-Jti` đã được verify để Auth denylist access token đó; các header identity/JTI
không được phép nhận trực tiếp từ public client.

Claim set của access token và ánh xạ claim sang identity header là contract chung
giữa Auth và Gateway, được chốt một chỗ duy nhất tại
[runtime-contract mục 6 và 6.1](../gateway/runtime-contract.md#6-quy-ước-xác-thực).
Không mô tả lại claim set ở file này: hai bản sao lệch nhau chính là nguyên nhân
gốc của sự cố identity header trước đây.

## 3. Event phát ra

Kafka key là `user_id`; thứ tự chỉ được cam kết trong cùng account. Auth publish
trực tiếp sau commit, retry có giới hạn và không có outbox: Kafka delivery sau
khi publish là at-least-once, nhưng retry cạn có thể làm mất event. Consumer phải
deduplicate bằng CloudEvent `id` UUID ổn định và có reconciliation vận hành. Auth
common schema ràng buộc UUID bằng `format` và regex assertion cho mọi `id`, để
consumer lưu receipt UUID mà không cần ép kiểu hoặc có nhánh xử lý event hợp lệ khác
kiểu.

| Event | Ý nghĩa / trigger | Consumer dự kiến |
|---|---|---|
| `USER_OTP_REQUESTED` | Cần OTP để đăng ký hoặc xác minh email mới | Notification |
| `PASSWORD_RESET_REQUESTED` | Yêu cầu email có opaque reset-token link | Notification |
| `USER_VERIFIED_SUCCESS` | Hoàn tất xác minh account | User |
| `USER_DELETED` | Bắt đầu luồng xóa account/GDPR; payload có deadline retention | User, Notification, Review, Chat, Cart |
| `USER_EMAIL_CHANGED` | Đổi email thành công | Notification |
| `USER_PASSWORD_CHANGED` | Đổi password thành công | Notification |
| `USER_ACCOUNT_LOCKED` | Đạt ngưỡng failed login | Notification |
| `USER_UNLOCKED` | Hoàn tất tự mở khóa bởi worker | Notification |

Schema, topic, retention, retry và compatibility policy nằm tại AsyncAPI. Event
notification chỉ có `delivery_id` cùng metadata: Notification gọi internal API
qua mTLS để lấy email và nội dung render. OTP, reset token, reset URL, password
và email không được đặt trong Kafka hoặc log.

Auth còn là **nguồn email duy nhất** cho mọi thông báo không do Auth khởi phát (Order,
Review, Cart): không event nào trong hệ thống mang email, nên Notification gọi
`GET /internal/v1/auth/users/{user_id}/notification-contact` lúc gửi. Response `no-store`,
Notification chỉ cache trong memory/Redis tối đa 15 phút và không ghi xuống DB; `404` khi
account không tồn tại hoặc đã xoá → Notification đánh delivery `FAILED (no_recipient)`.
Endpoint này chỉ trả `email`, `email_verified`, `locale?` — không secret, không số điện
thoại. Rate limit riêng cho principal `notification-service`; khi Notification xả backlog
lớn, Auth không được bị kéo theo (Notification tự giới hạn phía client theo lane, xem
[Notification 06](../notification/06-backpressure.md) §3).

Cả hai internal endpoint được phục vụ trên **listener nội bộ riêng (cổng 8081)**, không
bao giờ qua Gateway, sau cùng một chuỗi middleware: service identity (mTLS, bắt buộc ở
production) rồi rate limit theo client `security.rate_limit.internal_per_client`. Ánh xạ
trạng thái account sang response của `notification-contact`:

| Trạng thái account | Response |
|---|---|
| `PENDING_VERIFICATION` | `200`, `email_verified = false` — vẫn là người nhận cho thông báo giao dịch của chính họ; Notification không gửi bulk |
| `ACTIVE`, `LOCKED` | `200`, `email_verified = true` — khoá tài khoản không đổi chủ hộp thư |
| `DELETED` (đã anonymize theo AUTH-BR-007) hoặc không tồn tại | `404 ACCOUNT_NOT_FOUND` — hai trường hợp không phân biệt được với caller vì email đã bị xoá ngay lúc anonymize |

`locale` luôn có mặt trong body và là `null` ở phase 1 vì Auth không lưu locale;
Notification dùng mặc định của mình. Email không bao giờ xuất hiện trong log (access log
chỉ ghi route và status) hay trong body của problem response.

`USER_DELETED` được Notification consume để xoá preference/inbox và anonymize lịch sử
gửi trong 24 h ([Notification 09](../notification/09-data-model.md) §10).

## 4. Event tiêu thụ

Auth hiện không tiêu thụ business event. Các thao tác quản trị được thực hiện qua
HTTP API có authorization `ADMIN`; vì vậy không cần inbox/deduplication storage
theo `event_id`.

## 5. JWKS invalidation

`JWKS_UPDATED` là Redis Pub/Sub signal nhẹ trên `auth:jwks:update` để Gateway
invalidate cache. Đây không phải durable audit event: Gateway vẫn phải refresh
JWKS khi gặp `kid` không biết và định kỳ theo cache policy.

## 6. Compatibility

Ưu tiên bổ sung optional field; không được xóa field/enum hoặc thay đổi ngữ nghĩa
khi consumer còn được hỗ trợ. OpenAPI v1 là `proposed`; cần lint, contract test,
deprecation window và CI compatibility check trước khi contract đạt `implemented`
hoặc `verified`.
