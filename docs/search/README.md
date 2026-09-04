---
doc_id: search-overview
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence:
  - docs/search/schema
verified_against_commit: null
---

# Search Service — Service Overview

> Điểm bắt đầu để hiểu Search Service. Tài liệu mô tả thiết kế hiện hành đã được
> align với [Product Service](../product/README.md) và
> [Inventory](../inventory/inventory_events.md); trạng thái vẫn là `proposed` cho
> tới khi được đối chiếu với implementation.

## 1. Service status

- Service đang `running`. Implementation tại
  [`java/services/search-service`](../../java/services/search-service): Java 21,
  Spring Boot 3.3, Elasticsearch Java client 8.11, Spring Kafka.
- Tài liệu ở mức `proposed`: thiết kế đã chốt ở mức document nhưng chưa đối chiếu
  từng dòng code, nên `verified_against_commit` là `null`.
- Contract adoption `not_started`: chưa có OpenAPI cho HTTP API và chưa có AsyncAPI
  cho các topic mà Search tiêu thụ. Đây là gap lớn nhất của service.
- Elasticsearch là store duy nhất. Không có PostgreSQL, không có Redis.
- Ba file mapping trong `schema/` là **target vNext** cho service viết lại; chúng
  không mô tả index mà implementation legacy đang phục vụ.

## 2. Mục tiêu

Search Service phục vụ toàn bộ luồng browsing của buyer: full-text search tiếng
Việt, lọc theo thuộc tính/giá/danh mục, facet, gợi ý autocomplete và duyệt cây
danh mục. Service tồn tại để traffic đọc của buyer không chạm vào write store của
Product, và để các truy vấn mà PostgreSQL làm kém (full-text, facet đa chiều,
ranking) chạy trên inverted index.

## 3. Boundary

Search chịu trách nhiệm: query layer cho buyer, projection Elasticsearch từ event
của Product/Inventory/Review, vòng đời index (mapping, alias, reindex) và chất
lượng tìm kiếm (analyzer, ranking, suggestion).

Search **không** chịu trách nhiệm: sở hữu catalog, quyết định giá, quyết định tồn
kho, quản trị sản phẩm và point-read cho luồng giao dịch. Cart và Checkout phải
gọi gRPC của Product cho catalog/giá và reserve tại Inventory cho tồn kho — tuyệt
đối không đọc Search, vì Search là eventual consistent.

Search không có transaction nghiệp vụ: mọi thay đổi state đều là hệ quả của một
event upstream đã commit ở service sở hữu dữ liệu.

## 4. Data ownership

Search **không** là system of record cho bất kỳ field nghiệp vụ nào. Mọi document
là projection.

| Nhóm dữ liệu | Source of truth | Đường vào Search |
|---|---|---|
| Product, SKU, category, media, attribute | [Product](../product/data.md) | `t.product.events`, `t.category.events` |
| `base_price_vnd`, `selling_price_vnd` | [Product](../product/domain.md) | `t.product.events` |
| `available_quantity` | [Inventory](../inventory/inventory_events.md) | `t.inventory.events` |
| `product_rating_avg`, `product_rating_count` | [Review](../review/review_events.md) | `t.review.events` |
| Index mapping, alias, ranking, suggestion weight | Search | Sở hữu nội bộ |

Search sở hữu các giá trị dẫn xuất phục vụ query: `is_in_stock`, `has_promotion`,
token filter, `weight` của suggestion và `product_count` của category. Product
công bố snapshot cấu trúc category; Search chỉ materialize ancestor/path/level
từ snapshot đó, không là owner của thuật toán dựng cây. Không service nào được ghi trực tiếp vào
Elasticsearch của Search; mọi ghi đi qua Kafka consumer hoặc job nội bộ của Search.

## 5. Capabilities

- Tìm kiếm sản phẩm full-text tiếng Việt kèm filter, facet và cursor pagination.
- Gợi ý autocomplete search-as-you-type từ index suggestion pre-compute.
- Duyệt cây danh mục kèm số lượng sản phẩm đang hiển thị.
- Trả bộ filter khả dụng cho một trang danh mục.
- Đồng bộ projection catalog, tồn kho và rating từ event upstream.
- Reindex zero-downtime khi mapping hoặc dữ liệu cần rebuild.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Client qua API Gateway | HTTP | Search, suggest, browse, facet | Required | Cần tạo: `contracts/http/search/v1` |
| Inbound | Admin qua API Gateway | HTTP | Trigger và theo dõi full reindex | Optional | Cần tạo: `contracts/http/search/internal/v1` |
| Inbound | `product-service` | Kafka | Catalog, giá công bố, trạng thái, tombstone | Required | Cần tạo: AsyncAPI product |
| Inbound | `inventory-service` | Kafka | `available_quantity` theo SKU | Required | Cần tạo: AsyncAPI inventory |
| Inbound | `review-service` | Kafka | Rating aggregate theo product | Optional | Cần tạo: AsyncAPI review |
| Inbound | `product-service` | Kafka | Control reindex khi category tree đổi | Required | `t.catalog.control.events`, Product AsyncAPI v1 |
| Outbound | `product-service` | HTTP nội bộ | Export catalog cho full reindex | Required | [Product interfaces](../product/interfaces.md) |
| Outbound | Elasticsearch | HTTP | Store duy nhất | Required | [`schema/`](./schema) |

Search **không phát** event nghiệp vụ nào. Không service nào được phép chờ tín
hiệu từ Search.

## 7. Critical invariants

| ID | Invariant |
|---|---|
| `SEARCH-INV-001` | Search không bao giờ là nguồn để chốt giá hoặc tồn kho cho giao dịch; mọi số liệu hiển thị là snapshot có độ trễ. |
| `SEARCH-INV-002` | Một document trong index `products` và một item của API search là đúng một SKU; `_id = sku_id`. Product chỉ là ngữ cảnh hiển thị và khóa đếm DISTINCT. |
| `SEARCH-INV-003` | Mọi count hiển thị cho browse/facet phải đếm DISTINCT `product_id`, không dùng `doc_count` theo SKU. |
| `SEARCH-INV-004` | Event cũ hơn state hiện tại của document không được ghi đè state đó; guard theo từng nguồn dữ liệu. |
| `SEARCH-INV-005` | Event tồn kho hoặc rating không được tạo document mới; chỉ event catalog của Product mới tạo document. |
| `SEARCH-INV-006` | Document đã tombstone không được sống lại bởi event đến trễ trước khi bị purge. |
| `SEARCH-INV-007` | Mọi lệnh ghi index đi qua write alias; không ghi trực tiếp vào index versioned trừ pha catch-up của reindex. |

Giải thích và nơi enforcement ở [Domain](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Thành phần, processing và consistency model | Khi thay đổi cấu trúc hoặc luồng xử lý |
| [Domain](./domain.md) | Document model, rule hiển thị, guard, error | Khi thay đổi business logic |
| [Interfaces](./interfaces.md) | HTTP API và event tiêu thụ | Khi tích hợp với Search |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Khi sửa một use case |
| [Data](./data.md) | Ownership, index model và lifecycle | Khi sửa mapping hoặc dữ liệu |
| [Operations](./operations.md) | SLO, alert, failure mode, worker | Khi deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy, build và test local | Khi phát triển service |
| [Decisions](./decisions/README.md) | ADR | Khi cần hiểu lý do thiết kế |
| [`schema/`](./schema) | Index template và painless script | Khi đổi mapping hoặc script |

## 9. Known gaps

- **Contract chưa machine-readable.** Chưa có OpenAPI cho 4 API public và 2 API
  internal, chưa có AsyncAPI/JSON Schema cho 4 topic tiêu thụ. Mọi semantics hiện
  chỉ tồn tại ở [Interfaces](./interfaces.md).
- **Tài liệu chưa `verified`.** Thiết kế trong bộ tài liệu này đã align với
  Product/Inventory hiện hành, nhưng implementation hiện tại còn theo thế hệ thiết
  kế cũ ở các điểm sau và phải được migrate:
  - Guard ordering: code dùng một field `version` theo `updated_at`; thiết kế yêu
    cầu guard riêng theo từng nguồn ([ADR-004](./decisions/ADR-004-per-source-ordering-guard.md)).
  - Giá: code dùng `original_price`/`promotional_price`/`best_price`; Product công
    bố `base_price_vnd`/`selling_price_vnd`.
  - Tồn kho: code suy ra `is_in_stock` từ status; thiết kế lấy `available_quantity`
    từ Inventory ([ADR-009](./decisions/ADR-009-stock-projection-ownership.md)).
  - Xóa: Product đã chuyển sang hard delete kèm tombstone event.
  - `categories.product_count`: code cộng trừ theo event; thiết kế dùng job
    recompute ([ADR-008](./decisions/ADR-008-category-product-count-recompute.md)).
  - Draft Cleanup Job cũ gọi `GET /internal/products/orphan-drafts`; endpoint này
    không tồn tại trong Product hiện hành và job đã được thay bằng tombstone purge.
- **Chưa có SLO số.** Ngưỡng latency, freshness và alert threshold còn là `TBD`
  cho tới khi có số liệu production.
- **Chưa có contract test** giữa Search và ba producer.
- **Inventory chưa có target contract.** Schema vNext yêu cầu `stock_revision`
  đơn điệu theo SKU và snapshot/export stock để bootstrap hoặc phục hồi; event
  legacy chỉ có timestamp nên chưa đáp ứng yêu cầu này.
- **Media snapshot chưa chốt.** Product v1 hiện chỉ mô tả media storage key, chưa
  chốt rule chọn thumbnail giữa Product media và SKU media. `thumbnail_storage_key`
  trong schema vNext là field optional, chưa được coi là contract implemented.
- **Implementation chưa theo ADR-012.** Code hiện dùng `collapse(product_id)` và
  cursor offset, trong khi thiết kế đã chốt SKU hit trực tiếp với `search_after`.
  Khi viết lại service, implementation phải bỏ `collapse`/`inner_hits` và áp dụng
  cursor theo sort values; xem [ADR-012](./decisions/ADR-012-sku-results-with-search-after.md).
