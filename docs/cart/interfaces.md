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

`GET /api/v1/carts/mine` đọc Redis items. Sau đó Cart gọi `GetBatchProductSkus` để
phủ `current_price` và availability. Product timeout/breaker mở trả snapshot với
`price_freshness = DEGRADED`. Redis miss trả cart rỗng; không rehydrate.

`POST /api/v1/carts/items` cần `Idempotency-Key`. SKU mới gọi `GetProductSku` để
validate và dựng snapshot. SKU có validation Redis còn tươi đi thẳng Lua; khi stale,
application refresh Product dùng LRU + singleflight rồi retry script. Response/status
code và payload chuẩn nằm trong OpenAPI.

`DELETE /api/v1/carts/items` xóa tối đa 100 SKU, tự nhiên idempotent. Nếu cart rỗng,
script xóa cả items/meta và member scheduler ZSET. Không phát Kafka event.

## 3. gRPC APIs

| RPC | Khi gọi | Failure |
|---|---|---|
| `GetProductSku` | SKU mới hoặc validation write stale | Từ chối write |
| `GetBatchProductSkus` | Mở cart | Trả response degraded |

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

Cart dùng `order_id`, `user_id`, `sku_ids[]`, dựng routing từ `user_id`, chốt Redis
idempotency key rồi gọi remove-items script. SKU không có trong cart bị bỏ qua. Event
không tạo Kafka message Cart.

## 6. Compatibility

Ưu tiên thêm optional field. `CART_ABANDONED` là event duy nhất được AsyncAPI v1 mô
tả; các event Cart cũ bị loại khỏi public contract. Contract HTTP hiện dùng quantity
tuyệt đối trong khi script legacy dùng delta; trước implementation cần chốt semantics
và bổ sung contract test để tránh request đến lệch thứ tự.
