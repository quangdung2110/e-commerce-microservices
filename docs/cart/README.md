---
doc_id: cart-overview
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence:
  - go/services/cart-service
verified_against_commit: null
---

# Cart Service — Service Overview

> Điểm bắt đầu để developer hoặc AI hiểu Cart Service trong 5–10 phút. Tài liệu
> mô tả thiết kế đang được triển khai; nội dung chưa được đối chiếu với source
> code nên trạng thái vẫn là `proposed`.

## 1. Service status

- Trạng thái service: `running`. Implementation nằm tại
  [`go/services/cart-service`](../../go/services/cart-service).
- Runtime: Go, HTTP framework Gin, client gRPC cho Product Service. Lý do chọn
  runtime ghi tại [ADR-006](./decisions/ADR-006-golang-runtime.md).
- Runtime dependencies: Redis (primary store), PostgreSQL (cold store), Kafka
  (event transport), Product Service (gRPC).
- Trạng thái tài liệu: `proposed`. Chưa có bước xác minh tài liệu ↔ code, nên
  không mục nào trong bộ tài liệu này được coi là `verified`.
- Contract adoption: `pilot`. HTTP có
  [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml); event có
  [AsyncAPI v1](../../contracts/events/cart/v1/asyncapi.yaml) nhưng mới có JSON
  Schema cho một event.

## 2. Mục tiêu

Cart Service giữ ý định mua hàng của người dùng đã đăng nhập: user muốn mua SKU
nào, số lượng bao nhiêu, thêm vào lúc nào. Service phải trả lời và cập nhật giỏ
hàng ở độ trễ rất thấp vì đây là màn hình người dùng ra vào liên tục, đồng thời
không được mất dữ liệu giỏ khi Redis bị evict hoặc restart.

Kết quả nghiệp vụ: cung cấp danh sách item chuẩn cho Checkout, giữ lịch sử giá
tại thời điểm thêm vào giỏ để hiển thị chênh lệch, và phát tín hiệu vòng đời giỏ
hàng cho Notification/Analytics.

## 3. Boundary

Cart Service chịu trách nhiệm:

- Vòng đời item trong giỏ: thêm, cộng dồn hoặc giảm số lượng, xóa theo lô, xóa sạch.
- Trạng thái vòng đời của một giỏ: `ACTIVE`, `ABANDONED`, `DELETED`, `CHECKED_OUT`.
- Snapshot hiển thị của sản phẩm tại thời điểm thêm vào giỏ.
- Phát event thay đổi giỏ hàng cho downstream.

Cart Service **không** chịu trách nhiệm:

- Giá bán và trạng thái khả dụng thời gian thực — thuộc Product Service; Cart chỉ
  lấy theo Pull Model khi user đọc giỏ ([ADR-005](./decisions/ADR-005-pull-model-product-data.md)).
- Giữ chỗ tồn kho — thuộc Inventory Service. Kiểm tra tồn kho lúc thêm vào giỏ chỉ
  dựa trên snapshot hiển thị, không phải cam kết bán hàng.
- Tính khuyến mãi, phí và tổng tiền — thuộc Promotion và Checkout Service.
- Quyết định đơn hàng: Cart không tự xóa giỏ sau checkout mà phản ứng theo event
  `ORDER_PAID` từ Order Service.

Transaction boundary của một business write nằm gọn trong một Lua script trên
Redis. PostgreSQL là bản chiếu bất đồng bộ, không nằm trong transaction đó.

## 4. Data ownership

| Kho lưu trữ | Dữ liệu | Vai trò |
|---|---|---|
| Redis | Cart state: SKU, quantity, snapshot hiển thị, mốc thời gian | **Source of truth** |
| PostgreSQL | `carts`, `cart_items` | Cold store / projection để phục hồi và phân tích |
| Redis Streams | Outbox event chưa relay | Transport nội bộ |
| Kafka `t.cart.events` | Event đã phát ra ngoài | Transport, không phải database |
| Product Service | Giá hiện tại, trạng thái khả dụng, thông tin catalog | Source of truth của service khác |

Không service nào được đọc hoặc ghi trực tiếp Redis key hay database của Cart.
Chi tiết tại [Data Design](./data.md).

## 5. Capabilities

- Thêm SKU vào giỏ hoặc thay đổi số lượng trong một thao tác nguyên tử.
- Đọc toàn bộ giỏ kèm giá hiện tại, có suy giảm mềm khi Product Service lỗi.
- Phục hồi giỏ từ cold store khi Redis mất key, không sinh event rác.
- Xóa theo lô nhiều SKU trong một thao tác nguyên tử.
- Dọn các SKU đã mua khỏi giỏ sau khi đơn hàng được thanh toán.
- Đánh dấu giỏ bị bỏ quên và phát tín hiệu re-engagement.
- Đảm bảo cold store hội tụ đúng trạng thái Redis dù event trùng lặp hoặc sai thứ tự.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Client qua API Gateway | HTTP | CRUD giỏ hàng | Required | [OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml) |
| Inbound | Order Service | Kafka `t.order.events` | `ORDER_PAID` để dọn giỏ sau thanh toán | Required cho luồng checkout | [Interfaces](./interfaces.md#5-events-tiêu-thụ) |
| Outbound | Product Service | gRPC | Lấy giá và trạng thái khả dụng theo Pull Model | Required khi ghi, degradable khi đọc | [product_service.proto](../../contracts/grpc/ecommerce/product/v1/product_service.proto) |
| Outbound | Redis | Redis protocol | Primary store và outbox stream | Required | [Data](./data.md#7-caching) |
| Outbound | PostgreSQL | Database | Cold store | Required cho phục hồi và cronjob | [Schema](./schema/0001_initial_cart_schema.sql) |
| Outbound | Kafka `t.cart.events` | Kafka | Phát event vòng đời giỏ hàng | Required | [AsyncAPI v1](../../contracts/events/cart/v1/asyncapi.yaml) |
| Outbound | Notification Service | Kafka consumer | Nhận `CART_ABANDONED` | Optional by workflow | [Interfaces](./interfaces.md#4-events-phát-ra) |
| Outbound | Analytics Service | Kafka consumer | Nhận event giỏ hàng | Optional by workflow | [Interfaces](./interfaces.md#4-events-phát-ra) |

## 7. Critical invariants

- `CART-INV-001`: `cart_id = c1:<user_token>` là identity nghiệp vụ ổn định;
  nó không phải Redis key vật lý và không service nào được suy ngược `user_id`
  từ nó.
- `CART-INV-002`: một user có đúng một cart key; một `sku_id` xuất hiện tối đa một
  lần trong giỏ.
- `CART-INV-003`: quantity của mỗi item luôn nằm trong khoảng `1..max_qty`; không
  tồn tại item có quantity `<= 0` trong Redis.
- `CART-INV-004`: mọi state transition quan sát được của business write phải sinh
  đúng một outbox event tương ứng trong cùng Lua script; một batch command có thể
  sinh nhiều transition. Luồng rehydrate tuyệt đối không sinh event.
- `CART-INV-005`: cold store chỉ được cập nhật khi `stream_id` của event mới hơn
  giá trị đã ghi trên dòng tương ứng.
- `CART-INV-006`: `unit_price_at_add` và `added_at` là dữ liệu lịch sử, immutable
  sau khi item được tạo.

Giải thích và nơi enforcement tại [Domain Model](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Component, processing và consistency model | Khi thay đổi cấu trúc service |
| [Domain](./domain.md) | Quy tắc nghiệp vụ, state machine, domain error | Khi thay đổi business logic |
| [Interfaces](./interfaces.md) | HTTP, gRPC và event semantics | Khi tích hợp với service |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Khi sửa một use case |
| [Data](./data.md) | Ownership, Redis, PostgreSQL, outbox và idempotency | Khi sửa schema hoặc dữ liệu |
| [Operations](./operations.md) | SLO, alert, failure mode, worker và runbook | Khi deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy, build và test local | Khi phát triển service |
| [Decisions](./decisions/README.md) | Architecture Decision Records | Khi cần hiểu lý do của thiết kế |

## 9. Known gaps

- Toàn bộ bộ tài liệu này chưa được đối chiếu với implementation tại
  `go/services/cart-service`; `verified_against_commit` còn `null`.
- AsyncAPI của Cart mới có JSON Schema cho `CART_ITEM_ADDED`; năm event còn lại
  (`CART_ITEM_UPDATED`, `CART_ITEM_DELETED`, `CART_CLEARED`, `CART_CHECKED_OUT`,
  `CART_ABANDONED`) chưa có schema machine-readable.
- Chưa có DLQ monitor worker; thiết kế trước đây ghi nhận đây là hạng mục hoãn lại.
- Chưa có luồng xóa dữ liệu giỏ hàng theo `USER_DELETED` của Auth Service, nên
  yêu cầu xóa dữ liệu GDPR chưa được đáp ứng đầu-cuối.
- Outbox routing có 64 bucket cố định trong namespace `v1`. Thay đổi số bucket
  không phải configuration runtime; chỉ được thực hiện bằng namespace/routing
  version mới và quy trình drain outbox cũ.
- Chỉ hỗ trợ user đã đăng nhập; không có giỏ hàng cho khách vãng lai.
- Giá trị SLO, alert threshold, cấu hình runtime và command phát triển local
  đang là `TBD` vì chưa lấy từ hạ tầng và build configuration thật.
