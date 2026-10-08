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
> [Inventory](../inventory/interfaces.md#6-public-events); trạng thái vẫn là `proposed` cho
> tới khi được đối chiếu với implementation.

## 1. Service status

- Service đang `running`. Implementation tại
  [`java/services/search-service`](../../java/services/search-service): Java 21,
  Spring Boot 3.3, Elasticsearch Java client 8.11, Spring Kafka.
- Tài liệu ở mức `proposed`: thiết kế đã chốt ở mức document nhưng chưa đối chiếu
  từng dòng code, nên `verified_against_commit` là `null`.
- Contract adoption `pilot`: OpenAPI storefront/admin v2 đã có ở
  [`contracts/http/search/v2/openapi.yaml`](../../contracts/http/search/v2/openapi.yaml).
   Contract Review cho event rating và snapshot export đã được định nghĩa ở mức
   `proposed`; implementation/contract test giữa Review và Search vẫn là gap.
- Elasticsearch là store duy nhất. Không có PostgreSQL, không có Redis.
- Bốn file mapping trong `schema/` là nguồn chuẩn của implementation hiện hành:
  ba index phục vụ buyer cộng index nội bộ `search_jobs` cho state của job singleton.

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

Search cũng không là Product Detail provider. Product Detail BFF đọc catalog qua Product
và advisory availability qua Inventory Availability Read API; BFF không được dùng Search
index làm point-read hay suy rộng quyền event/export của Search.

Search không có transaction nghiệp vụ: mọi thay đổi state đều là hệ quả của một
event upstream đã commit ở service sở hữu dữ liệu.

## 4. Data ownership

Search **không** là system of record cho bất kỳ field nghiệp vụ nào. Mọi document
là projection.

| Nhóm dữ liệu | Source of truth | Đường vào Search |
|---|---|---|
| Product, SKU, category, media, attribute | [Product](../product/data.md) | `t.product.events`, `t.category.events` + internal snapshot export/replay offsets |
| `base_price_vnd`, `selling_price_vnd` | [Product](../product/domain.md) | `t.product.events` |
| `is_in_stock` | [Inventory](../inventory/interfaces.md#6-public-events) | Per-SKU availability event + readiness/snapshot/change export |
| `product_rating_avg`, `product_rating_count` | [Review](../review/interfaces.md) | `t.review.events` + internal aggregate snapshot export |
| Index mapping, alias, ranking, suggestion weight | Search | Sở hữu nội bộ |

Search sở hữu các giá trị dẫn xuất phục vụ query: `has_promotion`,
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
| Inbound | Storefront qua API Gateway | HTTP | Search, suggest, browse, facet | Required | [`contracts/http/search/v2`](../../contracts/http/search/v2/openapi.yaml) |
| Inbound | Admin qua API Gateway | HTTP | Trigger và theo dõi full reindex | Optional | [`contracts/http/search/v2`](../../contracts/http/search/v2/openapi.yaml) |
| Inbound | `product-service` | Kafka | Catalog, giá công bố, trạng thái, tombstone | Required | [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) |
| Inbound | `inventory-service` | Kafka | Snapshot `is_in_stock` theo SKU do Inventory tính | Required | [Inventory AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml) |
| Inbound | `review-service` | Kafka | Rating aggregate theo product | Required cho rating freshness; không nằm trên query hot path | [AsyncAPI Review v1](../../contracts/events/review/v1/asyncapi.yaml) |
| Inbound | `product-service` | Kafka | Control reindex khi category tree đổi | Required | `t.catalog.control.events`, Product AsyncAPI v1 |
| Outbound | `product-service` | HTTP nội bộ | Export Category/Product kèm replay offsets cho full reindex | Required | [Product interfaces](../product/interfaces.md) |
| Outbound | `inventory-service` | HTTP nội bộ | Đăng ký/revoke readiness và đọc fixed-watermark per-SKU availability export | Required | [Inventory Export OpenAPI v1](../../contracts/http/inventory/exports/v1/openapi.yaml) |
| Outbound | `review-service` | HTTP nội bộ | Export rating aggregate snapshot cho full reindex | Required để cutover index giữ đầy đủ rating | [Review internal OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) |
| Outbound | Elasticsearch | HTTP | Store duy nhất | Required | [`schema/`](./schema) |

Search **không phát** event nghiệp vụ nào. Không command hay request nghiệp vụ nào
được chờ Search; Inventory chỉ dùng readiness HTTP bất đồng bộ như publication gate
cho projection, không đưa nó vào đường reserve/stock authoritative.

## 7. Critical invariants

| ID | Invariant |
|---|---|
| `SEARCH-INV-001` | Search không bao giờ là nguồn để chốt giá hoặc tồn kho cho giao dịch; mọi số liệu hiển thị là snapshot có độ trễ. |
| `SEARCH-INV-002` | Một document trong index `products` là một SKU, `_id = sku_id`. Mỗi item API search là một Product đại diện bởi SKU giá thấp nhất thỏa bộ lọc (ADR-020). |
| `SEARCH-INV-003` | Mọi count hiển thị cho browse/facet phải đếm DISTINCT `product_id`, không dùng `doc_count` theo SKU. |
| `SEARCH-INV-004` | Event cũ hơn state hiện tại của document không được ghi đè state đó; guard theo từng nguồn dữ liệu. |
| `SEARCH-INV-005` | Event availability hoặc rating không được tạo document mới; chỉ Product được tạo document. Search phải đăng ký readiness sau khi document đã tồn tại; Inventory không được publish/export availability trước mốc đó. Missing document khi apply availability là contract violation, không ACK và tuyệt đối không upsert. |
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

- **HTTP contract mới chỉ ở mức proposed.** OpenAPI v2 đã mô tả bốn API storefront
  và hai operation admin reindex, nhưng provider/frontend chưa migrate và chưa có
   contract test. Contract Review v1 cũng mới là target contract và chưa có
   provider/consumer contract test.
- **Implementation đã được viết lại theo bộ tài liệu này.** Thế hệ code cũ (một field
  `version` chung, giá `original_price`/`promotional_price`, `is_in_stock` suy ra từ
  status, `product_count` cộng trừ theo event, Draft Cleanup Job gọi endpoint
  `orphan-drafts` không tồn tại, HTTP v1 legacy) đã bị gỡ hoàn toàn. Tài liệu vẫn ở
  mức `proposed` vì `verified_against_commit` chưa được cập nhật và contract test giữa
  Search với ba producer vẫn chưa tồn tại.
- **Chưa có SLO số.** Ngưỡng latency, freshness và alert threshold còn là `TBD`
  cho tới khi có số liệu production.
- **Chưa có contract test** giữa Search và ba producer.
- **Cross-service contract chưa được implement.** Product đã chốt
  `thumbnail_storage_key` trong `sku-upserted` và export Category/Product kèm replay
  offsets; Inventory đã có contract đích cho per-SKU availability, readiness và snapshot/change export. Các
  contract vẫn mang trạng thái `proposed` và cần producer implementation, contract
  test, rồi mới chuyển Search khỏi adapter legacy.
- **Search Product cards đã đổi ngữ nghĩa.** Backend/frontend cùng dùng `total_products`, signed PIT cursor và một card/Product; consumer pilot cần rollout đồng bộ. Xem ADR-020.
- **Không có field `created_at` cho sort `newest`.** Mapping đích chỉ có `updated_at`
  và allowlist của `catalog_upsert.painless` không cho ghi `created_at`, nên `newest`
  sắp theo `updated_at` — nghĩa là một lần đổi giá cũng đẩy SKU lên đầu. Sửa đúng cần
  thêm field vào mapping, mở rộng allowlist của script và Product phải công bố
  `created_at` trong `sku-upserted` cùng export.
- **Nhãn option của facet vẫn là giá trị dẫn xuất.** Nhãn nhóm đã lấy từ
  `facet_definitions` Product phát ([ADR-019](../product/decisions/ADR-019-category-facet-definitions.md)),
  nhưng descriptor không mang option, nên `label` của từng option được dựng từ token
  `code:type:value`: chuỗi đã case-fold, viết hoa chữ đầu, không phải tên hiển thị do
  Product đặt (ví dụ option có code `black` hiện là "Black", dù admin đặt nhãn "Đen"). Sửa đúng cần Product đưa option vào
  descriptor và phát lại category khi option đổi. Nhãn nhóm của attribute chưa có
  descriptor (snapshot từ producer cũ) cũng còn dẫn xuất.
- **Nhãn nhóm facet cache 60 giây** trong `FacetLabelResolver`; đổi tên attribute hiển thị
  chậm tối đa chừng đó sau khi snapshot được áp dụng.

## Product cards (2026-09-30)

[ADR-020](./decisions/ADR-020-product-cards-with-cheapest-eligible-sku.md) thay kết quả SKU bằng Product duy nhất và SKU đại diện giá thấp nhất thỏa bộ lọc. Store giữ nguyên SKU document, không cần reindex hay thay Product event. `total_products` chính xác; query grouping có cap/time budget và cần benchmark catalog lớn.
