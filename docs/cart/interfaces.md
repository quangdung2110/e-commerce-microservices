---
doc_id: cart-interfaces
service: cart
scope: current
status: implemented
owners:
  - Cart Service team
evidence:
  - contracts/http/cart/v1/openapi.yaml
  - contracts/events/cart/v1/asyncapi.yaml
  - go/services/cart-service/internal/api/router/router.go
  - go/services/cart-service/internal/api/dto/response/problem.go
verified_against_commit: null
---

# Cart Service — Interfaces

## 1. Contract index

| Interaction | Direction | Contract |
|---|---|---|
| Cart HTTP API | Inbound | [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml) |
| Checkout cart snapshot | Inbound (nội bộ) | [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml), `getCheckoutCartSnapshot` |
| `com.ecommerce.order.paid.v1` | Inbound Kafka | [Order Events AsyncAPI v1](../../contracts/events/order/v1/asyncapi.yaml) |
| `com.ecommerce.auth.user-deleted.v1` | Inbound Kafka | [Auth Events AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) |
| Product lookup | Outbound gRPC | `product_service.proto` |
| `CART_ABANDONED` | Outbound Kafka | [AsyncAPI v1](../../contracts/events/cart/v1/asyncapi.yaml) |

## 2. HTTP APIs

Gateway local dùng `authenticated@file` qua ForwardAuth `gateway-auth`; identity
lấy từ JWT đã verify, không inject user dev cố định. Gateway không retry POST/DELETE.
Bước 5 frontend dùng cùng module `lib/api/cart.ts` và cache theo account cho PDP,
badge header và `/cart` protected. Màn giỏ gửi delta để đổi số lượng, xóa đơn/batch
với cùng DELETE, và giữ key/payload khi retry. `DEGRADED` hoặc giá null không tạo
tổng từ snapshot cũ; `is_available` chỉ là catalog saleability. Xem
[báo cáo bước 5](../frontend/step-5-verification.md).

Cart không publish cổng host trong Docker Compose gốc; client đi qua Gateway
(`http://localhost:8088/api/v1/carts/mine`). Cổng 8085 thuộc Promotion.

`GET /api/v1/carts/mine` đọc Redis items. Sau đó Cart gọi `GetBatchSkus` để
phủ `current_price` và catalog saleability. Product timeout/breaker mở trả snapshot với
`catalog_freshness = DEGRADED` và `current_price` có thể null. Redis miss trả cart
rỗng; không rehydrate. GET là query thuần: không cập nhật snapshot Redis, TTL hay
abandonment schedule. Một SKU mà Product báo không tồn tại hoặc không bán được vẫn nằm
trong cart với `is_available = false` (`freshness` vẫn là `CURRENT`).

`image_url` trong response luôn là URL tuyệt đối. Product gRPC trả storage key của ảnh
đại diện (không phải URL); Cart lưu nguyên key trong snapshot và chỉ dựng URL khi render
response: `CART_MEDIA_CDN_BASE_URL` + `/` + key percent-encode từng segment, SKU không ảnh
nhận `CART_MEDIA_PLACEHOLDER_URL`, dòng cũ đã lưu URL tuyệt đối được giữ nguyên. Hai giá trị
này phải bằng cấu hình của Product Detail BFF và Search để giỏ hàng, thẻ sản phẩm và PDP
trỏ cùng một file ([PDP ADR-005](../product-detail-bff/decisions/ADR-005-pdp-catalog-scope-and-media-url.md)).
Storage key không bao giờ ra khỏi Cart.

`GET /internal/v1/carts/{user_id}/checkout-snapshot` chỉ dành cho principal
`checkout-service`. Endpoint đọc cùng Redis source of truth nhưng chỉ trả
`{product_id, sku_id, quantity}` sắp theo `sku_id`, không gọi Product, không refresh TTL và không
mutate lifecycle. Checkout không được đọc trực tiếp keyspace bucketed của Cart. Service kiểm
`X-Service-Identity` theo allowlist `cart.internal_callers` (mặc định `checkout-service`) như phòng thủ
bổ sung cho mesh: thiếu header `401`, service khác `403`; `user_id` không phải UUID `400`; Redis lỗi
`503`. Route nằm ngoài Gateway (không có router Traefik) và không bị rate limit.

`POST /api/v1/carts/mine/items` cần `Idempotency-Key` và nhận `delta` khác 0 trong -100..100. Delta
dương thêm/tăng, delta âm giảm; Cart không nhận quantity tuyệt đối. SKU mới hoặc
delta dương trên validation stale gọi `GetSku`; các lần tăng khi validation
còn tươi đi thẳng Lua. Cart không gọi Inventory và không chặn thao tác vì hết hàng;
Checkout lấy allocation preview, còn Order Reserve tại Inventory mới là quyết định cuối. Trả `201` khi SKU
mới vào cart và `200` khi SKU đã có đổi quantity; replay cùng key trả lại đúng status ban đầu.
Response/status code và payload chuẩn nằm trong OpenAPI.

`DELETE /api/v1/carts/mine/items` xóa tối đa 100 SKU, tự nhiên idempotent. Nếu cart rỗng,
script xóa cả items/meta và member scheduler ZSET. Không phát Kafka event.

### 2.1 Error catalog

Mọi lỗi là `application/problem+json` với `code` ổn định, `trace_id` (= `X-Request-Id` đã chuẩn hoá: giá trị
đến nếu khớp `^[A-Za-z0-9._:-]{8,128}$`, nếu không thì trace ID của `traceparent` hợp lệ, rồi UUID mới; giá trị
sai không làm request lỗi) và, với lỗi
validate, `errors[]` theo field. `retryable` chỉ có (và bằng `true`) khi client được phép gửi lại
nguyên request.

| Code | HTTP | Retryable | Khi nào |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | không | `Idempotency-Key` không dài 16–128 ký tự, body không phải JSON hoặc sai schema, `delta` bằng 0 hoặc ngoài -100..100, `sku_id` rỗng/quá 64 ký tự, `sku_ids` rỗng/trùng/quá 100, `user_id` nội bộ không phải UUID |
| `UNAUTHENTICATED` | 401 | không | Thiếu `X-User-Id` (public) hoặc `X-Service-Identity` (nội bộ) |
| `FORBIDDEN` | 403 | không | `X-Service-Identity` ngoài allowlist `cart.internal_callers` |
| `NOT_FOUND` | 404 | không | Không có route khớp |
| `IDEMPOTENCY_CONFLICT` | 409 | không | Cùng `Idempotency-Key` với payload khác |
| `CART_FULL` | 422 | không | SKU mới khi cart đã có 50 SKU |
| `PRODUCT_NOT_AVAILABLE` | 422 | không | Product/SKU không active, hoặc Product không biết SKU |
| `QUANTITY_INVALID` | 422 | không | Delta làm quantity không còn dương; dùng DELETE |
| `ITEM_NOT_FOUND` | 422 | không | Delta âm cho SKU không có trong cart |
| `PRODUCT_SNAPSHOT_REQUIRED` | 422 | — | Tín hiệu nội bộ giữa script và application service để gọi Product; không bao giờ tới client trên đường ghi bình thường |
| `DELTA_INVALID` | 400 | không | Delta bằng 0 ở script; handler đã chặn bằng `VALIDATION_FAILED` nên client không thấy mã này |
| `RATE_LIMITED` | 429 | có (`Retry-After`) | Vượt rate limit cục bộ theo user (§2.2) |
| `CART_STORE_UNAVAILABLE` | 503 | có | Redis lỗi hoặc timeout (GET, DELETE, snapshot nội bộ, và POST) |
| `PRODUCT_UNAVAILABLE` | 503 | có, trừ khi Product từ chối request (xem dưới) | Product gRPC lỗi, timeout hoặc breaker mở khi POST cần xác thực SKU |
| `INTERNAL_ERROR` | 500 | không | Lỗi không dự kiến (đã log đầy đủ, không lộ chi tiết) |

`PRODUCT_UNAVAILABLE` đọc `google.rpc.ErrorInfo` của Product ([ADR-017](decisions/ADR-017-classify-product-errors-by-error-info.md)): khi
Product từ chối vì lỗi của Cart (`INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`) mã vẫn là `503
PRODUCT_UNAVAILABLE` nhưng `retryable = false`, không có `Retry-After` và không làm breaker mở; `reason` nằm trong log.

Mọi `503` (`retryable = true`) kèm `Retry-After: 2` (giây) và mọi `429` kèm `Retry-After` theo [contracts ADR-005](../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md); caller chờ không quá deadline còn lại của nó.

### 2.2 Rate limit

Mỗi pod giữ một token bucket theo user (khóa bằng `user_token`, không dùng `user_id`): 20 request/giây,
burst 40, bucket idle 10 phút bị loại. Giới hạn áp dụng cho `GET`, `POST` và `DELETE` dưới
`/api/v1/carts/mine`; không áp dụng cho `/internal/v1/**` hay `/health/**`. Vượt giới hạn trả
`429 RATE_LIMITED` với `Retry-After` (giây) và `retryable = true`. Đây là lưới an toàn cục bộ theo từng
pod; policy chính thức nằm ở Gateway (`rate-limit-api@file`).

## 3. gRPC APIs

| RPC | Khi gọi | Failure |
|---|---|---|
| `GetSku` | SKU mới hoặc validation write stale | Từ chối write |
| `GetBatchSkus` | Mở cart | Trả response degraded |

Deadline 200ms và circuit breaker là bắt buộc. Cart không gọi Product khi xóa item,
xử lý `ORDER_PAID`, xử lý `USER_DELETED`, relay hay marker.

**Metadata gửi cho Product** ([contracts ADR-002](../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md)):
`x-service-identity: cart-service`, `x-request-id` (request ID đã chuẩn hoá của request đang phục vụ), và nếu
request đến có `traceparent` hợp lệ thì `traceparent` kèm `tracestate` (chỉ khi là ASCII in được, tối đa 512 ký
tự). Cart không có tracer nên không bao giờ bịa `traceparent`. Request ID và trace context đi trong
`context.Context` của request (middleware `RequestID`), nên lời gọi dùng chung qua singleflight mang ID của
request khởi tạo.

**Deadline của caller.** `GET /internal/v1/carts/{user_id}/checkout-snapshot` đọc `X-Request-Timeout-Ms`
([contracts ADR-003](../../contracts/decisions/ADR-003-deadline-propagation.md)) và bọc `context.WithTimeout` quanh
request (middleware `CallerDeadline`), nên lời gọi Redis dừng khi Checkout đã thôi chờ. Header sai hoặc thiếu
bị bỏ qua. Middleware chỉ gắn vào route đọc nội bộ; route ghi của Gateway không dùng nó và Gateway xoá header này.
Lời gọi Product tới `GetSku`/`GetBatchSkus` đã có deadline gRPC riêng (200 ms).

**Client hủy request không phải lỗi của Product.** Breaker chỉ đếm lỗi do Product gây ra
(lỗi transport, deadline riêng của Cart). Khi client đóng kết nối giữa chừng:

- `GetBatchSkus`: không ghi failure vào breaker. Nếu lời gọi đó là probe half-open thì slot
  được trả lại (`release`), breaker không mở lại và không kẹt half-open.
- `GetSku`: các request đồng thời cho cùng SKU dùng chung một lời gọi (singleflight) chạy trên
  context tách khỏi request đầu tiên, với deadline `GetSkuTimeout` riêng. Một client hủy chỉ làm
  chính request đó trả về sớm (`PRODUCT_UNAVAILABLE`); các request còn lại vẫn nhận kết quả.

Deadline của chính Cart vẫn tính là failure, vì đó là tín hiệu Product chậm.

## 4. Event phát ra: `CART_ABANDONED`

| Thuộc tính | Giá trị |
|---|---|
| Topic | `t.cart.events` |
| Consumer | Notification Service |
| Kafka key | `cart_id` |
| Delivery | At-least-once |
| Dedupe | CloudEvent `id` / `event_id` |
| Ordering | Trong cùng cart/bucket |
| `dataschema` | `/contracts/events/cart/v1/schemas/cart-abandoned.schema.json` (cấu hình `kafka.event_data_schema`) |
| Kafka header | `outbox_bucket`, `stream_id`, `cart_id` |

Event chỉ được tạo bởi `mark_abandoned.lua`. Relay đặt CloudEvent ID xác định từ
`outbox_key + stream_id`; retry produce cho cùng stream entry phải dùng cùng ID.
Payload gồm `cart_id`, `user_id`, `abandonment_cycle`, `occurred_at`. Nó không chứa
item list, current price, payment hay PII trực tiếp.

Không phát `CART_ITEM_*`, `CART_CLEARED` hay `CART_CHECKED_OUT` khi chưa có consumer
được xác định.

**Retry và DLQ.** Retry là của relay: mỗi lượt thử một entry tối đa 3 lần
(`worker.outbox_relay.max_publish_attempts`) với exponential backoff có jitter. Hết 3 lần entry vẫn
pending và được thử lại ở lượt sau qua `XAUTOCLAIM` (sau `claim_min_idle`). Relay dừng bucket tại
entry lỗi đầu tiên, và **không đọc entry mới khi bucket còn pending** (`XPENDING`), nên entry mới không
thể được publish trước entry đã lỗi dù Kafka hồi phục trong lúc chờ `claim_min_idle`. Nghĩa là Kafka outage làm chậm event chứ không làm mất event. `t.cart.events.dlq` chỉ nhận
entry outbox không bao giờ thành công (thiếu `cart_id` hoặc event type lạ); message DLQ là JSON chẩn
đoán `{reason, outbox_key, stream_id, outbox_bucket, event_type}`, không phải CloudEvent.

## 5. Event tiêu thụ

### 5.1 `com.ecommerce.order.paid.v1`

Cart dispatch theo exact CloudEvents `type`, kiểm `specversion=1.0`,
`source=/services/order`, `subject=orders/{order_id}` và canonical decimal-string
`order_id`. Cart dùng `user_id`, unique `sku_ids[]` và dựng routing từ `user_id`, rồi gọi
remove-items script với `touch_activity = 0`. SKU không có trong cart bị bỏ qua;
`HDEL` là idempotent nên Kafka redelivery không cần Redis key riêng. Event không tạo
Kafka message Cart. `ORDER_COMPLETED` cùng topic được ACK mà không tạo effect.

### 5.2 `com.ecommerce.auth.user-deleted.v1`

Topic `t.auth.events`, consumer group `cart-auth-consumer`, key Kafka `user_id`. Cart kiểm
`specversion=1.0`, `source=/services/auth`, `id` không rỗng, `data.user_id` là UUID và
`data.deleted_at` là RFC 3339, dựng identity từ `user_id` (không nhận `cart_id` từ event) rồi gọi
`purge_cart.lua`. Script xóa `items`, `meta` và member ZSET; chạy lại hoặc user chưa từng có giỏ chỉ trả
`purged = false`. Mọi event khác trên topic (ví dụ `USER_VERIFIED_SUCCESS`) bị ACK mà không có effect;
message không parse được hoặc thiếu trường bắt buộc bị log và ACK; lỗi Redis trả error để Kafka giao lại.
Cart không phát event đáp lại ([ADR-014](./decisions/ADR-014-purge-cart-on-user-deleted.md)).

## 6. Compatibility

Ưu tiên thêm optional field. `CART_ABANDONED` là event duy nhất Cart phát; inbound
`ORDER_PAID` thuộc AsyncAPI do Order sở hữu và `USER_DELETED` thuộc AsyncAPI do Auth sở hữu. Các
event Cart cũ bị loại khỏi public contract. HTTP v1 dùng delta, khớp Lua atomic; test script xác nhận
duplicate Idempotency-Key không áp dụng delta lần hai và các delta độc lập không lost update.
