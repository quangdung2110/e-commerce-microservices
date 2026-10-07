---
doc_id: cart-overview
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence:
  - docs/cart/scripts
verified_against_commit: null
---

# Cart Service — Service Overview

> Điểm bắt đầu để hiểu Cart Service. Thiết kế này đã được chốt ở mức tài liệu;
> trạng thái vẫn là `proposed` cho tới khi được đối chiếu với implementation.

## 1. Service status

- Runtime: Go, Gin, Redis, Kafka và Product Service gRPC.
- Redis là **nguồn chân lý duy nhất** của cart state. PostgreSQL không còn là
  dependency hay cold store của Cart.
- Kafka chỉ nhận event `CART_ABANDONED`; Notification Service là consumer duy
  nhất hiện tại.
- Cart data là ephemeral có kiểm soát: Redis phải bật AOF, replica và
  `maxmemory-policy noeviction`. Mất Redis toàn phần đồng nghĩa mất cart.

## 2. Mục tiêu

Cart lưu ý định mua của user đã đăng nhập với độ trễ thấp: SKU, quantity, snapshot
hiển thị và giá tại thời điểm thêm. Service phát đúng một tín hiệu
`CART_ABANDONED` cho Notification khi cart không hoạt động quá ngưỡng.

## 3. Boundary

Cart chịu trách nhiệm thêm, đặt quantity, xóa SKU theo lô, dọn SKU sau
`com.ecommerce.order.paid.v1`, đọc cart và đánh dấu abandoned. Cart không giữ chỗ tồn kho, không
tính khuyến mãi/tổng tiền, không là source of truth của giá hiện tại và không có
trách nhiệm phân tích.

Mọi business write nằm trong Redis Lua script. Một script thay đổi cart state,
metadata/lifecycle và (riêng lúc abandoned) Redis Stream outbox một cách nguyên tử.

## 4. Data ownership

| Kho lưu trữ | Dữ liệu | Vai trò |
|---|---|---|
| Redis hash | Item và metadata cart | **Source of truth** |
| Redis ZSET | Lịch quét cart inactive theo bucket | Scheduler state |
| Redis Streams | Record `CART_ABANDONED` chờ relay | Durable outbox |
| Kafka `t.cart.events` | Event đã relay | Transport cho Notification |
| Product Service | Giá hiện tại và catalog saleability | Source of truth external |

Không service nào được đọc hoặc ghi trực tiếp Redis key của Cart. Notification chỉ
đọc Kafka.

## 5. Capabilities

- Thêm SKU mới sau khi Product xác thực catalog; Cart không kiểm tra tồn kho.
- Đọc cart kèm giá hiện tại từ Product, suy giảm mềm khi Product lỗi.
- Dùng Redis idempotency record cho HTTP write.
- Đánh dấu abandoned bằng Redis ZSET, phát event nguyên tử và relay Kafka.
- Không phục hồi từ cold store: cart key không tồn tại trả cart rỗng.

## 6. Dependencies

| Direction | System | Protocol | Purpose |
|---|---|---|---|
| Inbound | API Gateway | HTTP | Identity đã xác thực và Cart API |
| Inbound | Order Service | Kafka | `com.ecommerce.order.paid.v1` để xóa SKU đã mua |
| Outbound | Product Service | gRPC | Validate catalog và phủ giá hiện hành khi đọc |
| Outbound | Redis | Redis protocol | Cart state, scheduler, idempotency, outbox |
| Outbound | Kafka `t.cart.events` | Kafka | Chỉ `CART_ABANDONED` |
| Outbound | Notification Service | Kafka consumer | Nhận `CART_ABANDONED` |

## 7. Critical invariants

- `cart_id = c1:<user_token>` là logical identity; Redis key vật lý có thể đổi.
- Một user có một cart; một `sku_id` chỉ xuất hiện một lần trong cart.
- `quantity` luôn trong `1..max_qty`; cart không quá `max_skus`.
- `unit_price_at_add` và `added_at` bất biến sau khi tạo item.
- Cart có thể giữ SKU hết tồn; Checkout từ chối preview `REJECT_ALL`, còn Order/Inventory quyết định Reserve cuối cùng.
- Chỉ script Cart được ghi state; `GET` không gia hạn TTL.
- `CART_ABANDONED` chỉ phát trong `mark_abandoned.lua`, cùng lúc với transition
  `ACTIVE -> ABANDONED`, và Notification dedupe theo `event_id`.

## 8. Document map

| Document | Nội dung |
|---|---|
| [Architecture](./architecture.md) | Component, Cluster routing và worker loops |
| [Domain](./domain.md) | Rule, lifecycle và error |
| [Interfaces](./interfaces.md) | HTTP, gRPC, Kafka event |
| [Workflows](./workflows.md) | Luồng end-to-end |
| [Data](./data.md) | Redis keyspace, TTL, idempotency, outbox |
| [Operations](./operations.md) | SLO, persistence, relay/trimmer/marker |
| [Development](./development.md) | Local development và test |
| [Decisions](./decisions/README.md) | ADR |

Quyết định ranh giới catalog/tồn kho hiện hành: [ADR-012](./decisions/ADR-012-catalog-only-cart-validation.md).

## 9. Known gaps

- Tài liệu chưa được xác minh với implementation Go.
- `USER_DELETED` chưa có workflow xóa cart keys và schedule index.
- Lua/OpenAPI v1 đã chốt delta semantics; implementation hiện hữu vẫn cần migrate
  và có contract test trước khi được đánh dấu conformant.
