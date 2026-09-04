---
doc_id: search-domain
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence:
  - docs/search/schema
verified_against_commit: null
---

# Search Service — Domain Model

> File này trả lời: “Search Service đang bảo vệ những quy tắc nào?” Search không
> sở hữu dữ liệu nghiệp vụ, nên domain của nó là **quy tắc chiếu dữ liệu và quy
> tắc hiển thị**: cái gì được vào index, cái gì được thấy, và cái gì thắng khi hai
> nguồn mâu thuẫn.

## 1. Ubiquitous language

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Projection | Bản chiếu chỉ đọc của dữ liệu do service khác sở hữu | Bản sao có thẩm quyền |
| SKU document | Một document trong index `products`, `_id = sku_id` | Product |
| Catalog availability | `product_status = ACTIVE` và `sku_status = ACTIVE` | Stock availability |
| Stock availability | `available_quantity > 0` do Inventory công bố | Tồn kho khả dụng để chốt đơn |
| Sellable projection | Catalog availability và stock availability cùng đúng | Kết quả reserve của Inventory |
| Guard field | Version của một nguồn dữ liệu trên document | `_version` của Elasticsearch |
| Tombstone | Document đã bị xóa ở upstream nhưng còn giữ lại để chặn zombie update | Soft delete nghiệp vụ |
| Purge | Xóa vật lý document tombstone sau cửa sổ bảo vệ | Tombstone |
| Read alias / write alias | Alias phục vụ đọc / phục vụ ghi trên cùng index versioned | Tên index vật lý |
| Cursor | Chuỗi base64 mã hóa sort values của hit cuối | Số trang |
| Suggestion weight | Điểm xếp hạng pre-compute của một gợi ý | `_score` |
| Facet count | Số DISTINCT `product_id` khớp một filter | `doc_count` theo SKU |

## 2. Domain objects

Search có ba document model, không có aggregate theo nghĩa transaction:

- **SKU document** (index `products`): đơn vị chiếu chính. Mang field product-level
  denormalize (tên, mô tả, category, thuộc tính chung, media) và field SKU-level
  (giá, trạng thái, thuộc tính biến thể, tồn kho). Identity là `sku_id` do Product
  cấp. Lifecycle bắt đầu bằng event catalog, kết thúc bằng purge sau tombstone.
- **Category document** (index `categories`): node của cây danh mục kèm số lượng
  sản phẩm hiển thị. Identity là `category_id` do Product cấp. `path`, `level` và
  `is_leaf` là giá trị Search tự dẫn xuất vì Product không lưu chúng
  ([ADR-010](./decisions/ADR-010-derived-category-tree.md)).
- **Suggestion document** (index `search_suggestions`): một cụm từ gợi ý kèm
  `weight`. Identity là `suggestion_id` do Search sinh. Lifecycle bằng đúng một
  chu kỳ rebuild.

Product, SKU, category, tồn kho và rating đều là object của service khác; Search
chỉ tham chiếu chúng qua identifier và snapshot.

## 3. Commands và queries

| Type | Capability | Actor | Xử lý bởi |
|---|---|---|---|
| Query | Tìm sản phẩm kèm filter và facet | Buyer qua Gateway | Query layer |
| Query | Autocomplete | Buyer qua Gateway | Query layer |
| Query | Duyệt cây danh mục | Buyer qua Gateway | Query layer |
| Query | Đọc facet của một danh mục | Buyer qua Gateway | Query layer |
| Command | Trigger full reindex | Admin qua Gateway | Reindex job |
| Query | Đọc trạng thái một lần reindex | Admin qua Gateway | Reindex job |
| Command nội bộ | Apply event catalog/stock/review | Kafka consumer | Indexing worker |
| Command nội bộ | Rebuild suggestion, recompute count, purge tombstone | Scheduler | Job tương ứng |

Buyer không có command nào trên Search. Mọi thay đổi dữ liệu đến từ upstream.

## 4. Business rules

| ID | Rule | Điều kiện áp dụng | Vi phạm |
|---|---|---|---|
| `SEARCH-BR-001` | Chỉ document có `product_status = ACTIVE`, `sku_status = ACTIVE` và `is_deleted = false` được trả cho buyer | Mọi API public | Kết quả rò rỉ hàng chưa công bố |
| `SEARCH-BR-002` | `in_stock` mặc định là `true`; buyer có thể tắt để xem cả hàng hết | API search và facet | — |
| `SEARCH-BR-003` | `is_in_stock = available_quantity > 0`; chỉ Inventory được thay đổi giá trị nguồn | Khi apply event tồn kho | Hiển thị sai khả dụng |
| `SEARCH-BR-004` | `has_promotion = selling_price_vnd < base_price_vnd` | Khi apply event catalog | Gắn cờ giảm giá sai |
| `SEARCH-BR-005` | `discount_percentage = floor((base - selling) / base * 100)` khi `base > 0` và `selling < base`; ngược lại bằng `0` | Khi build response | Sai số phần trăm giảm giá |
| `SEARCH-BR-006` | Mọi sort kết thúc bằng tie-break `sku_id asc` | Khi build query | Pagination lặp hoặc bỏ sót |
| `SEARCH-BR-007` | Mọi count hiển thị đếm DISTINCT `product_id` | Facet, category count | Số đếm phồng theo số SKU |
| `SEARCH-BR-008` | Enrichment category miss thì bỏ trống field category, vẫn index document | Apply event catalog | Mất sản phẩm khỏi index |
| `SEARCH-BR-009` | Event từ một nguồn chỉ ghi các field thuộc nguồn đó | Apply mọi event | Nguồn này xóa dữ liệu của nguồn kia |
| `SEARCH-BR-010` | Event tồn kho/rating cho document chưa tồn tại thì bỏ qua, không upsert | Apply event stock/review | Sinh document rỗng không có catalog |
| `SEARCH-BR-011` | Tombstone chỉ được purge sau khi qua cửa sổ bảo vệ | Tombstone purger | Document sống lại do event trễ |
| `SEARCH-BR-012` | Mapping thay đổi phải qua reindex và alias swap | Vận hành index | Downtime hoặc mapping conflict |

## 5. Invariants

`SEARCH-INV-001` đến `SEARCH-INV-007` được liệt kê ở
[README](./README.md#7-critical-invariants). Nơi enforcement:

| Invariant | Enforcement |
|---|---|
| `SEARCH-INV-002` | `_id = sku_id` khi ghi; không dùng auto-id |
| `SEARCH-INV-003` | `cardinality` aggregation trên `product_id` trong mọi facet |
| `SEARCH-INV-004` | Painless guard script trong [`schema/scripts`](./schema/scripts) |
| `SEARCH-INV-005` | Lệnh ghi của stock/review không dùng `scripted_upsert` |
| `SEARCH-INV-006` | Cửa sổ tombstone của purger dài hơn retention của topic |
| `SEARCH-INV-007` | Client chỉ được cấu hình tên alias, không cấu hình tên index |

Test bắt buộc cho từng invariant được liệt kê ở [Workflows](./workflows.md).

## 6. State machines

### SKU document

| State | Transition | Trigger | Guard / side effect |
|---|---|---|---|
| `ABSENT` | → `INDEXED` | `PRODUCT_CREATED`, `SKU_CREATED`, hoặc backfill reindex | Chỉ event catalog mới được tạo document |
| `INDEXED` | → `INDEXED` | Mọi event catalog/stock/review mới hơn | Guard theo nguồn; field ngoài nguồn giữ nguyên |
| `INDEXED` | → `TOMBSTONED` | `PRODUCT_DELETED`, `SKU_DELETED` | Đặt `is_deleted = true`, `deleted_at`, loại khỏi mọi query |
| `TOMBSTONED` | → `TOMBSTONED` | Event catalog/stock/review đến trễ | Bỏ qua, ghi metric zombie |
| `TOMBSTONED` | → `PURGED` | Tombstone purger sau cửa sổ bảo vệ | Xóa vật lý document |
| `PURGED` | → `INDEXED` | Chỉ khi Product tạo lại `sku_id` đó | Không phải khôi phục; là document mới |

Trạng thái hiển thị của một document `INDEXED` phụ thuộc `product_status`
(`DRAFT`, `ACTIVE`, `HIDDEN` do Product quyết định) và `sku_status`
(`ACTIVE`, `INACTIVE`). Search không tự chuyển các trạng thái này.

### Reindex run

`STARTED` → `BUILDING` → `CATCHING_UP` → `CUTOVER` → `COMPLETED`. Từ bất kỳ pha
nào có thể sang `FAILED`; sau `CUTOVER` có thể sang `ROLLED_BACK` bằng alias swap
ngược. Chỉ một run được phép ở trạng thái chưa kết thúc tại một thời điểm.

### Suggestion build

`BUILDING` → `VALIDATING` → `SWAPPED`, hoặc → `ABORTED` khi validation gate không
đạt. `ABORTED` giữ nguyên alias cũ nên API suggest không gián đoạn.

## 7. Authorization rules

- Bốn API public là read-only và không yêu cầu quyền nghiệp vụ. Gateway đã xác
  thực JWT trước khi forward; Search không tự verify chữ ký.
- Search chỉ dùng claim để logging và correlation, không lọc dữ liệu theo user.
  Không có dữ liệu riêng tư trong index.
- Internal API reindex yêu cầu claim `role = ADMIN` do Gateway chuyển tiếp. Search
  không chấp nhận identity header đến trực tiếp từ client.
- Không có shared-secret fallback cho internal API.

## 8. Domain errors

| Error code | Điều kiện | Retryable | Interface mapping |
|---|---|---|---|
| `INVALID_QUERY_PARAMETER` | Tham số sai kiểu hoặc ngoài range | No | HTTP 400 |
| `INVALID_CURSOR` | Cursor không decode được hoặc không khớp bộ tham số | No | HTTP 400 |
| `FORBIDDEN` | Gọi internal API mà claim không phải `ADMIN` | No | HTTP 403 |
| `CATEGORY_NOT_FOUND` | Facet cho category không tồn tại trong index | No | HTTP 404 |
| `REINDEX_ALREADY_RUNNING` | Đã có một run chưa kết thúc | Yes, sau khi run cũ xong | HTTP 409 |
| `REINDEX_RUN_NOT_FOUND` | `run_id` không tồn tại | No | HTTP 404 |

Các lỗi sau là lỗi hạ tầng, không phải domain error, và không được dùng để suy ra
kết luận nghiệp vụ: `ELASTICSEARCH_UNAVAILABLE` (HTTP 503), `SEARCH_TIMEOUT`
(HTTP 504), `INTERNAL_ERROR` (HTTP 500). Kết quả rỗng là kết quả hợp lệ, không
phải lỗi.

## 9. Business examples

- **Giảm giá.** SKU có `base_price_vnd = 34.990.000` và `selling_price_vnd =
  28.891.500` thì `has_promotion = true` và `discount_percentage = 17`.
- **Hết hàng.** Inventory phát `available_quantity = 0`; document còn được index
  và vẫn tìm thấy khi buyer đặt `in_stock=false`, nhưng biến mất khỏi kết quả mặc
  định. Product không đổi trạng thái vì hết hàng.
- **Event trễ.** Một `SKU_UPDATED` với `catalog_revision = 41` tới sau khi document
  đã ở revision `42` thì bị bỏ qua hoàn toàn, kể cả các field mà revision 42 không
  chạm tới.
- **Xóa rồi có event trễ.** Sau `SKU_DELETED`, một `INVENTORY_STOCK_CHANGED` của
  chính SKU đó tới muộn: document đang tombstone nên event bị bỏ qua và ghi metric,
  không làm `is_deleted` trở lại `false`.
- **Đổi tên danh mục.** `CATEGORY_UPDATED` đổi tên một node cha khiến `category_path`
  của toàn bộ SKU thuộc nhánh đó phải đồng bộ; đây là lý do event category là
  event diện rộng chứ không phải cập nhật một document.
