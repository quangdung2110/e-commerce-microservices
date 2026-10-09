---
doc_id: auth-interfaces
service: auth
scope: current
status: implemented
owners:
  - Auth Service team
evidence:
  - contracts/http/auth/v1/openapi.yaml
  - contracts/http/auth/internal/v1/openapi.yaml
  - contracts/events/auth/v1/asyncapi.yaml
  - go/services/auth-service/internal/api/router
  - go/services/auth-service/internal/api/httpx/problem.go
  - go/services/auth-service/test/integration/http_flow_test.go
verified_against_commit: null
---

# Auth Service — Giao diện tích hợp

> File này giải thích ngữ nghĩa. [OpenAPI v1](../../contracts/http/auth/v1/openapi.yaml)
> là nguồn chuẩn cho HTTP public; [Internal OpenAPI v1](../../contracts/http/auth/internal/v1/openapi.yaml)
> cho listener internal; [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml)
> là nguồn chuẩn cho event Kafka. Runtime đã implement cả ba; trạng thái contract
> trong cột cuối theo [ADOPTION](../../contracts/ADOPTION.md).

## 1. Danh mục contract

Hướng tính theo Auth: *Inbound* là lời gọi đi vào Auth, *Outbound* là thứ Auth phát đi.

| Tương tác | Hướng | Giao thức | Provider | Consumer | Contract | Trạng thái contract |
|---|---|---|---|---|---|---|
| API xác thực và session | Inbound | HTTP | Auth | Client/Gateway | [OpenAPI v1](../../contracts/http/auth/v1/openapi.yaml) | proposed |
| JWKS | Inbound | HTTP | Auth | API Gateway (`gateway-auth`) | [OpenAPI v1](../../contracts/http/auth/v1/openapi.yaml) `GET /.well-known/jwks.json` | proposed |
| Identity event | Outbound | Kafka `t.auth.events` | Auth | User (`USER_VERIFIED_SUCCESS`, `USER_DELETED`); Notification, Review, Chat, Cart (`USER_DELETED`) | [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml) | proposed |
| Security notification request | Outbound | Kafka `t.auth.notification.events` | Auth | Notification | [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml) | proposed |
| Resolve notification delivery | Inbound | Internal HTTP (mesh mTLS) | Auth | Notification | [Internal OpenAPI v1](../../contracts/http/auth/internal/v1/openapi.yaml) `POST /internal/v1/auth/notification-deliveries/{delivery_id}/content` | proposed |
| Resolve notification contact | Inbound | Internal HTTP (mesh mTLS) | Auth | Notification | [Internal OpenAPI v1](../../contracts/http/auth/internal/v1/openapi.yaml) `GET /internal/v1/auth/users/{user_id}/notification-contact` | proposed |
| JWKS invalidation | Outbound | Redis Pub/Sub `auth:jwks:update` | Auth | API Gateway | Payload ở [Gateway runtime contract §6](../gateway/runtime-contract.md#6-quy-ước-xác-thực) | n/a |

## 2. Nhóm HTTP API

| Nhóm | Mục đích | Caller | Điều kiện trước / idempotency |
|---|---|---|---|
| Đăng ký và xác minh | Tạo account chờ xác minh; yêu cầu/xác minh OTP | Anonymous client | Có rate limit; OTP chỉ dùng một lần và chưa hết hạn; gửi lại OTP luôn `202` |
| Đăng nhập | Xác minh credential và cấp token pair | Anonymous client | Mật khẩu được kiểm trước trạng thái account; account phải `ACTIVE`; áp dụng lockout policy |
| Làm mới token | Rotate một refresh token và cấp token pair mới | Client | Một token cụ thể chỉ thành công một lần |
| Đăng xuất và session | Revoke session hiện tại/được chọn; liệt kê session của mình | Authenticated account owner | Không giới hạn số thiết bị; phân trang cursor; JWT `jti` hiện tại có thể vào denylist |
| Quản lý mật khẩu | Đổi mật khẩu hoặc bắt đầu/hoàn tất reset | Owner/anonymous reset flow | Xác minh bảo mật và thu hồi toàn bộ session; yêu cầu reset luôn `202` |
| Đổi email | Yêu cầu bằng mật khẩu hiện tại, sau đó xác nhận email mới qua OTP | Account owner | Thu hồi toàn bộ session sau khi đổi thành công |
| Xóa account | Yêu cầu GDPR hoặc admin deletion | Owner/authorized admin | Anonymize ngay; không hỗ trợ restore |

Path, authentication scheme, status code và schema request/response được xác định trong
OpenAPI; ma trận mã lỗi theo endpoint ở [§6](#6-error-catalog-và-ma-trận-response). Refresh
token dùng cookie `auth_refresh` với `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/sessions`;
access token chỉ xuất hiện trong JSON response và response chứa credential bắt buộc
`Cache-Control: no-store`. HTTP adapter ánh xạ domain error nhất quán theo
[Domain §7](./domain.md#7-domain-errors).

`session_id` trên HTTP là `family_id` ổn định, không phải ID của từng refresh-token
record thay đổi theo rotation. Khi thu hồi một session hiện tại, Gateway phải inject
`X-Token-Jti` đã được verify để Auth denylist access token đó; Auth nhận ra "session hiện tại"
bằng `X-User-Session-Id` (claim `sid`), và chỉ dùng cookie `auth_refresh` làm đường dự phòng
khi header này vắng (gọi trực tiếp trong mạng nội bộ). Các header identity/JTI không được phép
nhận trực tiếp từ public client.

Claim set của access token và ánh xạ claim sang identity header là contract chung
giữa Auth và Gateway, được chốt một chỗ duy nhất tại
[runtime-contract mục 6 và 6.1](../gateway/runtime-contract.md#6-quy-ước-xác-thực).
Không mô tả lại claim set ở file này: hai bản sao lệch nhau chính là nguyên nhân
gốc của sự cố identity header trước đây.

### Rate limit

Fixed window trong Redis, fail closed khi Redis lỗi (`500 DEPENDENCY_UNAVAILABLE`). Giá trị là
mặc định trong `configs/application.yaml`.

| Phạm vi | Subject | Mặc định | Khi vượt |
|---|---|---|---|
| `POST /registrations` | IP | 10 / 1 giờ | `429 RATE_LIMITED` |
| `POST /email-verification-requests`, `POST /email-verifications` | IP (dùng chung cấu hình đăng ký) | 10 / 1 giờ | `429` |
| `POST /sessions` | IP | 20 / 5 phút | `429` |
| Login theo account | email chuẩn hóa | 10 / 5 phút | `429` |
| `POST /sessions/refresh` | IP | 60 / 5 phút | `429` |
| `POST /password-reset-requests`, `POST /password-resets` | IP | 5 / 1 giờ | `429` |
| `POST /email-change-requests`, `POST /email-changes/confirmations` | account (`X-User-Id`) | 5 / 15 phút | `429` |
| Gửi lại OTP đăng ký (mỗi account) | account | 3 / 15 phút | Không gửi, vẫn `202` (không lộ account) |
| Yêu cầu reset (mỗi account) | account | 5 / 15 phút | Không gửi, vẫn `202` (không lộ account) |
| Gửi lại OTP đổi email (mỗi account) | account | 3 / 15 phút | `429` (caller đã đăng nhập) |
| Số lần nhập sai OTP | challenge | 5 | `OTP_INVALID`, challenge bị hủy |
| Listener internal | (service gọi, `delivery_id` hoặc `user_id`) | 120 / phút | `429` |

## 3. Event phát ra

Kafka key là `user_id`; thứ tự chỉ được cam kết trong cùng account. Auth publish
trực tiếp sau commit, retry có giới hạn (3 lần, cùng CloudEvent `id`) và không có outbox:
Kafka delivery sau khi publish là at-least-once, nhưng retry cạn có thể làm mất event
(chỉ ghi log `error`). Consumer phải deduplicate bằng CloudEvent `id` UUID ổn định và có
reconciliation vận hành. Auth common schema ràng buộc UUID bằng `format` và regex assertion
cho mọi `id`, để consumer lưu receipt UUID mà không cần ép kiểu hoặc có nhánh xử lý event
hợp lệ khác kiểu. Với xóa account, đổi email và đổi/reset mật khẩu, event được phát trước
khi ghi revocation marker Redis
([ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)).

| Event | Topic | Ý nghĩa / trigger | Consumer |
|---|---|---|---|
| `USER_OTP_REQUESTED` | `t.auth.notification.events` | Cần OTP để đăng ký hoặc xác minh email mới | Notification |
| `PASSWORD_RESET_REQUESTED` | `t.auth.notification.events` | Yêu cầu email có opaque reset-token link | Notification |
| `USER_VERIFIED_SUCCESS` | `t.auth.events` | Hoàn tất xác minh account | User |
| `USER_DELETED` | `t.auth.events` | Bắt đầu luồng xóa account/GDPR; payload có deadline retention | User, Notification, Review, Chat, Cart |
| `USER_EMAIL_CHANGED` | `t.auth.notification.events` | Đổi email thành công; cảnh báo gửi tới email cũ | Notification |
| `USER_PASSWORD_CHANGED` | `t.auth.notification.events` | Đổi hoặc reset password thành công | Notification |
| `USER_ACCOUNT_LOCKED` | `t.auth.notification.events` | Đạt ngưỡng failed login | Notification |
| `USER_UNLOCKED` | `t.auth.notification.events` | Lock hết hạn được lift: bởi worker, hoặc bởi login/refresh thành công đầu tiên (một lần) | Notification |

Schema, topic, retention, retry và compatibility policy nằm tại AsyncAPI. Event
notification chỉ có `delivery_id` cùng metadata: Notification gọi internal API
để lấy email và nội dung render. OTP, reset token, reset URL, password và email
không được đặt trong Kafka hoặc log.

Auth còn là **nguồn email duy nhất** cho mọi thông báo không do Auth khởi phát (Order,
Review, Cart): không event nào trong hệ thống mang email, nên Notification gọi
`GET /internal/v1/auth/users/{user_id}/notification-contact` lúc gửi. Response `no-store`,
Notification chỉ cache trong memory/Redis tối đa 15 phút và không ghi xuống DB; `404` khi
account không tồn tại hoặc đã xoá → Notification đánh delivery `FAILED (no_recipient)`.
Endpoint này chỉ trả `email`, `email_verified`, `locale?` — không secret, không số điện
thoại. Rate limit theo (service gọi, `user_id`); khi Notification xả backlog lớn, Auth không
được bị kéo theo (Notification tự giới hạn phía client theo lane, xem
[Notification 06](../notification/06-backpressure.md) §3).

### Listener internal

Cả hai internal endpoint được phục vụ trên **listener nội bộ riêng (cổng 8081)**, không
bao giờ qua Gateway, sau cùng một chuỗi middleware: allowlist `X-Service-Identity` rồi rate
limit theo (service gọi, `delivery_id` hoặc `user_id`) `security.rate_limit.internal_per_resource`.

Ai được chạm listener do **mesh** quyết định: Istio ambient kết thúc mTLS và thực thi
`AuthorizationPolicy` theo principal + path (`notification-service` cho đúng hai path này,
[mesh runtime contract §5.2](../mesh/runtime-contract.md)); Auth không nhìn thấy chứng chỉ
caller và không tự kết thúc TLS ([ADR-007](./decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md)).
Ứng dụng chỉ thêm defense-in-depth: thiếu `X-Service-Identity` là `401 UNAUTHORIZED`, giá trị ngoài
`internal.allowed_callers` (mặc định `notification-service`) là `403 FORBIDDEN`. Header này không phải
bằng chứng authorization duy nhất, và ở Docker Compose (không có mesh) nó không được xác thực.

Ánh xạ trạng thái account sang response của `notification-contact`:

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
theo `event_id`, cũng không có consumer lag hay DLQ phía Auth. (`t.auth.events.dlq` và
`t.auth.notification.events.dlq` là DLQ của *consumer* khi chúng không xử lý được event.)

## 5. JWKS invalidation

`JWKS_UPDATED` là Redis Pub/Sub signal nhẹ trên `auth:jwks:update` để Gateway
invalidate cache; payload hiện tại là `{"type":"all"}` theo
[Gateway runtime contract §6](../gateway/runtime-contract.md#6-quy-ước-xác-thực). Đây không phải
durable audit event: Gateway vẫn phải refresh JWKS khi gặp `kid` không biết và định kỳ theo cache
policy (`Cache-Control: public, max-age=600` ở response JWKS).

## 6. Error catalog và ma trận response

Danh mục đầy đủ (code, HTTP, retryable, điều kiện) nằm ở [Domain §7](./domain.md#7-domain-errors).
Ma trận dưới đây liệt kê các status mà từng endpoint có thể trả ngoài `500`
(mọi endpoint có thể trả `500` với `INTERNAL_ERROR`, `DEPENDENCY_UNAVAILABLE` hoặc
`CONCURRENT_UPDATE`).

| Endpoint | Thành công | Lỗi có thể gặp (status `CODE`) |
|---|---|---|
| `POST /api/v1/auth/registrations` | `202` | `400 VALIDATION_FAILED` (gồm policy mật khẩu), `409 EMAIL_ALREADY_REGISTERED`, `429` |
| `POST /api/v1/auth/email-verification-requests` | `202` luôn | `400`, `429` (chỉ theo IP) |
| `POST /api/v1/auth/email-verifications` | `204` | `400`, `409 ACCOUNT_NOT_ACTIVE`, `422 OTP_INVALID`, `429` |
| `POST /api/v1/auth/sessions` | `200` | `400`, `401 INVALID_CREDENTIALS`, `403 ACCOUNT_NOT_VERIFIED`, `423 ACCOUNT_LOCKED`, `429` |
| `POST /api/v1/auth/sessions/refresh` | `200` | `401 REFRESH_TOKEN_INVALID` (xóa cookie), `429` |
| `GET /api/v1/auth/sessions` | `200` | `400` (cursor/limit sai), `401 UNAUTHORIZED` |
| `DELETE /api/v1/auth/sessions` | `204` | `401` |
| `DELETE /api/v1/auth/sessions/{session_id}` | `204` | `400` (không phải UUID), `401`, `404 SESSION_NOT_FOUND` |
| `PUT /api/v1/auth/password` | `204` | `400`, `401 INVALID_CREDENTIALS`/`UNAUTHORIZED`, `422 PASSWORD_POLICY_VIOLATION` |
| `POST /api/v1/auth/password-reset-requests` | `202` luôn | `400`, `429` (chỉ theo IP) |
| `POST /api/v1/auth/password-resets` | `204` | `400` (gồm policy mật khẩu), `422 RESET_TOKEN_INVALID`, `429` |
| `POST /api/v1/auth/email-change-requests` | `202` | `400`, `401 INVALID_CREDENTIALS`/`UNAUTHORIZED`, `409 EMAIL_ALREADY_REGISTERED`, `429` |
| `POST /api/v1/auth/email-changes/confirmations` | `204` | `400`, `401 UNAUTHORIZED`, `409 ACCOUNT_NOT_ACTIVE`, `422 OTP_INVALID`, `429` |
| `POST /api/v1/auth/accounts/me/deletion-requests` | `202` | `400`, `401 INVALID_CREDENTIALS`/`UNAUTHORIZED`, `409 ACCOUNT_NOT_ACTIVE` |
| `POST /api/v1/auth/admin/accounts/{account_id}/deletion-requests` | `202` | `400`, `401`, `403 FORBIDDEN`, `404 ACCOUNT_NOT_FOUND`, `409 ACCOUNT_NOT_ACTIVE` |
| `GET /.well-known/jwks.json` | `200` | `500 SIGNING_KEY_MISSING` (chưa có key) |
| `POST /internal/v1/auth/notification-deliveries/{id}/content` | `200` | `400`, `401`, `403`, `404 DELIVERY_NOT_FOUND`, `410 DELIVERY_EXPIRED`, `429` |
| `GET /internal/v1/auth/users/{user_id}/notification-contact` | `200` | `400`, `401`, `403`, `404 ACCOUNT_NOT_FOUND`, `429` |

Tên mã thiếu identity là `UNAUTHORIZED` (Cart, Product, Promotion, Inventory và Checkout dùng
`UNAUTHENTICATED`); chọn một tên chung là việc xuyên service chưa làm (audit X3).

## 7. Compatibility

Ưu tiên bổ sung optional field; không được xóa field/enum hoặc thay đổi ngữ nghĩa
khi consumer còn được hỗ trợ. Các contract Auth vẫn `proposed` trong ADOPTION: còn thiếu test
tự động validate response với OpenAPI và payload event với JSON Schema, consumer test từ
Gateway/Notification thật, deprecation window và CI compatibility check trước khi đạt
`implemented` hoặc `verified`.
