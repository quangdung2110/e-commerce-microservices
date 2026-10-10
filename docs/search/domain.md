---
doc_id: search-domain
service: search
scope: current
status: implemented
owners:
  - Search Service team
evidence:
  - docs/search/schema
  - java/services/search-service/src/main/java/com/ecommerce/search/domain
  - java/services/search-service/src/main/java/com/ecommerce/search/common/ErrorCode.java
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
| SKU availability | Snapshot boolean `is_in_stock` do Inventory tính cho một SKU trên mọi warehouse đủ điều kiện | Tồn kho khả dụng để chốt đơn |
| Sellable projection | Catalog availability và stock availability cùng đúng | Kết quả reserve của Inventory |
| Guard field | Revision đơn điệu của một nguồn dữ liệu trên document | `_version` Elasticsearch hoặc timestamp publish |
| Tombstone | Document đã bị xóa ở upstream nhưng còn giữ lại để chặn zombie update | Soft delete nghiệp vụ |
| Purge | Xóa vật lý document tombstone sau cửa sổ bảo vệ | Tombstone |
| Read alias / write alias | Alias phục vụ đọc / phục vụ ghi trên cùng index versioned | Tên index vật lý |
| Cursor | Chuỗi opaque, có chữ ký HMAC (generation 2), mang PIT id, vị trí Product kế tiếp, category id đã resolve và fingerprint của query đã chuẩn hóa (trừ `size`); chỉ hợp lệ với đúng query đã sinh ra nó | Số trang, hay sort values của hit cuối (cursor SKU `search_after` cũ đã bị gỡ) |
| Run lease | Lease `full-reindex` mà process đang chạy một reindex giữ và gia hạn; hết hạn nghĩa là run bị bỏ rơi | Lock nghiệp vụ |
| Control request | Request gộp, lưu bền, của mọi `catalog-reindex-requested` chưa được một run hoàn tất thỏa mãn | Event Kafka |
| Retired index | Index versioned đã ngừng được alias phục vụ, kèm thời điểm ngừng; đồng hồ để dọn index cũ tính từ đây | Index vừa tạo |
| Tail replay | Đọc lại, vào index mới sau cutover, stretch mà live indexer đã áp dụng vào index cũ sau khi catch-up hội tụ | Catch-up |
| Suggestion weight | Điểm xếp hạng pre-compute của một gợi ý | `_score` |
| Facet count | Số DISTINCT `product_id` khớp một filter | `doc_count` theo SKU |

## 2. Domain objects

Search có ba document model, không có aggregate theo nghĩa transaction:

- **SKU document** (index `products`): đơn vị chiếu chính. Mang field product-level
  denormalize (tên, mô tả, category, thuộc tính chung, media) và field SKU-level
  (giá, trạng thái, thuộc tính biến thể, tồn kho). Identity là `sku_id` do Product
  cấp. Lifecycle bắt đầu bằng event catalog, kết thúc bằng purge sau tombstone.
- **Category document** (index `categories`): node của cây danh mục kèm số lượng
  sản phẩm hiển thị. Identity là `category_id` do Product cấp. `ancestor_ids`,
  `level` và `is_leaf` được Search materialize từ snapshot Category của Product,
  không tự suy ra từ quan hệ cục bộ ([ADR-013](./decisions/ADR-013-product-category-snapshots.md)).
- **Suggestion document** (index `search_suggestions`): một cụm từ gợi ý kèm
  `weight`. Identity là hash deterministic của `type`, `scope`, `language` và
  `text_normalized`, do Search sinh. Lifecycle bằng đúng một chu kỳ rebuild. Đây
  là query-completion term, không phải tham chiếu/link đến entity; `text` được
  dùng làm truy vấn sản phẩm khi buyer chọn gợi ý, còn `type` chỉ là metadata
  trình bày/phân loại.

Product, SKU, category, tồn kho và rating đều là object của service khác; Search
chỉ tham chiếu chúng qua identifier và snapshot.

## 3. Commands và queries

| Type | Capability | Actor | Xử lý bởi |
|---|---|---|---|
| Query | Tìm sản phẩm kèm filter và facet | Buyer qua Gateway | Query layer |
| Query | Autocomplete | Buyer qua Gateway | Query layer |
| Query | Duyệt cây danh mục | Buyer qua Gateway | Query layer |
| Query | Đọc facet của một danh mục | Buyer qua Gateway | Query layer |
| Command | Trigger full reindex | Admin qua Gateway; hoặc hệ thống khi Product phát `catalog-reindex-requested` | Reindex job |
| Command | Hủy một reindex run | Admin qua Gateway | Reindex job |
| Command | Rollback một reindex run đã hoàn tất | Admin qua Gateway | Reindex run service |
| Query | Đọc trạng thái một lần reindex, hoặc liệt kê các lần | Admin qua Gateway | Reindex run service |
| Command nội bộ | Resume / đánh dấu bỏ rơi một run mất owner; dọn index cũ | Watchdog, scheduler | Reindex job, index cleanup |
| Command nội bộ | Apply event catalog/availability/review | Kafka consumer | Indexing worker |
| Command nội bộ | Import availability/rating snapshot khi full reindex | Reindex job | Reindex writer |
| Command nội bộ | Rebuild suggestion, recompute count, purge tombstone | Scheduler | Job tương ứng |

Buyer không có command nào trên Search. Mọi thay đổi dữ liệu đến từ upstream.

## 4. Business rules

| ID | Rule | Điều kiện áp dụng | Vi phạm |
|---|---|---|---|
| `SEARCH-BR-001` | Chỉ document có `product_status = ACTIVE`, `sku_status = ACTIVE` và `is_deleted = false` được trả cho buyer | Mọi API public | Kết quả rò rỉ hàng chưa công bố |
| `SEARCH-BR-002` | `in_stock` mặc định là `true`; buyer có thể tắt để xem cả hàng hết | API search và facet | — |
| `SEARCH-BR-003` | Search copy nguyên `is_in_stock` từ snapshot per-SKU; chỉ Inventory sở hữu công thức và revision | Khi apply availability event | Hiển thị sai khả dụng |
| `SEARCH-BR-004` | `has_promotion = selling_price_vnd < base_price_vnd` | Khi apply event catalog | Gắn cờ giảm giá sai |
| `SEARCH-BR-005` | `discount_percentage = floor((base - selling) / base * 100)` khi `base > 0` và `selling < base`; ngược lại bằng `0` | Khi build response | Sai số phần trăm giảm giá |
| `SEARCH-BR-006` | SKU đại diện: giá bán asc, sku_id asc; sort Product kết thúc product_id asc | Khi gom/sort Product | Pagination lặp hoặc bỏ sót |
| `SEARCH-BR-007` | Mọi count hiển thị đếm DISTINCT `product_id` | Facet, category count, total_products | Số đếm phồng theo số SKU |
| `SEARCH-BR-008` | Enrichment category miss thì bỏ trống field category, vẫn index document | Apply event catalog | Mất sản phẩm khỏi index |
| `SEARCH-BR-009` | Event từ một nguồn chỉ ghi các field thuộc nguồn đó. Ngoại lệ duy nhất: catalog tombstone đặt `is_in_stock = false` mà không đổi `availability_revision` ([ADR-011](./decisions/ADR-011-tombstone-and-purge-window.md)) | Apply mọi event | Nguồn này xóa dữ liệu của nguồn kia |
| `SEARCH-BR-010` | Availability chỉ được publish sau readiness; nếu document vẫn thiếu thì không ACK và không upsert. Rating thiếu document được bỏ qua có metric | Apply availability/review | Mất availability hoặc sinh document rỗng không có catalog |
| `SEARCH-BR-011` | Tombstone chỉ được purge sau khi qua cửa sổ bảo vệ | Tombstone purger | Document sống lại do event trễ |
| `SEARCH-BR-012` | Mapping thay đổi phải qua reindex và alias swap | Vận hành index | Downtime hoặc mapping conflict |
| `SEARCH-BR-013` | Rating snapshot chỉ apply sau catalog build và dùng cùng `review_revision` với event replay | Full reindex | Rating thiếu hoặc event cũ ghi đè snapshot |
| `SEARCH-BR-014` | Suggestion dedupe theo `(type, scope, language, text_normalized)`; `PRODUCT` và `CATEGORY` cùng text không hợp nhất | Rebuild suggestion | Dropdown mất phân loại hoặc lặp term cùng loại |
| `SEARCH-BR-015` | Weight suggestion tính sau dedupe; nhóm Product trùng tên lấy điểm cao nhất, không cộng dồn theo số Product/SKU | Rebuild suggestion | Nhiều entity trùng tên làm lệch ranking |
| `SEARCH-BR-016` | Câu trả lời của Elasticsearch có `timed_out` hoặc shard lỗi không bao giờ được trả cho buyer hay ghi vào `product_count`; nó là `SEARCH_TIMEOUT` | Mọi truy vấn và job tổng hợp | Facet/số đếm thiếu trông như đầy đủ |
| `SEARCH-BR-017` | Index mới chỉ được nhận alias sau khi qua kiểm tra: số document, sample diff, smoke query; bốn alias chuyển trong một lệnh | Cutover reindex | Cutover index thiếu hoặc lùi dữ liệu; hai thế hệ lẫn nhau giữa `products` và `categories` |
| `SEARCH-BR-018` | Một run chưa kết thúc có đúng một owner sống (lease); owner chết thì run được resume hoặc đánh dấu bỏ rơi, không chặn trigger vĩnh viễn | Reindex | Hai run song song, hoặc `REINDEX_ALREADY_RUNNING` mãi mãi |
| `SEARCH-BR-019` | Một `catalog-reindex-requested` đến sau khi run bắt đầu làm run `SUPERSEDED`; request chỉ bị xóa bởi run `COMPLETED` bắt đầu sau nó | Reindex | Cutover index mang category path cũ, hoặc mất một thay đổi cây |

## 5. Invariants

`SEARCH-INV-001` đến `SEARCH-INV-007` được liệt kê ở
[README](./README.md#7-critical-invariants). Nơi enforcement:

| Invariant | Enforcement |
|---|---|
| `SEARCH-INV-002` | `_id = sku_id` khi ghi; không dùng auto-id |
| `SEARCH-INV-003` | `cardinality` aggregation trên `product_id` trong mọi facet |
| `SEARCH-INV-004` | Painless guard script trong [`schema/scripts`](./schema/scripts/README.md) |
| `SEARCH-INV-005` | Availability dùng readiness handshake + `availability_patch` không upsert; missing document không ACK. Review cũng không `scripted_upsert` |
| `SEARCH-INV-006` | `search.tombstone.protection-window` (45 ngày) dài hơn thời gian dài nhất một record đã consume còn nằm trên broker: retention dài nhất 30 ngày (`t.inventory.stock.events.v1`) + `segment.ms` mặc định 7 ngày + biên; `TombstoneProtectionWindowTest` ([ADR-022](./decisions/ADR-022-post-implementation-audit-corrections.md), thay con số của [ADR-021](./decisions/ADR-021-tombstone-protection-window-35d.md)) |
| `SEARCH-INV-007` | Client chỉ được cấu hình tên alias, không cấu hình tên index |

Test bắt buộc cho từng invariant được liệt kê ở [Workflows](./workflows.md).

## 6. State machines

### SKU document

| State | Transition | Trigger | Guard / side effect |
|---|---|---|---|
| `ABSENT` | → `INDEXED` | `sku-upserted` hoặc backfill reindex | Chỉ snapshot catalog Product mới được tạo document |
| `INDEXED` | → `INDEXED` | Mọi event catalog/availability/review mới hơn | Guard theo nguồn; field ngoài nguồn giữ nguyên |
| `INDEXED` | → `TOMBSTONED` | `product-deleted`, `sku-deleted` | Đặt `is_deleted = true`, `deleted_at`, loại khỏi mọi query |
| `TOMBSTONED` | → `TOMBSTONED` | Event catalog/availability/review đến trễ | Bỏ qua, ghi metric zombie |
| `TOMBSTONED` | → `PURGED` | Tombstone purger sau cửa sổ bảo vệ | Xóa vật lý document |
| `PURGED` | → `INDEXED` | Chỉ khi Product tạo lại `sku_id` đó | Không phải khôi phục; là document mới |

Trạng thái hiển thị của một document `INDEXED` phụ thuộc `product_status`
(`DRAFT`, `ACTIVE`, `HIDDEN` do Product quyết định) và `sku_status`
(`ACTIVE`, `INACTIVE`). Search không tự chuyển các trạng thái này.

### Reindex run

`STARTED` → `BUILDING` → `CATCHING_UP` → `CUTOVER` → `COMPLETED`. `CUTOVER` gồm bước kiểm tra index mới
(số document, sample diff, smoke query) rồi lệnh chuyển bốn alias. Từ `BUILDING` hoặc `CATCHING_UP`
có thể sang `SUPERSEDED` khi một `catalog-reindex-requested` mới tới sau khi run bắt đầu (cũng được kiểm
ngay trước khi swap), sang `CANCELLED` khi admin hủy, và từ bất kỳ pha nào sang `FAILED` (lỗi,
kiểm tra không đạt, hết hạn, hoặc `RUN_ABANDONED` khi owner chết và hết lượt resume). `COMPLETED` có thể
sang `ROLLED_BACK` bằng một lệnh rollback (alias swap ngược về index đã bị thay). `COMPLETED`, `FAILED`,
`ROLLED_BACK`, `SUPERSEDED` và `CANCELLED` là năm trạng thái kết thúc. Chỉ một run được phép ở trạng
thái chưa kết thúc tại một thời điểm.

Bên trong, run đi qua các stage `CREATE_INDICES`, `BUILD_CATEGORIES`, `BUILD_CATALOG`, `BUILD_AVAILABILITY`,
`BUILD_RATING`, `CATCH_UP`, `VERIFY`, `CUTOVER`, `DONE`; stage hiện tại và cursor của nó được lưu ở mỗi trang
nên một run bị bỏ rơi được resume từ đó (tối đa 3 lần). Một run `CANCELLED` hoặc `SUPERSEDED` đã xóa các index
nó dựng; `FAILED` cũng vậy trừ khi các alias đã chuyển.

### Suggestion build

`BUILDING` → `VALIDATING` → `SWAPPED`. Từ `BUILDING` hoặc `VALIDATING` có thể sang
`ABORTED` khi scan/bulk/PIT lỗi, source alias đổi hoặc validation gate không đạt;
`ABORTED` giữ nguyên alias cũ nên API suggest không gián đoạn. Sau `SWAPPED`, version vừa bị thay là
rollback version và luôn được giữ; quay lại nó là một lệnh `_aliases` thủ công theo runbook
([Operations §10](./operations.md#10-runbooks)). Code không đặt trạng thái `ROLLED_BACK` cho suggestion build.

## 7. Authorization rules

- Bốn API storefront v2 là anonymous read-only qua Gateway. Search không nhận
  identity claim ở đường này, không lọc theo user và không có dữ liệu riêng tư
  trong index.
- Reindex là admin API `/api/v2/search/reindex-runs` (tạo, liệt kê, đọc, hủy, rollback), yêu cầu
  identity và role `ADMIN` do Gateway inject sau JWT verification: thiếu identity là `401 UNAUTHORIZED`,
  thiếu role là `403 FORBIDDEN`. Actor audit lấy từ identity đó. Run do control event tự khởi chạy có
  actor `system:catalog-control`. Service phải từ chối identity header nếu endpoint có thể bị bypass
  Gateway.
- `/internal/v1` chỉ dành cho service-to-service qua mTLS; không dùng nó cho
  admin reindex và không có shared-secret fallback.

## 8. Domain errors

| Error code | Điều kiện | Retryable | Interface mapping |
|---|---|---|---|
| `INVALID_QUERY_PARAMETER` | Tham số sai kiểu, ngoài range, vượt giới hạn độ dài/số lượng, token `filter` không phải giá trị facet, `category` không khớp category nào, body không đọc được | No | HTTP 400 |
| `INVALID_CURSOR` | Cursor không decode được, sai chữ ký, quá hạn, quá dài hoặc không khớp bộ tham số | No | HTTP 400 |
| `UNAUTHORIZED` | Gọi admin reindex mà không có trusted identity | No | HTTP 401 |
| `FORBIDDEN` | Gọi admin reindex mà role không phải `ADMIN` | No | HTTP 403 |
| `CATEGORY_NOT_FOUND` | Facet cho category không tồn tại trong index | No | HTTP 404 |
| `REINDEX_RUN_NOT_FOUND` | `run_id` không tồn tại | No | HTTP 404 |
| `REINDEX_ALREADY_RUNNING` | Đã có một run chưa kết thúc (hoặc một run bị bỏ rơi vừa được resume) | Yes, sau khi run cũ xong | HTTP 409 |
| `REINDEX_PRECONDITION_FAILED` | Cluster `red` hoặc thiếu dung lượng đĩa cho một index thứ hai | Yes, khi cluster hồi phục | HTTP 409 |
| `REINDEX_RUN_NOT_CANCELLABLE` | Run đã kết thúc hoặc đang `CUTOVER` | No | HTTP 409 |
| `REINDEX_ROLLBACK_UNAVAILABLE` | Run không `COMPLETED`, không thay index nào, không phải cutover mới nhất, index trước đã bị dọn, hoặc có run khác đang chạy | No | HTTP 409 |

Tất cả error response dùng `application/problem+json` theo RFC 9457, với extension
`code`, `trace_id` (luôn có), `retryable` và `errors[]`; không dùng legacy response shape.
Các lỗi sau là lỗi hạ tầng, không phải domain error, và không được dùng để suy ra
kết luận nghiệp vụ: `ELASTICSEARCH_UNAVAILABLE` (HTTP 503), `SEARCH_TIMEOUT`
(HTTP 504), `INTERNAL_ERROR` (HTTP 500). Lỗi giao thức khi gọi lệch route/method/media type:
`NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `NOT_ACCEPTABLE` (406), `UNSUPPORTED_MEDIA_TYPE` (415);
không bao giờ `500`. Kết quả rỗng là kết quả hợp lệ, không phải lỗi. Bảng đầy đủ kèm cách client xử
lý ở [Interfaces §7](./interfaces.md#7-error-catalog).

## 9. Business examples

- **Giảm giá.** SKU có `base_price_vnd = 34.990.000` và `selling_price_vnd =
  28.891.500` thì `has_promotion = true` và `discount_percentage = 17`.
- **Hết hàng.** Inventory phát `is_in_stock = false`; document còn được index
  và vẫn tìm thấy khi buyer đặt `in_stock=false`, nhưng biến mất khỏi kết quả mặc
  định. Product không đổi trạng thái vì hết hàng.
- **Event trễ.** Một `sku-upserted` với `catalog_revision = 41` tới sau khi document
  đã ở revision `42` thì bị bỏ qua hoàn toàn, kể cả các field mà revision 42 không
  chạm tới.
- **Xóa rồi có event trễ.** Sau `sku-deleted`, một availability snapshot v1 của
  chính SKU đó tới muộn: document đang tombstone nên event bị bỏ qua và ghi metric,
  không làm `is_deleted` trở lại `false`.
- **Đổi cây danh mục.** Product phát `catalog-reindex-requested` sau rename, đổi
  slug hoặc move category. Search lưu bền request (gộp theo root category), đợi nó yên lặng 2 phút rồi tự
  khởi chạy một run: export category trước, Product sau; Product không fan-out toàn subtree trong write
  transaction. Hai lần sửa liền nhau chỉ tốn một run; một lần sửa đến giữa lúc run đang chạy làm run đó
  `SUPERSEDED` và request vẫn còn để run kế tiếp xử lý.
- **Run bị crash.** Process đang reindex chết giữa Build. Lease của nó hết hạn sau tối đa 2 phút; replica
  nào giành được lease sẽ resume từ stage đã lưu thay vì bắt đầu lại, và sau ba lần resume vẫn không xong
  thì run là `FAILED` với `RUN_ABANDONED`, index dở bị xóa và trigger mới được nhận.

## Product result semantics

[ADR-020](./decisions/ADR-020-product-cards-with-cheapest-eligible-sku.md): lọc SKU trước, mỗi Product một kết quả. Chọn SKU giá bán thấp nhất đủ điều kiện. Tất cả field SKU trên card từ cùng representative. PIT giữ eligibility/giá/ranking giữa các trang; total_products chính xác, facet vẫn approximate.
