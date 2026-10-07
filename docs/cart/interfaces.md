---
doc_id: cart-interfaces
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
verified_against_commit: null
---

# Cart Service — Interfaces

## 1. Contract index

| Interaction | Direction | Contract |
|---|---|---|
| Cart HTTP API | Inbound | [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml) |
| `com.ecommerce.order.paid.v1` | Inbound Kafka | [Order Events AsyncAPI v1](../../contracts/events/order/v1/asyncapi.yaml) |
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


`GET /api/v1/carts/mine` đọc Redis items. Sau đó Cart gọi `GetBatchSkus` để
phủ `current_price` và catalog saleability. Product timeout/breaker mở trả snapshot với
`catalog_freshness = DEGRADED` và `current_price` có thể null. Redis miss trả cart
rỗng; không rehydrate. GET là query thuần: không cập nhật snapshot Redis, TTL hay
abandonment schedule.

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
`503`. Route nằm ngoài Gateway (không có router Traefik).

`POST /api/v1/carts/mine/items` cần `Idempotency-Key` và nhận `delta` khác 0. Delta
dương thêm/tăng, delta âm giảm; Cart không nhận quantity tuyệt đối. SKU mới hoặc
delta dương trên validation stale gọi `GetSku`; các lần tăng khi validation
còn tươi đi thẳng Lua. Cart không gọi Inventory và không chặn thao tác vì hết hàng;
Checkout lấy allocation preview, còn Order Reserve tại Inventory mới là quyết định cuối. Response/status code và payload chuẩn nằm trong OpenAPI.

`DELETE /api/v1/carts/mine/items` xóa tối đa 100 SKU, tự nhiên idempotent. Nếu cart rỗng,
script xóa cả items/meta và member scheduler ZSET. Không phát Kafka event.

## 3. gRPC APIs

| RPC | Khi gọi | Failure |
|---|---|---|
| `GetSku` | SKU mới hoặc validation write stale | Từ chối write |
| `GetBatchSkus` | Mở cart | Trả response degraded |

Deadline 200ms và circuit breaker là bắt buộc. Cart không gọi Product khi xóa item,
xử lý `ORDER_PAID`, relay hay marker.

## 4. Event phát ra: `CART_ABANDONED`

| Thuộc tính | Giá trị |
|---|---|
| Topic | `t.cart.events` |
| Consumer | Notification Service |
| Kafka key | `cart_id` |
| Delivery | At-least-once |
| Dedupe | CloudEvent `id` / `event_id` |
| Ordering | Trong cùng cart/bucket |

Event chỉ được tạo bởi `mark_abandoned.lua`. Relay đặt CloudEvent ID xác định từ
`outbox_key + stream_id`; retry produce cho cùng stream entry phải dùng cùng ID.
Payload gồm `cart_id`, `user_id`, `abandonment_cycle`, `occurred_at`. Nó không chứa
item list, current price, payment hay PII trực tiếp.

Không phát `CART_ITEM_*`, `CART_CLEARED` hay `CART_CHECKED_OUT` khi chưa có consumer
được xác định.

## 5. Event tiêu thụ: `com.ecommerce.order.paid.v1`

Cart dispatch theo exact CloudEvents `type`, kiểm `specversion=1.0`,
`source=/services/order`, `subject=orders/{order_id}` và canonical decimal-string
`order_id`. Cart dùng `user_id`, unique `sku_ids[]` và dựng routing từ `user_id`, rồi gọi
remove-items script với `touch_activity = 0`. SKU không có trong cart bị bỏ qua;
`HDEL` là idempotent nên Kafka redelivery không cần Redis key riêng. Event không tạo
Kafka message Cart. `ORDER_COMPLETED` cùng topic được ACK mà không tạo effect.

## 6. Compatibility

Ưu tiên thêm optional field. `CART_ABANDONED` là event duy nhất Cart phát; inbound
`ORDER_PAID` thuộc AsyncAPI do Order sở hữu. Các event Cart cũ bị loại khỏi public contract. HTTP v1 dùng delta, khớp Lua
atomic; contract test phải chứng minh duplicate Idempotency-Key không áp dụng delta
lần hai và các delta độc lập không lost update.
