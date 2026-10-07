---
doc_id: checkout-overview
service: checkout-service
scope: target
status: proposed
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/cart/v1/openapi.yaml
  - ../../contracts/http/user/v1/openapi.yaml
  - ../../contracts/grpc/ecommerce/product/v1/product_service.proto
  - ../../contracts/http/inventory/workflow/v1/openapi.yaml
  - ../../contracts/http/promotion/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
verified_against_commit: null
---

# Checkout Service — Service Overview

Checkout là **Backend for Frontend** của trang thanh toán. Nó ghép dữ liệu từ các service
sở hữu thành một báo giá ngắn hạn, cung cấp khay voucher theo đúng báo giá đó, và điều
phối submit idempotent sang Order. Checkout không sở hữu giỏ hàng, giá, tồn kho, khuyến
mãi, địa chỉ hay đơn hàng; nó chỉ sở hữu **trạng thái workflow ngắn hạn** của một lần
checkout trong Redis.

## 1. Service status

| Mục | Giá trị |
|---|---|
| Triển khai | `implemented`: `java/services/checkout-service`; compose chạy `checkout-service` (cổng host `8096`) và `checkout-redis` riêng |
| Runtime | Java 21, Spring Boot WebFlux, Resilience4j, Lettuce, grpc-java ([ADR-013](./decisions/ADR-013-java-spring-webflux-runtime.md)); module `java/services/checkout-service`, package `com.ecommerce.checkout` |
| Tài liệu | `proposed` |
| Contract adoption | `pilot` ([catalog](../../contracts/catalog/services.yaml)); contract test validate mọi response với OpenAPI và mọi fixture provider với OpenAPI/proto của provider |
| Gateway route | Bốn router và ba profile rate limit `checkout-*` chạy trong compose local; production chỉ bật theo [gateway matrix](../gateway/runtime-contract.md#5-ma-trận-định-tuyến-bắt-buộc) khi readiness và load test đạt |

## 2. Mục tiêu

- Một lần gọi từ frontend nhận đủ báo giá có thể hiển thị: sản phẩm, ảnh, giá, voucher,
  tổng tiền; frontend không tự ghép nhiều API hay tự tính tiền.
- Không tin bất kỳ số liệu nào từ client: quantity, giá, category, discount, địa chỉ và
  kế hoạch kho đều do server lấy từ service sở hữu.
- Chặn traffic "xem thử" trước khi chạm Order: Preview không ghi gì vào Order, Inventory
  hay Promotion.
- Bảo đảm mỗi báo giá tạo tối đa một đơn hàng logic, kể cả khi double-click, nhiều tab,
  mất mạng hay timeout.

## 3. Boundary

| Năng lực | Owner | Vai trò của Checkout |
|---|---|---|
| Item và quantity trong giỏ | Cart | Đọc checkout snapshot qua internal API; không đọc Redis của Cart, không sửa giỏ |
| Tên, ảnh, giá bán, giá niêm yết, saleability, `category_ids` | Product | Batch-read `GetBatchSkus`; dựng URL CDN từ storage key |
| Địa chỉ giao hàng và quyền sở hữu địa chỉ | User | Đọc address snapshot đã xác minh ownership |
| Kế hoạch kho, giữ hàng | Inventory | Gọi allocation preview advisory; không reserve |
| Rule, eligibility, discount, ví voucher, budget | Promotion | Tính discount 0/1 voucher; liệt kê voucher options; không save, claim hay reserve |
| Đơn hàng, Reserve, Payment attempt | Order (Payment) | Submit snapshot bất biến; relay Payment client data, không lưu secret |
| Báo giá ngắn hạn, khay voucher, submit idempotent, recovery | **Checkout** | System of record của workflow state trong Redis |

### Thao tác ngoài Checkout

Trang checkout gọi thẳng service sở hữu cho các thao tác sau; Checkout không proxy chúng.

| Thao tác trên trang | Route qua Gateway | Service |
|---|---|---|
| Đổi số lượng, xoá sản phẩm | `/api/v1/carts/mine`, `/api/v1/carts/mine/items` | Cart |
| Liệt kê, thêm, sửa địa chỉ | `/api/v1/users/me/addresses/**` | User |
| Lưu voucher `PUBLIC_SAVE` | `PUT /api/v1/me/promotion-vouchers/{promotion_id}` (body rỗng) | Promotion |
| Nhận voucher `LIMITED_CLAIM` | `POST /api/v1/me/promotion-voucher-claims` | Promotion |
| Thanh toán bằng Stripe, thanh toán lại, xem đơn | `/api/v1/orders/**` | Order |

Sau mỗi thao tác làm đổi dữ liệu đầu vào của báo giá, frontend gọi lại Preview.

## 4. Data ownership

- System of record: session báo giá, con trỏ active, payload submit bất biến, kết quả
  submit và bản replay idempotency của Preview, tất cả trong Checkout Redis có TTL
  ([data.md](./data.md)).
- Bản chụp Cart/Product/User/Inventory/Promotion chỉ nằm trong session của đúng token,
  không phải cache dùng chung và không được tái sử dụng cho token khác.
- Không có PostgreSQL, Kafka hay Temporal. Không service nào khác đọc hoặc ghi Redis của
  Checkout.

## 5. Capabilities

| ID | Capability | Kết quả |
|---|---|---|
| `CHK-CAP-01` | Preview | Báo giá bất biến, hết hạn sau 20 phút, có token riêng |
| `CHK-CAP-02` | Voucher options | Khay voucher (ví + công khai) đánh giá trên đúng báo giá |
| `CHK-CAP-03` | Submit | Tạo đúng một đơn logic tại Order và trả Payment client data |
| `CHK-CAP-04` | Submission recovery | Phân giải kết quả sau timeout mà không tạo đơn trùng |

| Method | Path | Mục đích |
|---|---|---|
| `POST` | `/api/v1/checkout/preview` | Tạo báo giá hiện hành của user |
| `GET` | `/api/v1/checkout/previews/{checkout_token}/voucher-options` | Khay voucher cho báo giá |
| `POST` | `/api/v1/checkout/submit` | Submit báo giá theo token |
| `GET` | `/api/v1/checkout/submissions/{checkout_token}` | Khôi phục kết quả submit |

Contract chuẩn: [Checkout OpenAPI](../../contracts/http/checkout/v1/openapi.yaml). Preview
chỉ nhận `previous_checkout_token`, `delivery_address_id`, `promotion_id`; Submit chỉ nhận
`checkout_token`.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Storefront qua Gateway | HTTP | Bốn API công khai ở trên | — | [Checkout OpenAPI](../../contracts/http/checkout/v1/openapi.yaml) |
| Outbound | Cart | HTTP nội bộ mTLS | Checkout snapshot | Required | [Cart OpenAPI](../../contracts/http/cart/v1/openapi.yaml) |
| Outbound | User | HTTP nội bộ mTLS (cổng internal) | Address snapshot | Required | [User OpenAPI](../../contracts/http/user/v1/openapi.yaml) |
| Outbound | Product | gRPC mTLS | `GetBatchSkus` | Required | [Product proto](../../contracts/grpc/ecommerce/product/v1/product_service.proto) |
| Outbound | Inventory | HTTP nội bộ mTLS | Allocation preview | Required | [Inventory Workflow OpenAPI](../../contracts/http/inventory/workflow/v1/openapi.yaml) |
| Outbound | Promotion | HTTP nội bộ mTLS | Discount calculation; voucher options | Required khi có `promotion_id`; voucher options tách riêng, lỗi không chặn Preview/Submit | [Promotion OpenAPI](../../contracts/http/promotion/v1/openapi.yaml) |
| Outbound | Order | HTTP nội bộ mTLS | Checkout order submission và recovery | Required cho Submit | [Order internal OpenAPI](../../contracts/http/order/internal/v1/openapi.yaml) |
| Outbound | Checkout Redis (riêng, `noeviction`) | RESP + Lua | Workflow state | Required | [data.md](./data.md) |

Quyền gọi do service mesh thực thi theo principal `checkout-service`
([mesh §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint)); Checkout gửi
`X-Service-Identity: checkout-service` cho attribution. Header đầy đủ ở
[interfaces.md](./interfaces.md#header-gửi-cho-dependency).

## 7. Critical invariants

| ID | Invariant |
|---|---|
| `CHK-INV-01` | Mọi số liệu tiền, quantity, category, địa chỉ và allocation đến từ service sở hữu; client chỉ gửi identifier. |
| `CHK-INV-02` | Mỗi user có tối đa một token chưa terminal (`PREVIEWED`, `SUBMITTING` hoặc `RETRYABLE`); con trỏ active luôn trỏ tới token đó ([ADR-016](./decisions/ADR-016-fresh-preview-and-in-flight-submission-guard.md)). |
| `CHK-INV-03` | Cùng token tạo tối đa một đơn logic: Order `Idempotency-Key` bằng chính `checkout_token` và payload bất biến từ lúc claim. |
| `CHK-INV-04` | Mọi dependency của Preview/Submit fail-closed: lỗi hạ tầng không bao giờ biến thành "không giảm giá" hay "đủ hàng"; không có degraded quote. |
| `CHK-INV-05` | Không phí ship; `final_amount = total_amount − total_discount ≥ 1 VND`. |
| `CHK-INV-06` | Không lưu Payment `client_secret` ở bất kỳ đâu; raw token và PII không nằm trong Redis key, log, metric label hay trace attribute. |
| `CHK-INV-07` | Timeout ứng dụng luôn ngắn hơn timeout mesh; deadline nội bộ ngắn hơn timeout Gateway ít nhất một giây ([ADR-014](./decisions/ADR-014-resilience-budget-and-mesh-gateway-split.md)). |
| `CHK-INV-08` | Sau khi claim, lời gọi Order không bị hủy theo client; kết quả luôn được ghi lại. |
| `CHK-INV-09` | Khay voucher chỉ đọc và chỉ advisory; discount chính thức luôn đến từ Preview. |

Giải thích chi tiết ở [domain.md](./domain.md#luật-nghiệp-vụ).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Thành phần, wave gọi song song, phân vai với mesh/gateway | Khi sửa luồng gọi hoặc kiến trúc |
| [Domain](./domain.md) | Thuật ngữ, state machine, luật nghiệp vụ | Khi đổi business logic |
| [Interfaces](./interfaces.md) | Public API, nguồn từng field, error catalog, mapping downstream | Khi tích hợp frontend hoặc downstream |
| [Workflows](./workflows.md) | Preview, voucher, Submit, recovery, tải lại trang | Khi sửa một use case |
| [Data](./data.md) | Redis keyspace, TTL, Lua/CAS, mã hoá | Khi sửa state hoặc TTL |
| [Operations](./operations.md) | SLO, ma trận lỗi, resilience, metric, cấu hình, runbook | Khi deploy hoặc xử lý sự cố |
| [Development](./development.md) | Stack, chạy local, test bắt buộc, Definition of Done | Khi phát triển service |
| [Decisions](./decisions/README.md) | ADR hiện hành và lịch sử | Khi cần lý do của thiết kế |

## 9. Known gaps

- Mọi số timeout, SLO và bulkhead là giá trị khởi điểm, phải được load test xác nhận trước khi
  bật route production.
- Chưa có kiểm thử end-to-end tự động qua Cart, User, Product, Inventory, Promotion và Order
  thật: các test của Checkout dùng fixture đã được validate với contract của từng provider
  (xem [development.md](./development.md#test-bắt-buộc)).
- Cổng internal của User giới hạn `internal_per_client` 600 request/phút theo IP, dùng chung cho
  mọi pod Checkout; ở mức 20 RPS Preview mỗi pod sẽ gặp `429` và Checkout trả `503`. Nâng giới
  hạn này cho principal `checkout-service` trước load test.
- Product gRPC không có nhãn biến thể: báo giá trả `variant_attributes` dạng mã → giá trị
  và Order nhận `variant_name` vắng mặt cho tới khi Product bổ sung nhãn.
- User snapshot không có tên tỉnh/huyện/xã; Order nhận `province_name`, `district_name`,
  `ward_name` là `null`.
- Chưa có tier authority: `user_tier` luôn omit, rule theo tier không match.
- Rate limit Gateway hiện là in-memory per replica; production cần Redis-backed shared
  state như [gateway §7](../gateway/runtime-contract.md#7-chính-sách-rate-và-concurrency).
