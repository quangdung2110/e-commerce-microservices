---
doc_id: cart-interfaces
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence:
  - contracts/http/cart/v1/openapi.yaml
  - contracts/events/cart/v1/asyncapi.yaml
  - go/services/cart-service/internal/api
verified_against_commit: null
---

# Cart Service — Interfaces

> File này giải thích semantics. Nguồn chuẩn cho payload là
> [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml) cho HTTP và
> [AsyncAPI v1](../../contracts/events/cart/v1/asyncapi.yaml) cho event Kafka.
> Một số điểm giữa contract đích và thiết kế đang chạy còn lệch nhau; xem
> [§6 Compatibility](#6-compatibility).

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumers | Contract | Status |
|---|---|---|---|---|---|---|
| Cart HTTP API | Inbound | HTTP | Cart | Client qua API Gateway | [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml) | proposed |
| Cart lifecycle events | Outbound | Kafka `t.cart.events` | Cart | Cart (Postgres Sync), Notification, Analytics | [AsyncAPI v1](../../contracts/events/cart/v1/asyncapi.yaml) | proposed |
| `ORDER_PAID` | Inbound | Kafka `t.order.events` | Order | Cart | [Order events](../order/order_events.md) | legacy, chưa machine-readable |
| Product SKU lookup | Outbound | gRPC | Product | Cart | [product_service.proto](../../contracts/grpc/ecommerce/product/v1/product_service.proto) | proposed |

## 2. HTTP APIs

Toàn bộ API yêu cầu identity tin cậy. Gateway verify JWT rồi inject `X-User-Id`;
đây là security scheme `gatewayIdentity` trong OpenAPI và không được để lộ như một
header do client kiểm soát ở edge. Cart tự dựng `cart_id = "{cart}:" + user_id`,
nên không có tham số `cart_id` trên bất kỳ endpoint công khai nào (`CART-INV-001`).

`Idempotency-Key` là header bắt buộc cho hai endpoint ghi. Cùng key với cùng payload
phải trả về cùng kết quả logic; cùng key với payload khác trả `409`.

| Nhóm | Endpoint | Mục đích | Preconditions | Postconditions |
|---|---|---|---|---|
| Đọc giỏ | `GET /api/v1/carts/mine` | Trả toàn bộ item kèm giá lịch sử và giá hiện tại | User đã đăng nhập | Không thay đổi state, trừ ngoại lệ ghi lại `is_available = false` theo `CART-BR-009` |
| Thêm/cập nhật item | `POST /api/v1/carts/items` | Thêm SKU mới hoặc thay đổi số lượng SKU đã có | SKU và product `ACTIVE`; giỏ chưa vượt `max_skus` | Cart hash được cập nhật và đúng một outbox event được sinh ra; TTL giỏ được gia hạn |
| Xóa item theo lô | `DELETE /api/v1/carts/items` | Xóa tối đa 100 SKU khỏi giỏ | User đã đăng nhập | Các SKU tồn tại bị xóa; nếu giỏ rỗng, key bị `DEL` và phát `CART_CLEARED` |

**Đọc giỏ.** Response luôn phân biệt hai loại giá: `unit_price_at_add` là dữ liệu
lịch sử bất biến, `current_price` là dữ liệu runtime lấy từ Product Service tại thời
điểm đọc và không được lưu bền ở bất kỳ đâu. Khi Product Service timeout hoặc
circuit breaker mở, endpoint vẫn trả `200` với dữ liệu snapshot cũ và đánh dấu độ
tươi của giá là `DEGRADED`; đây không phải lỗi (`CART-BR-014`). Trước khi chạm cold
store, service kiểm tra negative cache để chặn quét database lặp lại cho những user
thực sự chưa có giỏ.

**Thêm/cập nhật item.** Service gọi Product Service để dựng snapshot và validate
trạng thái sản phẩm trước khi ghi. Toàn bộ phần ghi nằm trong một Lua script nên
không có cửa sổ nào mà cart state đã đổi nhưng event chưa được sinh ra. Việc trả
`201` hay `200` phân biệt SKU mới được thêm và SKU đã có được cập nhật số lượng.

**Xóa item theo lô.** Danh sách `sku_ids` được xóa trong một script duy nhất. SKU
không tồn tại trong giỏ được bỏ qua chứ không gây lỗi, nên thao tác này tự nhiên
idempotent. Nếu giỏ rỗng sau khi xóa, service phát `CART_CLEARED` và giỏ chuyển
sang `DELETED` ở cold store.

**Rate limit và giới hạn kích thước.** Endpoint ghi có rate limit theo user và trả
`429` kèm `Retry-After`. Giới hạn nghiệp vụ: tối đa 100 `sku_ids` mỗi lần xóa, tối
đa 50 SKU trong giỏ, tối đa 100 đơn vị cho mỗi SKU. Payload snapshot của mỗi item
nên giữ dưới 8KB để tránh phình hash key.

Path, status code, error format `application/problem+json` và schema
request/response nằm trong OpenAPI. Ánh xạ domain error được định nghĩa tại
[Domain §8](./domain.md#8-domain-errors).

## 3. gRPC APIs

Cart Service không cung cấp gRPC API. Cart là **consumer** của Product Service:

| RPC | Mục đích | Deadline | Retry | Idempotency |
|---|---|---|---|---|
| `GetProductSku` | Lấy dữ liệu một SKU để dựng snapshot khi ghi | 200ms | Không retry mù trong request path | Read-only |
| `GetBatchProductSkus` | Multi-get cho toàn bộ SKU trong giỏ khi đọc hoặc rehydrate | 200ms | Không retry mù | Read-only |

Cả hai lời gọi bắt buộc được bọc circuit breaker. Khi breaker mở, luồng đọc chuyển
sang dữ liệu suy giảm; luồng ghi từ chối thao tác vì không thể xác nhận sản phẩm
còn bán. Ràng buộc kiến trúc: Cart không bao giờ được sập kéo theo Product Service
([ADR-005](./decisions/ADR-005-pull-model-product-data.md)).

## 4. Events phát ra

Tất cả event đi lên topic `t.cart.events`, partition key là `cart_id`, nên thứ tự
chỉ được cam kết trong phạm vi một giỏ. Delivery là at-least-once: consumer bắt
buộc phải deduplicate.

Cơ chế phát: event được `XADD` vào Redis Stream trong cùng script với thay đổi
cart state, sau đó Outbox Relay forward sang Kafka và chỉ `XACK` khi Kafka xác nhận
([ADR-002](./decisions/ADR-002-redis-streams-outbox.md)). Vì vậy không tồn tại
trạng thái “đã đổi giỏ nhưng mất event”.

**Khóa thứ tự và khóa dedupe.** Mọi message mang `stream_id` là Redis Stream Entry
ID. Đây là khóa ordering và idempotency chính cho downstream
([ADR-004](./decisions/ADR-004-stream-id-ordering.md)). Định danh event được Relay
sinh theo cách xác định từ `stream_id` và `cart_id`, không phải UUID ngẫu nhiên, để
mọi lần retry vẫn ánh xạ về cùng một định danh.

| Event | Ý nghĩa nghiệp vụ | Điều kiện phát | Consumer dự kiến |
|---|---|---|---|
| `CART_ITEM_ADDED` | Một SKU chưa từng có được thêm vào giỏ | Script add/update, nhánh item mới | Postgres Sync, Analytics |
| `CART_ITEM_UPDATED` | Số lượng hoặc snapshot của SKU đã có thay đổi | Script add/update, nhánh item đã tồn tại | Postgres Sync, Analytics |
| `CART_ITEM_DELETED` | Một dòng SKU bị xóa khỏi giỏ | Script batch delete, cho từng SKU thực sự tồn tại | Postgres Sync, Analytics |
| `CART_CLEARED` | Giỏ rỗng do user tự xóa hết | Script batch delete khi giỏ rỗng và luồng là user thao tác | Postgres Sync |
| `CART_CHECKED_OUT` | Giỏ rỗng do toàn bộ item đã được mua | Script batch delete khi giỏ rỗng và luồng đến từ `ORDER_PAID` | Postgres Sync, Analytics |
| `CART_ABANDONED` | Giỏ `ACTIVE` quá ngưỡng không tương tác | Cronjob đánh dấu, `XADD` vào cùng outbox stream | Notification, Analytics |

`CART_ITEM_DELETED` luôn có nghĩa là xóa cả dòng theo `sku_id`, không bao giờ có
nghĩa là giảm bớt số lượng. Phân biệt `CART_CLEARED` và `CART_CHECKED_OUT` là quan
trọng vì hai trạng thái này có chính sách retention khác nhau ở cold store.

Payload không bao giờ chứa `current_price`: giá hiện tại là dữ liệu runtime của
Product Service, không phải sự kiện của Cart. Payload cũng luôn mang `user_id`
tường minh để consumer không phải tách chuỗi từ `cart_id`.

Schema, envelope, retention, retry và dead-letter policy được định nghĩa trong
AsyncAPI. Topic hiện khai báo retention 7 ngày, retry tối đa 3 lần với exponential
backoff, và dead-letter topic `t.cart.events.dlq`.

## 5. Events tiêu thụ

### `ORDER_PAID`

| Thuộc tính | Giá trị |
|---|---|
| Producer | Order Service |
| Topic | `t.order.events` |
| Consumer group | `cart-order-consumer` |
| Filter | `event_type = ORDER_PAID` |
| Field Cart sử dụng | `order_id`, `user_id`, `sku_ids[]` |
| Dedupe key | `cart:idemp:order_paid:{order_id}` trong Redis |
| Ordering requirement | Không có; thao tác xóa là idempotent theo `sku_id` |

Hành động: Cart dựng `cart_id` từ `user_id`, chốt khóa idempotency, rồi gọi script
batch delete với loại event kết thúc là `CART_CHECKED_OUT`. Kết quả là các
`CART_ITEM_DELETED` cho từng SKU thực sự bị xóa, cộng thêm `CART_CHECKED_OUT` nếu
giỏ rỗng hoàn toàn.

Quy tắc bắt buộc với consumer:

- Cart **không** nhận `cart_id` từ payload; `cart_id` luôn được dựng từ `user_id`.
- Cart bỏ qua mọi field không nằm trong ba field kể trên, kể cả field mới xuất hiện
  do Order thay đổi contract (consumer resilience).
- Thiếu `user_id` hoặc `sku_ids` rỗng: ghi log lỗi và bỏ qua message, có commit
  offset để tránh redelivery vô hạn. Đây là lỗi contract, không thể sửa bằng retry.
- SKU đã bị user xóa trước khi thanh toán không gây lỗi; script bỏ qua SKU không tồn tại.
- Xử lý lại cùng `order_id` được chặn bằng khóa idempotency, nên duplicate delivery
  không phát sinh event thừa.

Cart hiện không tiêu thụ event nào khác. Đáng chú ý là Cart **chưa** tiêu thụ
`USER_DELETED` từ Auth Service, nên chưa có đường xóa dữ liệu giỏ hàng theo yêu cầu
GDPR; đây là gap đã ghi ở [README §9](./README.md#9-known-gaps).

## 6. Compatibility

**Quy tắc thay đổi.** Ưu tiên thêm field optional. Không xóa field, không thu hẹp
enum và không đổi ngữ nghĩa của field khi consumer còn được hỗ trợ. Thêm loại event
mới là thay đổi tương thích; đổi ngữ nghĩa của một event đã phát là breaking change.
Đổi format Redis key hoặc số shard là breaking change ở tầng dữ liệu vì `cart_id`
được lưu trong cold store và trong mọi payload đã phát.

**Khoảng lệch cần hòa giải giữa contract đích và thiết kế đang chạy.** Những điểm
sau khác nhau giữa `contracts/` và bộ tài liệu thiết kế trước đây; cần chốt một
hướng trước khi contract được nâng lên `implemented`:

| Chủ đề | Contract đích | Thiết kế trước đây |
|---|---|---|
| Ngữ nghĩa tham số ghi | `quantity` tuyệt đối, tối thiểu 1 | `step` cộng dồn, cho phép giá trị âm |
| Idempotency HTTP | `Idempotency-Key` bắt buộc, có trạng thái `409` | Không có idempotency ở tầng HTTP |
| Cờ suy giảm giá | `price_freshness: CURRENT \| DEGRADED` | `is_price_degraded: boolean` |
| Cấu trúc response | Mảng `items` phẳng | Item được gom nhóm kèm `latest_item_at` |
| Biến thể sản phẩm | `variant_attributes` dạng object | `variant_info` dạng chuỗi hiển thị |
| Định dạng thời gian | `date-time` theo RFC 3339 | Epoch milliseconds dạng chuỗi |
| Envelope event | CloudEvents với `type: com.ecommerce.cart.*` | Envelope tự định nghĩa với `event_type` và `stream_id` |

**Điều kiện để contract đạt `implemented` hoặc `verified`.** Bổ sung JSON Schema
cho năm event còn thiếu, hòa giải bảng lệch ở trên, có contract test hai chiều cho
provider và consumer, và có bước kiểm tra breaking change trong CI.
