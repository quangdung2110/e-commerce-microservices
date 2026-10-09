---
doc_id: product-detail-bff-overview
service: product-detail-bff
scope: current
status: implemented
owners:
  - Storefront team
  - Product Service team
  - Inventory Service team
evidence:
  - java/services/product-detail-bff
  - contracts/http/product-detail-bff/v1/openapi.yaml
  - docker-compose.yml
---

# Product Detail BFF

Product Detail BFF là read-composition layer cho trang chi tiết sản phẩm (PDP). Nó
trả catalog và giá từ Product (gồm category breadcrumb, bảng thông số, trục biến thể và
gallery do Product resolve), trạng thái khả dụng advisory theo SKU từ Inventory
Availability Read API, cùng rating aggregate và preview hữu hạn từ Review. BFF không
sở hữu catalog, tồn kho, Review domain, database nghiệp vụ hoặc Inventory projection.

BFF chỉ phục vụ phần **đọc** của PDP qua một endpoint `GET`. Mọi thao tác khác trên
trang, kể cả thao tác chỉ đọc như phân trang review, đi thẳng từ storefront qua Gateway
tới service sở hữu, không qua BFF (xem [Thao tác ngoài BFF](#thao-tác-ngoài-bff)).

## Boundary

| Năng lực | Owner | Vai trò của BFF |
|---|---|---|
| Nội dung Product, category path, thuộc tính, trục biến thể, media, giá công bố, catalog lifecycle | Product | Đọc projection đã resolve qua internal buyer-read API; chỉ dựng URL CDN từ storage key |
| Quy tắc `is_in_stock`, quantity, reserve và quyết định bán cuối cùng | Inventory | Batch-read trạng thái advisory; không gửi command reserve |
| Review content, eligibility, moderation và rating aggregate | Review | Đọc summary/preview qua internal API; không lưu hay nhận command Review |
| Browse, search, facet, suggestion | Search | Không gọi trong PDP hot path |
| PDP HTTP response | Product Detail BFF | Ghép catalog, availability và Review enrichment theo policy storefront |

PDP không đưa stock về Product. Khi buyer checkout, Checkout gọi Inventory allocation
preview và Order mới Reserve; `IN_STOCK` trên PDP là UI state, không phải xác nhận có thể mua.

## Thao tác ngoài BFF

| Thao tác trên PDP | Route qua Gateway | Service |
|---|---|---|
| Phân trang, lọc review | `GET /api/v1/reviews/products/**` | Review |
| Viết/sửa/report review, upload media | `/api/v1/reviews/**` | Review |
| Thêm, bớt, xoá SKU trong giỏ; xem giỏ | `/api/v1/carts/mine`, `/api/v1/carts/mine/items` | Cart |
| Lưu hoặc nhận voucher | `/api/v1/me/promotion-vouchers/**`, `/api/v1/me/promotion-voucher-claims` | Promotion |
| Checkout Preview và Submit | `/api/v1/checkout/preview`, `/api/v1/checkout/submit` | Checkout |

Route và policy chuẩn nằm trong
[gateway matrix](../gateway/runtime-contract.md#5-ma-trận-định-tuyến-bắt-buộc). Nút
thêm vào giỏ theo availability tuân theo `PDP-BR-009` trong [Domain](./domain.md).

## Dependencies

| Direction | Service/System | Protocol | Purpose |
|---|---|---|---|
| Inbound | Storefront qua Gateway | HTTP | Đọc Product Detail theo slug |
| Outbound | Product | HTTP nội bộ mTLS | Buyer-read catalog, category path, thuộc tính, trục biến thể, media giới hạn và giá |
| Outbound | Inventory | HTTP nội bộ mTLS | Batch-read advisory availability cho các SKU active |
| Outbound | Review | HTTP nội bộ mTLS + Bearer token | Rating aggregate và review preview cho PDP |

mTLS và quyền gọi ba internal API trên do service mesh thực thi theo principal
`product-detail-bff` ([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
BFF gửi `X-Service-Identity: product-detail-bff` cho attribution. Review còn bắt buộc
static bearer token làm defense-in-depth, nên BFF gửi `Authorization: Bearer` lấy từ
`PDP_REVIEW_SERVICE_TOKEN` ([interfaces.md](./interfaces.md#header-gửi-cho-dependency)).

Runtime là Java 21 + Spring Boot WebFlux + Resilience4j
([ADR-006](./decisions/ADR-006-java-spring-webflux-runtime.md)); cấu hình ở
[operations.md](./operations.md#cấu-hình).

Không có Kafka consumer, PostgreSQL read model, Inventory export client hoặc Search
dependency trong BFF. Cache process-local hoặc CDN, nếu được bật, chỉ là tối ưu ngắn
hạn và không phải source of truth.

## Invariants

- Product, Inventory và Review là các nguồn độc lập; không suy diễn global version
  hoặc global transaction từ response của chúng.
- BFF chỉ gọi một Inventory batch-read cho tất cả SKU catalog-active của PDP; không
  gọi per-SKU và không truy cập database Inventory.
- BFF chỉ map trạng thái `IN_STOCK`, `OUT_OF_STOCK` hoặc `UNKNOWN` do Inventory trả;
  không tính availability từ quantity hoặc cache dài hạn.
- Thiếu một SKU trong response, `UNKNOWN` từ Inventory, hay call Inventory không cho kết
  quả hợp lệ (timeout, `5xx`, `4xx`, sai contract, circuit open, bulkhead đầy, hết
  budget) đều phải render SKU là `UNKNOWN`, không suy diễn `OUT_OF_STOCK`.
- Ứng dụng không retry dependency nào trong hot path; timeout BFF luôn ngắn hơn timeout
  mesh; response degrade không được cache
  ([ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md)).
- BFF không gửi reserve/deduct/release và không dùng availability UI để xác nhận đơn.
- BFF không gọi Search để lấy catalog hay stock cho PDP.
- Category, thuộc tính, trục biến thể và media chỉ đến từ Product buyer-read, giữ nguyên
  thứ tự Product trả; BFF không gọi API category/attribute và không tự tra nhãn. Public
  response không chứa storage key: BFF dựng URL CDN, SKU không ảnh nhận URL placeholder
  ([ADR-005](./decisions/ADR-005-pdp-catalog-scope-and-media-url.md)).
- BFF không lưu Review data, không consume Review event và không nhận command Review.
- Review unavailable phải được biểu diễn bằng `review_section.status = UNAVAILABLE`,
  không được giả thành aggregate 0 hoặc làm mất phần catalog của PDP.

## Tài liệu chuẩn

| Tài liệu | Vai trò |
|---|---|
| [Architecture](./architecture.md) | Thành phần, request path và consistency model |
| [Domain](./domain.md) | Status availability và business rule hiển thị |
| [Data](./data.md) | Không có persistent read model; cache boundary |
| [Interfaces](./interfaces.md) | Public PDP API và internal dependencies |
| [Workflows](./workflows.md) | Read và degradation flow |
| [Operations](./operations.md) | SLO, resilience budget, phân chia với mesh/gateway, circuit breaker và runbook |
| [Development](./development.md) | Contract/integration test và Definition of Done |
| [Decisions](./decisions/README.md) | Availability batch-read, Review read composition, ranh giới Preview/Reserve, resilience budget, phạm vi catalog và URL media, runtime, làm rõ hot path, hiệu chỉnh sau audit (status ngoài contract, storage key, readiness, request ID trong log) |

## Trạng thái rollout

PDP có thể public availability khi Inventory Availability Read API đã có contract test
với BFF, service identity cho `product-detail-bff` được cấp và Inventory chứng minh
read model của chính nó đáp ứng freshness SLO. Trước đó, BFF có thể rollout catalog và
Review enrichment với `PDP_INVENTORY_ENABLED=false`: BFF không gọi Inventory, mọi SKU là
`UNKNOWN` với outcome `disabled` (không tính SLO degrade, không alert), thay vì sử dụng
event/export cũ. Bật cờ là bước rollout riêng sau khi đạt gate.

Review enrichment có thể rollout độc lập bằng `PDP_REVIEW_ENABLED`. Khi cờ tắt, BFF trả
review section ở trạng thái `UNAVAILABLE`; trạng thái này không đồng nghĩa Product chưa
có review ([ADR-007](./decisions/ADR-007-hot-path-clarifications.md)).
