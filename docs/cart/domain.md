---
doc_id: cart-domain
service: cart
scope: current
status: implemented
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/internal/domain
  - docs/cart/scripts
verified_against_commit: null
---

# Cart Service — Domain Model

## 1. Ubiquitous language

| Term | Nghĩa |
|---|---|
| Cart | Ý định mua hiện thời của một authenticated user trong Redis |
| Cart item | Một SKU và quantity duy nhất trong cart hash |
| Snapshot | Dữ liệu Product đã thấy khi item được thêm |
| Validation freshness | Khoảng thời gian Cart được tái sử dụng kết quả Product |
| Abandonment cycle | Một lần `ACTIVE -> ABANDONED`; tăng khi user quay lại rồi inactive lần nữa |
| Outbox record | Redis Stream entry bền trước khi relay Kafka |

## 2. Domain objects

`Cart` gồm `items` hash và `meta` hash cùng bucket. `CartItem` chứa quantity,
snapshot display, giá lịch sử và freshness metadata. Không có entity cart hoặc item
trong database.

## 3. Commands và queries

| Kind | Operation | Actor |
|---|---|---|
| Query | Đọc cart của mình | Authenticated user |
| Command | Thêm SKU hoặc điều chỉnh quantity theo delta | Authenticated user |
| Command | Xóa nhiều SKU | Authenticated user |
| Command | Xóa SKU đã mua | `com.ecommerce.order.paid.v1` consumer |
| Command | Xóa toàn bộ giỏ của account đã xóa | `com.ecommerce.auth.user-deleted.v1` consumer |
| Command | Đánh dấu abandoned | Marker worker |
| Query | Đọc `{product_id, sku_id, quantity}` cho Checkout | Checkout Service (nội bộ) |

## 4. Business rules

| ID | Rule |
|---|---|
| `CART-BR-001` | Quantity luôn `1..100`; delta dương chạm trần được clamp về 100, delta làm quantity không dương bị từ chối |
| `CART-BR-002` | Cart có tối đa 50 SKU |
| `CART-BR-003` | SKU mới phải được Product xác nhận Product/SKU đang active; Cart không kiểm tra stock |
| `CART-BR-004` | SKU đã có dùng validation còn tươi, không gọi Product cho mọi click tăng quantity |
| `CART-BR-005` | Giá lúc thêm và thời điểm thêm không đổi khi update quantity |
| `CART-BR-006` | Xóa SKU không tồn tại là idempotent |
| `CART-BR-007` | `com.ecommerce.order.paid.v1` chỉ xóa SKU đã mua; cart còn item vẫn active |
| `CART-BR-008` | GET không gia hạn TTL; write và reactivation có gia hạn |
| `CART-BR-009` | `CART_ABANDONED` chỉ phát sau 30 ngày inactive |
| `CART-BR-010` | Khi nhận `USER_DELETED`, toàn bộ giỏ của user bị xóa (items, meta, lịch abandoned) và thao tác này idempotent |
| `CART-BR-011` | Mỗi user bị giới hạn 20 request/giây (burst 40) trên mỗi pod cho `/api/v1/carts/mine/**`; vượt giới hạn trả `RATE_LIMITED` |

## 5. Invariants

- `cart_id = c1:<user_token>` là identity nghiệp vụ; không nhận từ public client.
- Hash field bảo đảm mỗi `sku_id` chỉ có một item.
- Không ghi cart state ngoài Lua script.
- State transition abandoned và outbox `XADD` cùng thành công hoặc cùng thất bại.
- Outbox at-least-once; Notification dedupe xác định theo event ID.

## 6. State machines

### Cart lifecycle trong `meta.status`

| State | Transition | Trigger |
|---|---|---|
| *(không tồn tại)* | `ACTIVE` | Add SKU đầu tiên |
| `ACTIVE` | `ABANDONED` | Marker đến deadline ZSET |
| `ABANDONED` | `ACTIVE` | User thêm/đổi quantity, hoặc xóa SKU mà cart vẫn còn item, trong TTL retention |
| `ACTIVE` / `ABANDONED` | *(không tồn tại)* | Cart rỗng, Redis TTL expire hoặc `USER_DELETED` |

`DELETED` và `CHECKED_OUT` không được lưu trạng thái: chúng là kết quả xóa key, vì
không còn cold-store retention hay event consumer cho hai trạng thái này.

### Cart item lifecycle

SKU có hai trạng thái: field tồn tại hoặc không tồn tại. Xóa là hard delete trong
Redis; không cần tombstone vì không có asynchronous projection để hội tụ.

## 7. Authorization rules

Mọi API công khai yêu cầu user đã đăng nhập. Gateway inject identity; Cart tự dựng token,
bucket và cart ID. Endpoint nội bộ chỉ nhận caller trong allowlist `cart.internal_callers`
(mặc định `checkout-service`). Order-paid và user-deleted consumer dựng cart từ trusted
`user_id`. Marker và relay chạy system identity.

## 8. Domain errors

| Code | Điều kiện |
|---|---|
| `CART_FULL` | SKU mới vượt `max_skus` |
| `PRODUCT_SNAPSHOT_REQUIRED` | SKU mới hoặc delta dương trên validation hết hạn cần Product refresh nội bộ |
| `DELTA_INVALID` | Delta bằng 0 hoặc sai kiểu |
| `QUANTITY_INVALID` | Delta làm quantity không còn dương; client phải dùng DELETE |
| `ITEM_NOT_FOUND` | Delta âm gửi cho SKU không có trong cart |
| `PRODUCT_NOT_AVAILABLE` | Product/SKU không active theo catalog, hoặc Product không biết SKU |
| `IDEMPOTENCY_CONFLICT` | Cùng `Idempotency-Key` đã được dùng với payload khác (không có trạng thái "đang xử lý": ghi là nguyên tử) |

Các mã còn lại xuất hiện ở biên HTTP hoặc là lỗi hạ tầng, không phải quy tắc nghiệp vụ:

| Code | Điều kiện |
|---|---|
| `UNAUTHENTICATED` | Thiếu trusted identity (`X-User-Id` hoặc `X-Service-Identity`) |
| `FORBIDDEN` | `X-Service-Identity` không nằm trong allowlist của endpoint nội bộ |
| `VALIDATION_FAILED` | Request sai cú pháp: header `Idempotency-Key`, body, `delta`, `sku_ids`, `user_id` |
| `RATE_LIMITED` | Vượt rate limit theo user (`CART-BR-011`) |
| `CART_STORE_UNAVAILABLE` | Redis lỗi hoặc timeout |
| `PRODUCT_UNAVAILABLE` | Product gRPC lỗi, timeout hoặc circuit breaker mở khi ghi |
| `NOT_FOUND` | Không có route khớp |
| `INTERNAL_ERROR` | Lỗi không dự kiến |

Mapping sang HTTP status và `retryable` nằm ở [interfaces.md](./interfaces.md) §2.1.
Product lỗi khi `GET` tạo response `DEGRADED`; không phải domain error. Redis hoặc
Kafka lỗi là infrastructure error.

## 9. Business examples

Ba click tăng liên tiếp trên SKU đã có đi qua Lua và không mất update; Product không
bị gọi nếu validation còn tươi. Sau 30 ngày không activity, marker atomically đổi
status, `XADD` một entry; Relay retry Kafka cho tới khi Notification nhận event.
