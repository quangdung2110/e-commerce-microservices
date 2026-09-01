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
| `ORDER_PAID` | Inbound Kafka | Order event contract |
| Product lookup | Outbound gRPC | `product_service.proto` |
| `CART_ABANDONED` | Outbound Kafka | [AsyncAPI v1](../../contracts/events/cart/v1/asyncapi.yaml) |

## 2. HTTP APIs

`GET /api/v1/carts/mine` đọc Redis items. Sau đó Cart gọi `GetBatchSkus` để
phủ `current_price` và catalog saleability. Product timeout/breaker mở trả snapshot với
`catalog_freshness = DEGRADED` và `current_price` có thể null. Redis miss trả cart
rỗng; không rehydrate. GET là query thuần: không cập nhật snapshot Redis, TTL hay
abandonment schedule.

`POST /api/v1/carts/mine/items` cần `Idempotency-Key` và nhận `delta` khác 0. Delta
dương thêm/tăng, delta âm giảm; Cart không nhận quantity tuyệt đối. SKU mới hoặc
delta dương trên validation stale gọi `GetSku`; các lần tăng khi validation
còn tươi đi thẳng Lua. Cart không gọi Inventory và không chặn thao tác vì hết hàng;
Checkout reserve tại Inventory mới là quyết định cuối. Response/status code và payload chuẩn nằm trong OpenAPI.

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

## 5. Event tiêu thụ: `ORDER_PAID`

Cart dùng `order_id`, `user_id`, `sku_ids[]` và dựng routing từ `user_id`, rồi gọi
remove-items script với `touch_activity = 0`. SKU không có trong cart bị bỏ qua;
`HDEL` là idempotent nên Kafka redelivery không cần Redis key riêng. Event không tạo
Kafka message Cart.

## 6. Compatibility

Ưu tiên thêm optional field. `CART_ABANDONED` là event duy nhất được AsyncAPI v1 mô
tả; các event Cart cũ bị loại khỏi public contract. HTTP v1 dùng delta, khớp Lua
atomic; contract test phải chứng minh duplicate Idempotency-Key không áp dụng delta
lần hai và các delta độc lập không lost update.
