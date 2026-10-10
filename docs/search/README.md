---
doc_id: search-overview
service: search
scope: current
status: implemented
owners:
  - Search Service team
evidence:
  - docs/search/schema
  - contracts/http/search/v2/openapi.yaml
  - contracts/events/product/v1/asyncapi.yaml
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/events/review/v1/asyncapi.yaml
  - java/services/search-service/src/main/java/com/ecommerce/search
  - java/services/search-service/src/test/java/com/ecommerce/search
---

# Search Service — Service Overview

> Điểm bắt đầu để hiểu Search Service. Tài liệu mô tả thiết kế hiện hành, đã được
> đồng bộ với [Product Service](../product/README.md),
> [Inventory](../inventory/interfaces.md#6-public-events), [Review](../review/README.md) và với
> implementation (ADR-001..022; lần đối chiếu gần nhất:
> [ADR-022](./decisions/ADR-022-post-implementation-audit-corrections.md)).

## 1. Service status

- Service đang `running`. Implementation tại
  [`java/services/search-service`](../../java/services/search-service): Java 21,
  Spring Boot 3.3, Elasticsearch Java client 8.11, Spring Kafka.
- Tài liệu ở mức `implemented`: mọi mục bên dưới đã được đối chiếu với code và test của service. Phần còn thiếu được
  liệt kê trung thực ở [§9 Known gaps](#9-known-gaps).
- Contract adoption `implemented`: OpenAPI storefront/admin v2
  ([`contracts/http/search/v2/openapi.yaml`](../../contracts/http/search/v2/openapi.yaml), `2.2.0`) được provider
  phục vụ và frontend dùng; v1 đã bị gỡ hoàn toàn. Search consume AsyncAPI Product, Inventory và Review v1 và gọi các
  export/readiness API của ba service đó; cả hai phía đều chạy. Chưa `verified` vì chưa có contract test chéo giữa Search
  và producer ([`contracts/ADOPTION.md`](../../contracts/ADOPTION.md)).
- Elasticsearch là store duy nhất. Không có PostgreSQL, không có Redis.
- Bốn file mapping trong `schema/` là nguồn chuẩn của implementation hiện hành, được test so từng byte với bản
  trong artifact: ba index phục vụ buyer cộng index nội bộ `search_jobs` cho state của các job (run reindex, request
  reindex đang chờ, marker index đã thay, yêu cầu hủy, lease).

## 2. Mục tiêu

Search Service phục vụ toàn bộ luồng browsing của buyer: full-text search tiếng
Việt, lọc theo thuộc tính/giá/danh mục, facet, gợi ý autocomplete và duyệt cây
danh mục. Service tồn tại để traffic đọc của buyer không chạm vào write store của
Product, và để các truy vấn mà PostgreSQL làm kém (full-text, facet đa chiều,
ranking) chạy trên inverted index.

## 3. Boundary

Search chịu trách nhiệm: query layer cho buyer, projection Elasticsearch từ event
của Product/Inventory/Review, vòng đời index (mapping, alias, reindex, rollback, dọn index cũ) và chất
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
- Đồng bộ projection catalog, tồn kho và rating từ event upstream, với retry thật sự khi một batch không áp dụng được.
- Reindex zero-downtime có kiểm tra trước cutover, resume khi run bị bỏ rơi, hủy, rollback và tự khởi chạy khi cây
  category đổi.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Storefront qua API Gateway | HTTP | Search, suggest, browse, facet | Required | [`contracts/http/search/v2`](../../contracts/http/search/v2/openapi.yaml) |
| Inbound | Admin qua API Gateway | HTTP | Trigger, theo dõi, liệt kê, hủy và rollback full reindex | Optional | [`contracts/http/search/v2`](../../contracts/http/search/v2/openapi.yaml) |
| Inbound | `product-service` | Kafka | Catalog, giá công bố, trạng thái, tombstone | Required | [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) |
| Inbound | `inventory-service` | Kafka | Snapshot `is_in_stock` theo SKU do Inventory tính | Required | [Inventory AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml) |
| Inbound | `review-service` | Kafka | Rating aggregate theo product | Required cho rating freshness; không nằm trên query hot path | [AsyncAPI Review v1](../../contracts/events/review/v1/asyncapi.yaml) |
| Inbound | `product-service` | Kafka | Control reindex khi category tree đổi (tự dẫn tới một run) | Required | `t.catalog.control.events`, [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) |
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
| `SEARCH-INV-005` | Event availability hoặc rating không được tạo document mới; chỉ Product được tạo document. Search phải đăng ký readiness sau khi document đã tồn tại; Inventory không được publish/export availability trước mốc đó. Missing document khi apply availability là contract violation, không ACK (batch được `nack` và giao lại) và tuyệt đối không upsert. |
| `SEARCH-INV-006` | Document đã tombstone không được sống lại bởi event đến trễ trước khi bị purge. |
| `SEARCH-INV-007` | Mọi lệnh ghi index đi qua write alias; không ghi trực tiếp vào index versioned trừ pha catch-up (và tail replay) của reindex. |

Giải thích và nơi enforcement ở [Domain](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Thành phần, processing và consistency model | Khi thay đổi cấu trúc hoặc luồng xử lý |
| [Domain](./domain.md) | Document model, rule hiển thị, guard, error | Khi thay đổi business logic |
| [Interfaces](./interfaces.md) | HTTP API, event tiêu thụ và error catalog | Khi tích hợp với Search |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Khi sửa một use case |
| [Data](./data.md) | Ownership, index model và lifecycle | Khi sửa mapping hoặc dữ liệu |
| [Operations](./operations.md) | SLO, alert, failure mode, worker, runbook | Khi deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy, build và test local | Khi phát triển service |
| [Decisions](./decisions/README.md) | ADR | Khi cần hiểu lý do thiết kế |
| [`schema/`](./schema) | Index template và painless script | Khi đổi mapping hoặc script |

## 9. Known gaps

- **Chưa có SLO số.** Ngưỡng latency, freshness và alert threshold còn là `TBD`
  cho tới khi có số liệu production.
- **Chưa có contract test chéo** giữa Search và ba producer (Product, Inventory, Review), nên các contract chưa lên
  `verified`. `SnapshotExportClientTest` và `InventoryReadinessClientTest` là client test phía consumer, chưa chạy với fixture
  sinh từ contract.
- **Test bắt buộc trong [Workflows](./workflows.md) chưa đủ.** Đã có: đường listener thật với Kafka
  (`ConsumerRedeliveryIT`), toàn bộ vòng đời reindex trên Elasticsearch và Kafka thật
  (`ReindexLifecycleIT`), tombstone purge, projection và query. Chưa có test tích hợp riêng cho rebuild suggestion
  (`SuggestionRebuildJob`), recompute `product_count` (`CategoryProductCountJob`) và catch-up có event thật trên cả bốn
  topic; các job này mới được kiểm qua unit test của phần logic thuần (`SuggestionCandidateTest`, `SuggestionTextTest`).
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
- **Enrichment category lỗi I/O bị coi là miss.** `CategoryEnrichment` nuốt `IOException` của lần đọc category và index
  document không có field category dẫn xuất (`SEARCH-BR-008`); guard `catalog_revision` làm lần phát lại cùng revision
  thành no-op, nên document chỉ hồi phục ở revision kế tiếp hoặc ở reindex.
- **`search_zombie_update_blocked_total` không được tăng.** Script canonical dùng cùng `ctx.op = 'none'` cho revision cũ và
  document đã tombstone, nên cả hai đếm vào `search_indexing_total{status="guard_blocked"}`.
- **Rollback của suggestion là thao tác thủ công** theo runbook (một lệnh `_aliases`); không có API hay trạng thái
  `ROLLED_BACK` cho suggestion build. Rollback của reindex run thì có API.
- **Cluster có replica cần nâng `min-free-disk-ratio`.** Precondition so free disk với kích thước *primary* của index hiện hành;
  một index mới kèm replica chiếm nhiều hơn.
- **Tail replay là best effort.** Nếu không đọc hết, run vẫn `COMPLETED` với `checkpoint.tail_replay = INCOMPLETE` và các
  document đổi giữa lúc catch-up hội tụ và lúc swap giữ trạng thái cũ cho tới event kế tiếp hoặc reindex sau.
- **Search Product cards đã đổi ngữ nghĩa.** Backend/frontend cùng dùng `total_products`, signed PIT cursor và một card/Product;
  rollout đã đồng bộ. Xem ADR-020.

## Product cards (2026-09-30)

[ADR-020](./decisions/ADR-020-product-cards-with-cheapest-eligible-sku.md) thay kết quả SKU bằng Product duy nhất và SKU đại diện giá thấp nhất thỏa bộ lọc. Store giữ nguyên SKU document, không cần reindex hay thay Product event. `total_products` chính xác; query grouping có cap/time budget và cần benchmark catalog lớn.
