# Interfaces

**Trạng thái:** `implemented` (phase 1) — provider đã chạy theo các contract dưới đây; contract vẫn `x-adoption-status: proposed` (ADOPTION.md ghi khoảng cách: chưa có consumer contract test).

## 1. Bản đồ

| Bề mặt | Chiều | Contract | Ai gọi / ai nghe | Đi qua |
|---|---|---|---|---|
| Request lane (3 topic) | Vào | [AsyncAPI Notification v1](../../contracts/events/notification/v1/asyncapi.yaml) `notificationCritical/Transactional/Bulk` | Order (transactional); critical/bulk chưa có producer | Kafka |
| Request từ Auth | Vào | [AsyncAPI Auth v1](../../contracts/events/auth/v1/asyncapi.yaml) `authNotificationEvents` | Auth | Kafka (`t.auth.notification.events`, consumer group `notification-auth-critical`, D-19) |
| Request từ Review | Vào | [AsyncAPI Review v1](../../contracts/events/review/v1/asyncapi.yaml) | Review | Kafka (`t.review.events`) |
| Fact Cart | Vào | [AsyncAPI Cart v1](../../contracts/events/cart/v1/asyncapi.yaml) | Cart | Kafka (`t.cart.events`) |
| Preference replication | Vào | [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) | User | Kafka (`t.user.events`) |
| Account deleted | Vào | AsyncAPI Auth v1 `USER_DELETED` | Auth | Kafka (`t.auth.events`) |
| Outcomes | Ra | AsyncAPI Notification v1 `notificationOutcomes` | Analytics, User | Kafka (`t.notification.outcomes.v1`) |
| Resolve content (Auth request) | Ra | [Auth internal OpenAPI](../../contracts/http/auth/internal/v1/openapi.yaml) `POST .../notification-deliveries/{id}/content` | Notification → Auth | HTTP trong mesh (mTLS do mesh), `X-Service-Identity` |
| Resolve email (mọi nguồn khác) | Ra | Auth internal OpenAPI `GET .../users/{user_id}/notification-contact` | Notification → Auth | HTTP trong mesh (mTLS do mesh), `X-Service-Identity` |
| SES send / events | Ra / Vào | AWS SES API; SNS → SQS | Notification ↔ AWS | IAM |
| Inbox in-app | Vào | [Notification OpenAPI v1](../../contracts/http/notification/v1/openapi.yaml) | Frontend qua Gateway | HTTPS, `gatewayIdentity` |
| Status lookup | Vào | [Notification internal OpenAPI](../../contracts/http/notification/internal/v1/openapi.yaml) `Status` | Order admin UI, finance-ops | mesh + allowlist `X-Service-Identity` |
| Ops | Vào | Notification internal OpenAPI `Ops` | `notification-ops` (SRE) | mesh + allowlist `X-Service-Identity`, không qua Gateway |

Catalog template: [templates/catalog.yaml](../../contracts/events/notification/v1/templates/catalog.yaml). Interaction đăng ký trong [interactions.yaml](../../contracts/catalog/interactions.yaml); ma trận mesh ở `docs/mesh/runtime-contract.md` §5.2; route Gateway ở `docs/gateway/runtime-contract.md`.

## 2. Kafka vào: `notification.requested.v1`

Ba channel cùng một message type; lane được xác định bởi topic và phải khớp `lane` của template trong catalog (sai → `FAILED (lane_mismatch)`).

| Envelope | Quy tắc |
|---|---|
| `id` | UUID; producer retry phải dùng lại cùng `id` (dedup lớp A) |
| `source` | `/services/<producer>`; phải nằm trong `x-access-policy` của channel |
| `subject` | Aggregate mà thông báo nói về (`orders/1000234`); **là Kafka message key** — ordering theo aggregate, tránh hot key theo user |
| `correlationid` | Bắt buộc; nối với flow gốc (checkout/order) |
| `sequence` | Bắt buộc, integer `>=1` cho `/services/order`; optional cho producer khác. Notification dùng để phát hiện collision/superseded request |

| `data` | Quy tắc |
|---|---|
| `template` | Enum từ catalog, chỉ `active` |
| `user_id` | UUID; Notification resolve email từ đây lúc gửi |
| `dedup_key` | Theo công thức của template; `required` với mọi template Order; unique theo `(dedup_key, channel)` 30 ngày |
| `expires_at` | Tuỳ chọn; chỉ rút ngắn `max_age`, không kéo dài |
| `template_data` | `oneOf` theo `template`; schema tại `templates/<ID>.schema.json`; **không email, không số điện thoại, không địa chỉ chi tiết, không secret** |

Schema đã được kiểm tra chặn: sai `template` cho `template_data`, thiếu `dedup_key`, field lạ trong `template_data` (ví dụ `email`), sai định dạng `dedup_key`, template không tồn tại, thiếu `subject`.

Ví dụ đầy đủ: [notification-requested.v1.json](../../contracts/examples/events/notification-requested.v1.json).

### Ba consumer request khác nhau ở cách đọc payload

| Nguồn | Consumer đọc gì | Ánh xạ |
|---|---|---|
| Order | Đúng schema trên | Trực tiếp |
| Review | `reviewReplyNotificationRequested` / `reviewRemovalNotificationRequested` | → `REVIEW_REPLIED` / `REVIEW_REMOVED`; `recipient_user_id` → `user_id`; `dedup_key` sinh theo công thức; `review_url` do Notification lắp = `{frontend.base_url}/reviews/{review_id}` (trang đánh giá của chính tác giả; contract Review không mang URL — ngoại lệ thứ hai cùng loại với `cart_url`, [07-templates.md](./07-templates.md) §2) |
| Auth | `delivery_id` + metadata | **Template gán ngay ở Ingest** từ CloudEvents `type` (+ `data.purpose` với OTP): `user-otp-requested/REGISTRATION_VERIFICATION` → `REGISTRATION_VERIFICATION`, `…/EMAIL_CHANGE_VERIFICATION` → `EMAIL_CHANGE_VERIFICATION`, `password-reset-requested` → `PASSWORD_RESET`, `user-email-changed` → `EMAIL_CHANGED`, `user-password-changed` → `PASSWORD_CHANGED`, `user-account-locked` → `ACCOUNT_LOCKED`, `user-unlocked` → `ACCOUNT_UNLOCKED`. `source_ref = delivery_id`. Bốn template cảnh báo lấy `template_data` (`changed_at`, `locked_at`, …) từ chính event; ba template OTP/reset không lưu `template_data` (secret) và `expires_at` lấy từ event. Email + secret resolve ở Render qua `content` API cho kênh email; kênh in-app của 4 cảnh báo render từ dữ liệu đã lưu, không gọi Auth |

## 3. Kafka ra: `t.notification.outcomes.v1`

Một topic, bốn type, key `data.user_id`, publish qua outbox (Debezium, hoặc poller của chính service khi `outbox.publisher = poller` — cùng schema bảng), `id` sinh lúc ghi outbox. `data` lõi chung (`delivery_id`, `request_id`, `template`, `lane`, `channel`, `user_id`, `subject`, `provider`, `occurred_at`) — **không có `template_data`, không có địa chỉ**. `request_id` là `notification_request.id` do Notification sinh (UUID mới), **không phải** CloudEvents `id` của request gốc (id của Cart không phải UUID).

| Type | Khi | Thêm | Consumer |
|---|---|---|---|
| `notification.delivered.v1` | Email: SES `Delivery`; in-app: đã ghi `inbox_item` | `delivered_at` | Analytics |
| `notification.bounced.v1` | SES `Bounce` | `bounce_type`, `suppressed` | Analytics |
| `notification.failed.v1` | Trạng thái cuối `FAILED`/`EXPIRED` | `final_status`, `reason_code`, `attempts` | Analytics |
| `notification.complaint-received.v1` | SES `Complaint` | `marketing_disabled` | Analytics, **User** (tắt `marketing_enabled` ở nguồn) |

Không có event cho `SENT` hay `SKIPPED`: `SENT` là trạng thái trung gian, `SKIPPED` là hành vi bình thường có metric riêng; consumer downstream không cần chúng.

## 4. HTTP ra: Auth internal

| Endpoint | Khi | Cache | Lỗi |
|---|---|---|---|
| `POST /internal/v1/auth/notification-deliveries/{delivery_id}/content` (đã có) | Request từ Auth | **Không** (chứa secret) | `404`/`410` → `FAILED (no_recipient)` / `EXPIRED` |
| `GET /internal/v1/auth/users/{user_id}/notification-contact` (**mới**) | Mọi kênh email không phải Auth | Redis/memory 15 phút theo `user_id`; không ghi DB | `404` → `FAILED (no_recipient)`, không retry |

Cả hai: mTLS do mesh thực thi (Auth không tự kết thúc TLS và chỉ kiểm allowlist `X-Service-Identity: notification-service`, [Auth ADR-007](../auth/decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md); Notification luôn gửi header này), `no-store`, timeout 2 s, breaker và bulkhead riêng theo lane ([06-backpressure.md](./06-backpressure.md) §3). Cả hai nằm trên **listener internal 8081** của Auth (public 8080 không serve `/internal/**`). `email_verified = false` → vẫn gửi transactional, không gửi bulk (delivery `SKIPPED (email_unverified)`). Với `content`: `template` trả về phải khớp template đã gán ở Ingest, lệch → `FAILED (template_mismatch)`.

## 5. HTTP vào: inbox (public qua Gateway)

Listener public `8080` (host dev `8091`), chỉ có nhóm này. Identity từ Gateway (`X-User-Id`), mọi response `no-store`, lỗi `problem+json`.

| Method | Path | Ghi chú |
|---|---|---|
| `GET` | `/api/v1/notifications/inbox?cursor&limit&unread_only` | Cursor `(created_at DESC, id)`, `limit ≤ 50`; đọc `inbox_item`, đúng tới lúc commit |
| `GET` | `/api/v1/notifications/inbox/unread-count` | Redis; contract ghi rõ **có thể lệch vài giây**; frontend poll 30–60 s |
| `PATCH` | `/api/v1/notifications/inbox/{item_id}` `{"read": bool}` | Idempotent; `404` nếu không thuộc user |
| `DELETE` | `/api/v1/notifications/inbox/{item_id}` | Archive, không xoá vật lý; idempotent |
| `POST` | `/api/v1/notifications/inbox/mark-all-read` | Action; idempotent theo bản chất, không cần `Idempotency-Key` |

**Không có**: API preference (User Service sở hữu `replaceMyNotificationPreferences`), API lịch sử email (không lưu bản render), API gửi thủ công, realtime (SSE/WebSocket để phase sau — Gateway đã hỗ trợ WebSocket cho Chat nên khi cần chỉ là thêm route).

## 6. HTTP vào: internal

Listener internal `8081` (host dev `8092`), không qua Gateway; cũng serve `/metrics`, `/livez`, `/readyz`. Ai được chạm các path này do **service mesh** quyết định (mTLS + AuthorizationPolicy theo principal, [mesh runtime contract](../mesh/runtime-contract.md) §5.2): Notification **không** tự kết thúc TLS và không nhìn thấy chứng chỉ của caller. Ứng dụng chỉ kiểm header `X-Service-Identity` với allowlist của từng nhóm như lớp phòng thủ thứ hai, **ở mọi môi trường**: thiếu header → `401`, ngoài allowlist → `403`. Allowlist (`internal.status_principals`, `internal.ops_principals`): status → `order-service`, `finance-ops`; ops → `notification-ops`; rỗng là lỗi cấu hình lúc khởi động. Docker Compose không có mesh nên header không được xác thực ở đó ([decisions.md](./decisions.md) D-21, cùng mô hình [Auth ADR-007](../auth/decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md) và [User ADR-008](../user/decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md)).

### Status (support / admin tool)

| Method | Path | Trả về |
|---|---|---|
| `GET` | `/internal/v1/notifications/requests/{request_id}` | Request + `decision` + danh sách delivery (status, attempt, timestamps, `last_error_code`, `possibly_duplicate`) |
| `GET` | `/internal/v1/notifications/deliveries?subject=orders/1000234&template=` | Tối đa 100 delivery mới nhất của aggregate — câu hỏi thực tế của support |

Không trả `template_data`, không trả email. Caller phase 1: `order-service` (admin UI), `finance-ops`.

### Ops (SRE, không qua Gateway)

| Method | Path | Tác dụng | Ràng buộc |
|---|---|---|---|
| `GET` | `/internal/v1/notifications/ops/lanes/{lane}` | Đọc trạng thái pause hiện tại (lane chưa đổi trả `active`/`active`) | Có audit, không cần `Idempotency-Key`; lane ngoài enum → `404` |
| `PUT` | `/internal/v1/notifications/ops/lanes/{lane}` | Pause/resume lane hoặc `lane × channel` | `Idempotency-Key`. Watchdog breaker cũng ghi trạng thái này (`updated_by = system:breaker-watchdog`, [06-backpressure.md](./06-backpressure.md) §5) |
| `POST` | `/internal/v1/notifications/ops/deliveries/{id}/redrive` | `FAILED`/`EXPIRED` → `PENDING` | Trạng thái khác → `409`; không tự kéo dài `expires_at` |
| `POST` | `/internal/v1/notifications/ops/dlq/redrive` | Re-drive theo lô, `202` + `operation_id` | Lọc theo `reason_code`/thời gian; không bao gồm lỗi contract của producer |
| `POST` / `DELETE` | `/internal/v1/notifications/ops/suppressions[/{id}]` | Thêm `MANUAL` / gỡ | Không gỡ được `HARD_BOUNCE`/`COMPLAINT` (`409`) |
| `PUT` | `/internal/v1/notifications/ops/replay-mode` | Bỏ qua event cũ hơn X khi replay | Tự tắt sau `ttl` |

Mọi ops call ghi audit (principal, thời điểm, tham số) vào bảng `ops_audit` và stream log `audit=true`. `Idempotency-Key` lưu 24 h trong `ops_idempotency` (hash = method + path + body); trạng thái pause lane và replay mode lưu trong `ops_control` để mọi process (deliver worker ở replica khác) cùng thấy ([09-data-model.md](./09-data-model.md) §8.1). Phase 1 có cả HTTP handler lẫn `notifctl` gọi cùng handler.

## 7. Thay đổi đã áp vào contract của service khác

| File | Thay đổi |
|---|---|
| `contracts/http/auth/internal/v1/openapi.yaml` | Thêm `GET /internal/v1/auth/users/{user_id}/notification-contact`, schema `NotificationContact`, response `UserNotFound` |
| `contracts/events/order/v1/asyncapi.yaml` | Channel `notificationRequests` tham chiếu message của Notification và operation `publishNotificationRequests` với 5 template/mốc phát; deterministic item-name source đã khóa từ Checkout/Order snapshot |
| `contracts/catalog/services.yaml` | `notification-service.contract_adoption: pilot`; từ 2026-10-10 `runtime: go`, `status: implemented` |
| `contracts/catalog/interactions.yaml` | 7 interaction mới (`order-requests-notifications`, `notification-resolves-auth-contact`, `notification-consumes-cart-abandoned`, `notification-publishes-outcomes`, `gateway-routes-notification-inbox`, `support-queries-notification-status`, `notification-ops-operates-notification`) |
| `docs/mesh/runtime-contract.md` | 4 dòng AuthorizationPolicy §5.2; 3 dòng retry/timeout; sửa ghi chú "Notification chỉ nhận Kafka" |
| `docs/gateway/runtime-contract.md` | Route `/api/v1/notifications/inbox/**` |

Còn lại cho owner tương ứng: Order xác nhận snapshot có `name` (README §7 mục 3); ~~User Service đăng ký consume `notification.complaint-received.v1`~~ (đã xong: User consume trên `t.notification.outcomes.v1`, group `user-service`, DLQ `t.notification.outcomes.v1.dlq`, xem [User ADR-009](../user/decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md)); Analytics đăng ký consume `t.notification.outcomes.v1`.

## 8. Mã lỗi HTTP (listener public và internal)

Mọi lỗi là `application/problem+json` theo [`problem.schema.json`](../../contracts/shared/json-schema/v1/problem.schema.json) với `code` ổn định, `Cache-Control: no-store`; nội dung lỗi nội bộ (driver DB, panic) không bao giờ vào `detail`. Route lạ trả `404` và method sai trả `405` cùng định dạng.

| `code` | HTTP | `retryable` | Khi nào |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | không | Tham số/body sai (`limit` ngoài 1–50, `cursor` hỏng, field lạ, `Content-Type` không phải JSON, body > 64 KB); thiếu hoặc ngắn/dài sai `Idempotency-Key` (8–128 ký tự) ở ops mutation; `subject` thiếu ở `deliveries`; redrive delivery đã quá `expires_at` mà không gửi `expires_at` mới; lane/state/channel ngoài enum ở `PUT lanes`; redrive `reason_code` thuộc lỗi contract của producer |
| `UNAUTHORIZED` | 401 | không | Inbox thiếu/sai `X-User-Id`; internal thiếu `X-Service-Identity` |
| `FORBIDDEN` | 403 | không | `X-Service-Identity` ngoài allowlist của nhóm (status gọi ops, v.v.) |
| `NOT_FOUND` | 404 | không | Item inbox không tồn tại hoặc không thuộc user (không bao giờ `403`); id trên path không phải UUID; request/delivery/suppression không tồn tại; lane lạ ở `GET lanes`; user không có contact khi thêm suppression thủ công |
| `METHOD_NOT_ALLOWED` | 405 | không | Method không có trên route |
| `STATE_CONFLICT` | 409 | không | Redrive delivery không ở `FAILED`/`EXPIRED`; gỡ suppression `HARD_BOUNCE`/`COMPLAINT` |
| `IDEMPOTENCY_CONFLICT` | 409 | không | Cùng `Idempotency-Key` nhưng method/path/body khác |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | không | Call đầu tiên với key đó chưa xong; gọi lại sau |
| `DEPENDENCY_UNAVAILABLE` | 503 | có | Kho idempotency lỗi; Auth lỗi khi resolve contact cho suppression thủ công |
| `INTERNAL_ERROR` | 500 | không | Lỗi không lường trước (kể cả panic đã được `recovery` bắt) |

`DEPENDENCY_UNAVAILABLE` (503, `retryable=true`) kèm `Retry-After: 2` (giây) theo [contracts ADR-005](../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md); caller `notification-ops` vẫn gọi lại bằng cùng `Idempotency-Key`.

`RATE_LIMITED` (429) có trong từ vựng lỗi nhưng Notification không phát ra: nó không có rate limit trên hai listener HTTP.

## 9. DLQ của consumer: topic dùng chung và cách phân biệt

Mỗi nguồn Kafka có DLQ theo cấu hình `kafka.sources.<nguồn>.dlq` (tên topic nguồn + `.dlq`). Message vào đó là **bản gốc nguyên văn** kèm header của nó cộng ba header của Notification: `x-dlq-reason` (`invalid_envelope`, `invalid_payload`, `max_attempts_exceeded`), `x-dlq-source-topic` và `x-dlq-consumer-group` (group của Notification đã đẩy message, D-22). Lỗi contract của producer không đi Kafka DLQ mà nằm ở bảng `notification_request` ([02-pipeline.md](./02-pipeline.md) §4).

Phần lớn các DLQ này là **topic dùng chung** với service khác:

| DLQ | Ai khác ghi vào | Cách phân biệt message của Notification |
|---|---|---|
| `t.auth.events.dlq` | User, Review (cùng consume `USER_DELETED`) | Header `x-dlq-consumer-group = notification-user-lifecycle` |
| `t.review.events.dlq` | Aggregator của Review; consumer khác của topic tự chọn policy ([AsyncAPI Review](../../contracts/events/review/v1/asyncapi.yaml)) | `x-dlq-consumer-group = notification-review-transactional` |
| `t.user.events.dlq` | Chat khi có runtime | `x-dlq-consumer-group = notification-preferences` |
| `t.cart.events.dlq` | **Cart relay** — DLQ phía *producer*, payload là JSON `{reason, outbox_key, ...}` chứ không phải CloudEvent ([AsyncAPI Cart](../../contracts/events/cart/v1/asyncapi.yaml)) | `x-dlq-consumer-group = notification-cart-bulk`; message của Notification là CloudEvent gốc, của Cart thì không |
| `t.auth.notification.events.dlq`, `t.notification.{critical,transactional,bulk}.v1.dlq` | Không ai | — |

Hệ quả cho vận hành: **không redrive cả topic**; lọc theo `x-dlq-consumer-group` trước, vì một tool redrive của Cart/User/Review có thể nhặt nhầm message của Notification và ngược lại. Message ghi trước khi có header `x-dlq-consumer-group` không mang group; nhận diện bằng `x-dlq-source-topic` và định dạng CloudEvent.
