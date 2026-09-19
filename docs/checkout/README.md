---
doc_id: checkout-overview
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence: []
verified_against_commit: null
---

# Checkout Service — Tổng quan dịch vụ

> Đây là điểm bắt đầu để hiểu thiết kế mục tiêu của Checkout Service. Bộ tài liệu
> được tái cấu trúc từ năm tài liệu thiết kế Checkout cũ (architecture, workflow,
> caching schema, idempotency, security analysis) và ghi chú kèm theo; toàn bộ
> quyết định của thiết kế cũ được giữ nguyên, chưa được xác minh với một
> implementation hiện hữu và chưa nhằm thống nhất contract giữa các service.

## 1. Trạng thái dịch vụ

Checkout Service đang ở mức thiết kế `proposed`, phạm vi `target`. Chưa tìm thấy
mã nguồn Checkout Service, cấu hình Redis hay cấu hình triển khai trong repository
để chứng minh runtime, framework, port hoặc hành vi production. Thiết kế cũ chỉ
nêu hai lựa chọn runtime tương đương (`Goroutines` hoặc `CompletableFuture`) cho
việc gọi song song downstream; runtime chưa được chốt.

Contract adoption đang ở mức `not_started`: Checkout chưa có OpenAPI cho
`POST /v1/checkout/preview` và `POST /v1/checkout/submit`; các contract mà
Checkout tiêu thụ (Promotion, Inventory, Product, User) đã tồn tại ở nhiều mức
khác nhau và được liệt kê trong `interfaces.md`.

## 2. Mục tiêu

Trong hệ thống E-Commerce quy mô lớn, tính năng “Thanh toán/Chốt đơn” được tách
biệt hoàn toàn khỏi “Quản lý Đơn hàng” (Order Service) để tối ưu hóa hiệu năng,
bảo vệ sổ cái dữ liệu và xử lý hàng triệu lượt xem trước giỏ hàng (Checkout
Preview) trong các kỳ Flash Sale.

Checkout Service giải quyết ba vấn đề:

- Khách hàng thao tác liên tục trên màn hình Checkout: đổi địa chỉ, thử 5–7 mã
  giảm giá khác nhau, so sánh số tiền phải trả. Nếu những tương tác “nháp” này
  gõ trực tiếp vào Order Service, PostgreSQL của Order sẽ bị vắt kiệt IOPS dẫn
  đến timeout khi khách hàng khác đang thực sự tạo đơn.
- Frontend (Mobile App) nếu không có Checkout BFF sẽ phải gọi nhiều API riêng lẻ
  (Cart, Promotion, Inventory, User) và tự xử lý dependency warehouse selection
  theo từng item, gây lỗi bảo mật (client bị hack đổi giá), logic App nặng nề,
  tốn pin và băng thông.
- Mọi phép trừ phần trăm giảm giá, tính tổng tiền phải được tính bởi Backend để
  ngăn chặn client truyền số sai lệch (tampering).

Kết quả nghiệp vụ: một bảng báo giá (Quotation) được server công nhận và niêm
phong bằng `checkout_token`; một lệnh tạo đơn duy nhất được gửi sang Order Service
cho mỗi phiên checkout; 99% lượng “traffic xem thử” bị chặn trước khi chạm vào
CSDL của Order Service.

## 3. Ranh giới trách nhiệm

Checkout Service chịu trách nhiệm:

- **Data Aggregation:** nhận một request duy nhất từ Frontend, song song gọi
  Cart, Promotion, Inventory (và User, Product) để lấy thông số và ráp lại thành
  bảng báo giá.
- **Idempotency & Rate Limiting:** validation ban đầu (check token, check JWT,
  chống spam click).
- **Tính toán tập trung:** mọi số tiền được tính server-side; client chỉ gửi
  identifier.
- **Boundary ghi dữ liệu rõ ràng:** Checkout chỉ orchestration tính toán và commit
  đơn. Việc ghi mới vào ví voucher không được làm ngầm ở Submit.
- **Conflict Recovery có chủ đích:** khi reserve voucher fail trong Saga
  (all-or-nothing), Checkout gọi lại Promotion evaluate để trả
  `409 PROMOTION_RECALCULATION_NEEDED` với `dropped_promotions[]` và đề xuất tổng
  tiền mới; không dùng phép trừ cục bộ.
- **Cô lập dội tải:** chặn traffic xem thử trước khi nó chạm vào Order Service.

Checkout Service không chịu trách nhiệm:

- Tăng/giảm số lượng, thêm/xóa sản phẩm: thuộc Cart Service. Checkout chỉ nhận
  snapshot SKU + quantity đã chuẩn hóa từ Cart để tính báo giá.
- Quản lý vòng đời đơn hàng, lưu database đơn, xử lý giao dịch phân tán (Temporal
  Saga): thuộc Order Service.
- Khóa tồn kho (hard allocation) và khóa ngân sách khuyến mãi (reserve budget):
  chỉ xảy ra trong Temporal Saga của Order sau Submit.
- Ghi ví voucher (`user_coupon_wallets`): chỉ Promotion ghi qua explicit action
  (Save & Apply, claim, campaign worker).
- Chọn payment provider: hệ thống chỉ dùng Stripe (Phase 18); provider được fix
  cứng ở Order Service và Payment Service.
- Cache địa chỉ người dùng: User Service sở hữu cache này; Checkout không duy trì
  layer cache riêng cho address.

Checkout không có transaction quan hệ. Ranh giới nhất quán duy nhất là các thao tác
atomic trên Redis (Lua script, `SETNX`, `HSET`). Mọi side effect bền vững (tạo
order, reserve tồn kho/budget) thuộc Order Service và các service downstream.

| Tiêu chí | Checkout Service | Order Service |
|---|---|---|
| Bản chất | BFF / API Aggregator / Stateless | Core Domain / RDBMS / Event-Driven / Stateful |
| Nhiệm vụ chính | Hiển thị bảng tính tiền, bắt lỗi logic trước khi chốt, tạo token bảo mật | Quản lý vòng đời đơn (State Machine), lưu database, xử lý giao dịch phân tán (Temporal Saga) |
| Database | Redis (lưu session Checkout) | PostgreSQL (+ CQRS Elasticsearch theo tài liệu cũ) |
| Tần suất gọi | Rất cao (thử mã, đổi kho) | Thấp hơn (chỉ khi user xác nhận mua hàng) |

## 4. Quyền sở hữu dữ liệu

Checkout Service không sở hữu database chứa dữ liệu bền vững nào. Mọi dữ liệu
lưu trữ đều nằm trên Redis với TTL.

| Nhóm dữ liệu | Nguồn chân lý | Ghi chú |
|---|---|---|
| `checkout:session:{user_id}` | Checkout (tạm thời, TTL 20 phút) | Bản nháp báo giá được server công nhận; token, status, pricing snapshot |
| `checkout:payload:{user_id}:{token}` | Checkout (tạm thời) | Payload đã khóa gửi sang Order để phục hồi khi rớt mạng |
| `checkout:response:{user_id}:{token}` | Checkout (tạm thời) | Idempotent response (success hoặc conflict nghiệp vụ) |
| `checkout:routing:{items_hash}:{address_hash}` | Inventory (Checkout chỉ cache) | Downstream response cache của routing/allocation |
| `checkout:lock:*`, `checkout:ratelimit:*` | Checkout (guard tạm thời) | Distributed lock và rate-limit counter |
| Giỏ hàng | Cart Service | Checkout đọc snapshot, không ghi |
| Giá SKU, trạng thái sản phẩm | Product Service | Checkout đọc qua gRPC, không cache riêng |
| Địa chỉ giao hàng và cache địa chỉ | User Service | Checkout đọc qua internal API; không double cache |
| Ví voucher, ngân sách, rule khuyến mãi | Promotion Service | Checkout chỉ gọi evaluate/discovery; không ghi ví, không reserve ở Preview |
| Tồn kho và routing | Inventory Service | Soft allocation ở Preview; hard allocation trong Saga |
| Order, payment | Order/Payment Service | Checkout chỉ giữ `order_id` + định danh attempt trong response cache; `client_secret`/`publishable_key` relay từ Order, không lưu ([ADR-010](./decisions/ADR-010-relay-payment-client-data-no-secret-cache.md)) |

Service khác không đọc/ghi trực tiếp Redis keyspace của Checkout. Checkout không
query database của service khác.

## 5. Năng lực cung cấp

| Capability | Kết quả nghiệp vụ |
|---|---|
| Checkout Preview | Gom dữ liệu Cart/User/Product/Inventory/Promotion, tính giá waterfall, sinh `checkout_token` và lưu session Redis |
| Cập nhật phiên checkout | Đổi địa chỉ hoặc đổi voucher PLATFORM trên phiên hiện hành, chỉ gọi lại downstream cần thiết, sinh token mới |
| Voucher tray | Cung cấp tab “Voucher của tôi” và “Khám phá” qua Promotion; cho phép “Lưu & Chọn” rồi preview lại |
| Checkout Submit | Xác thực token one-time-use, kiểm tra giá stale, khóa token, gửi payload sang Order Service để kích hoạt Saga |
| Idempotent response | Trả lại đúng kết quả cũ (success hoặc conflict nghiệp vụ) khi client retry cùng token |
| Failed submit recovery | Dùng payload đã lưu để gọi lại Order khi lần gọi đầu rớt mạng, không tạo đơn trùng |
| Promotion conflict recovery | Khi reserve voucher fail, gọi lại Promotion evaluate và trả `dropped_promotions[]` + đề xuất tổng mới |
| Degraded pricing | Khi Promotion/Inventory timeout, trả trạng thái degraded và yêu cầu user xác nhận trước khi Submit |

## 6. Phụ thuộc

| Hướng | Service/Hệ thống | Giao thức | Mục đích | Bắt buộc/Tùy chọn | Contract |
|---|---|---|---|---|---|
| Inbound | Frontend (Web/App) qua Gateway | HTTP | Preview, Submit, voucher tray | Bắt buộc | Chưa có OpenAPI Checkout |
| Outbound | Cart | Redis read (`HGETALL cart:user:{id}`) theo tài liệu cũ | Đọc snapshot giỏ hàng | Bắt buộc | [Cart Data](../cart/data.md) |
| Outbound | User | HTTP nội bộ mTLS | Danh sách địa chỉ, validate ownership và snapshot address | Bắt buộc | [User OpenAPI v1](../../contracts/http/user/v1/openapi.yaml) |
| Outbound | Product | gRPC | Batch fetch SKU: `is_available`, `status`, giá hiện tại | Bắt buộc | [Product gRPC v1](../../contracts/grpc/ecommerce/product/v1/product_service.proto) |
| Outbound | Inventory | HTTP nội bộ | Routing/allocation preview theo từng nhóm kho (soft allocation) | Bắt buộc | [Inventory Workflow OpenAPI v1](../../contracts/http/inventory/workflow/v1/openapi.yaml) — proposed |
| Outbound | Promotion | HTTP nội bộ | Tính discount (Preview, re-evaluate sau reserve fail) | Bắt buộc khi có voucher | [Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) — implemented |
| Outbound | Promotion | HTTP (customer API qua Gateway) | Voucher suggestions, discovery, Save & Apply | Tùy chọn (tray) | [Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) |
| Outbound | Order | Internal HTTP mTLS | Tạo đơn từ checkout payload, kích hoạt Temporal Saga | Bắt buộc | [Order internal OpenAPI v1](../../contracts/http/order/internal/v1/openapi.yaml) |
| Internal | Redis | Redis commands, Lua | Session, lock, rate limit, response/payload cache, routing cache | Bắt buộc | Key/TTL trong `data.md` |
| Edge | API Gateway | HTTP | JWT auth, rate-limit profile `promotion-discovery` | Bắt buộc | [Gateway Runtime Contract](../gateway/runtime-contract.md) |

Không có Kafka producer/consumer, PostgreSQL hay Temporal worker trong Checkout.

## 7. Bất biến quan trọng

- [`INV-CHK-001`](./domain.md#5-invariants): mỗi `checkout_token` chỉ được dùng
  để submit đúng một lần; chuyển `PREVIEW_READY → SUBMITTED` là atomic và không
  rollback.
- [`INV-CHK-002`](./domain.md#5-invariants): mỗi user chỉ có một checkout session
  hiện hành (`checkout:session:{user_id}`); Preview với `checkout_token = null`
  khi đã có session → `409`.
- [`INV-CHK-003`](./domain.md#5-invariants): mọi số tiền gửi sang Order Service
  đều đến từ `pricing_summary` do server tính ở Preview; không tin amount từ
  client.
- [`INV-CHK-004`](./domain.md#5-invariants): Submit chỉ chạy Lua check-and-mark
  sau khi `pricing_hash` tính lại khớp với session; hash lệch → `409 PRICE_CHANGED`
  và session vẫn `PREVIEW_READY`.
- [`INV-CHK-005`](./domain.md#5-invariants): Preview không khóa tồn kho (soft
  allocation) và không khóa ngân sách khuyến mãi; Reserve trong Saga là nguồn
  quyết định cuối cùng.
- [`INV-CHK-006`](./domain.md#5-invariants): Submit không ghi ví voucher; khi
  reserve fail Checkout không làm phép trừ cục bộ mà gọi lại Promotion evaluate.
- [`INV-CHK-007`](./domain.md#5-invariants): Checkout không mutate `quantity`;
  payload cố mutate quantity → `400 INVALID_CHECKOUT_MUTATION_SCOPE`.
- [`INV-CHK-008`](./domain.md#5-invariants): Submit ở chế độ degraded
  (`DEGRADED_PROMOTION`/`DEGRADED_STOCK`) bắt buộc mang
  `user_ack_degraded_pricing = true`.

## 8. Bản đồ tài liệu

| Tài liệu | Nội dung | Khi cần đọc |
|---|---|---|
| [Kiến trúc](./architecture.md) | BFF/Aggregator, parallel orchestration, Redis-only, lock + Lua, consistency | Khi thay đổi cấu trúc hoặc dependency |
| [Miền nghiệp vụ](./domain.md) | Session, token, pricing hash, rule, state machine và lỗi miền | Khi thay đổi business logic |
| [Giao diện](./interfaces.md) | Preview/Submit semantics, downstream contract, error code | Khi tích hợp service |
| [Luồng nghiệp vụ](./workflows.md) | Preview, Submit, retry/recovery, reserve fail, degraded | Khi sửa use case |
| [Dữ liệu](./data.md) | Redis session schema, cache, lock, rate limit, lifecycle | Khi sửa key/TTL |
| [Vận hành](./operations.md) | Timeout/fallback, failure mode, degraded behavior, runbook | Khi deploy hoặc xử lý sự cố |
| [Phát triển](./development.md) | Checklist build/test khi có code | Khi bắt đầu implementation |
| [Quyết định](./decisions/README.md) | ADR bảo toàn lý do của thiết kế cũ | Khi đánh giá trade-off |

## 9. Khoảng trống đã biết

- Chưa có implementation, OpenAPI Checkout, cấu hình Redis, deployment hoặc test
  để xác minh; runtime (`Goroutines` hay `CompletableFuture`) chưa chốt.
- Inventory Preview dùng `POST /internal/inventory/v1/allocation-previews`; Checkout
  lưu và gửi canonical `allocation_plan_id` cùng line assignments sang Order.
- Tài liệu cũ gọi RPC Product là `GetBatchProductSkus`; proto hiện có định nghĩa
  `GetBatchSkus`. Tên RPC và field (`original_price`, `promotional_price`,
  `best_price`, `current_price`) cần đối soát.
- Checkout gọi Order bằng `POST /internal/v1/checkout-order-submissions` với mTLS
  principal `checkout-service`; `Idempotency-Key` bằng `checkout_token`.
- Bảng timeout Preview còn giữ fallback “Flat-rate config” cho Inventory từ thời
  còn Shipping Service (đã bỏ ở Phase 14); ý nghĩa fallback này chưa được định
  nghĩa lại.
- Session field vừa được mô tả là `selected_voucher_id` (scalar) vừa là
  `selected_vouchers` (map); cần chuẩn hóa riêng khi viết Checkout OpenAPI.
- Rate limit Preview được gọi là “sliding window” nhưng Lua script mô tả là fixed
  window `INCR` + `EXPIRE`.
- Cả rate limit và Preview lock đều trả `429`; chưa phân biệt error code.
- `GET /checkout/warehouse-assignments` được nhắc tới như ví dụ selective read
  nhưng không có mô tả API.
- Tham chiếu tới `docs/_contracts/CHANGELOG.md#phase-18` của tài liệu cũ không còn
  tồn tại; Phase 18 (Stripe duy nhất) được bảo toàn trong
  [ADR-008](./decisions/ADR-008-single-payment-provider-stripe.md).
- Chưa có SLO số, capacity benchmark, cấu hình alert, runbook tự động hóa hoặc
  chiến lược Redis HA cho keyspace Checkout.
