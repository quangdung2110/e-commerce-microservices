---
doc_id: search-workflows
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence: []
verified_against_commit: null
---

# Search Service — Workflows

> Mỗi workflow phải cho developer biết điều gì xảy ra khi một bước timeout hoặc
> khi cùng một event được xử lý hai lần. Contract chi tiết ở
> [Interfaces](./interfaces.md); guard và field ở [Data](./data.md).

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-SEARCH-001` | Tìm kiếm sản phẩm | API | Buyer | proposed |
| `WF-SEARCH-002` | Gợi ý autocomplete | API | Buyer | proposed |
| `WF-SEARCH-003` | Duyệt cây danh mục | API | Buyer | proposed |
| `WF-SEARCH-004` | Facet của một danh mục | API | Buyer | proposed |
| `WF-SEARCH-005` | Chiếu catalog từ Product | Event | Indexing worker | proposed |
| `WF-SEARCH-006` | Chiếu danh mục và cây dẫn xuất | Event | Indexing worker | proposed |
| `WF-SEARCH-007` | Chiếu tồn kho từ Inventory | Event | Indexing worker | proposed |
| `WF-SEARCH-008` | Chiếu rating từ Review | Event | Indexing worker | proposed |
| `WF-SEARCH-009` | Full reindex ba pha | API/Schedule | Admin, reindex job | proposed |
| `WF-SEARCH-010` | Rebuild suggestion hằng ngày | Schedule | Rebuilder | proposed |
| `WF-SEARCH-011` | Recompute `product_count` | Schedule | Job | proposed |
| `WF-SEARCH-012` | Purge tombstone | Schedule | Job | proposed |

---

## WF-SEARCH-001 — Tìm kiếm sản phẩm

**Mục tiêu.** Trả trang SKU kết quả ổn định, kèm ngữ cảnh Product và facet dùng
được cho bộ lọc.

**Trigger và participants.** `GET /api/v2/search/products` qua Gateway; chỉ Search
và Elasticsearch tham gia.

**Preconditions.** Không có. Index rỗng vẫn là trạng thái hợp lệ.

**Happy path.**
1. Validate và chuẩn hóa tham số: giới hạn `size`, chuẩn hóa `q` (trim, hạ chữ,
   gộp khoảng trắng), phân giải `category` theo id hoặc slug.
2. Nếu có `cursor`: decode base64, kiểm tra khớp với bộ tham số hiện tại.
3. Build query: `multi_match` với `fuzziness: AUTO` khi có `q`; filter bắt buộc
   (`is_deleted=false`, `product_status=ACTIVE`, `sku_status=ACTIVE`) cộng filter
   của buyer; filter attribute dùng `filter_tokens`, OR các value cùng attribute
   code và AND giữa các code khác nhau.
4. Thêm sort đã chọn kèm tie-break `sku_id asc`, `search_after` khi có cursor,
   và các aggregation facet với `cardinality` trên `product_id`. Không dùng
   `collapse` hoặc `inner_hits`.
5. Transform mỗi hit thành đúng một SKU item, tính `discount_percentage` theo
   `SEARCH-BR-005`, sinh `next_cursor` từ sort values của hit cuối.

**State/data changes.** Không có. Đây là luồng thuần đọc.

**Output.** Danh sách SKU item, `total_skus` chính xác, facet kèm
`count_semantics = DISTINCT_PRODUCT` và `count_accuracy = APPROXIMATE`,
và `next_cursor` (`null` khi hết dữ liệu). Cùng Product có thể có nhiều item.

**Failure paths.** Tham số sai → `INVALID_QUERY_PARAMETER`. Cursor hỏng hoặc
không khớp tham số → `INVALID_CURSOR`. Elasticsearch không sẵn sàng →
`ELASTICSEARCH_UNAVAILABLE`. Vượt ngân sách thời gian → `SEARCH_TIMEOUT`. Kết quả
rỗng không phải lỗi.

**Timeout và retry.** Timeout truy vấn 5s, fail fast. Search không tự retry; client
quyết định thử lại. Không có retry nào ẩn ở tầng server.

**Idempotency.** Thuần đọc, không có tác dụng phụ.

**Concurrency.** Kết quả có thể đổi giữa hai trang liên tiếp vì indexing chạy song
song; `search_after` cộng tie-break giữ cho pagination không lặp hoặc bỏ sót
document không đổi, nhưng document mới vẫn có thể xuất hiện.

**Metrics.** Latency theo endpoint, tỉ lệ lỗi, tỉ lệ kết quả rỗng, tỉ lệ dùng
cursor, thời gian truy vấn Elasticsearch.

**Test scenarios.** Không có `q`; có `q` kèm lỗi chính tả; filter nhiều giá trị;
range giá biên; `in_stock=false`; từng kiểu sort; phân trang đến hết; cursor lỗi;
cursor dùng lại sau khi đổi query/filter/sort; một Product nhiều SKU trả nhiều
SKU item theo sort; Elasticsearch timeout; kiểm tra facet đếm DISTINCT product;
kiểm tra hàng `DRAFT`/`HIDDEN`/đã xóa không bao giờ lộ ra.

---

## WF-SEARCH-002 — Gợi ý autocomplete

**Mục tiêu.** Trả gợi ý trong ngân sách vài chục millisecond để dropdown theo kịp
tốc độ gõ.

**Happy path.** `GET /api/v2/search/suggestions`: validate `q` tối thiểu 2 ký tự → query index `search_suggestions`
→ sort `weight` giảm dần, tie-break `text.keyword` → trả `text` và `type`. Khi buyer chọn
một gợi ý, client dùng nguyên `text` làm `q` cho luồng tìm kiếm sản phẩm; đây là
cụm từ giúp hoàn tất truy vấn, không phải link đến một Product, SKU hoặc Category.
`type` chỉ phục vụ hiển thị/phân loại trong dropdown, không mang ngữ nghĩa điều hướng.

**State/data changes.** Không có.

**Failure paths.** `q` quá ngắn → `INVALID_QUERY_PARAMETER`. Elasticsearch lỗi →
`ELASTICSEARCH_UNAVAILABLE`; client phải coi dropdown rỗng là chấp nhận được và
không chặn thao tác tìm kiếm.

**Timeout và retry.** Timeout 2s, không retry.

**Idempotency và concurrency.** Thuần đọc. Trong lúc rebuilder swap alias, request
đang chạy vẫn đọc index cũ; không có cửa sổ lỗi.

**Metrics.** Latency p50/p99, tỉ lệ gợi ý rỗng theo prefix, QPS.

**Test scenarios.** Prefix 1 ký tự; prefix có dấu và không dấu cùng ra một kết
quả; `limit` biên; index rỗng; alias đang swap; chọn item `CATEGORY` và `PRODUCT`
đều dùng đúng `text` làm `q` cho tìm kiếm sản phẩm, không điều hướng hoặc suy ra
entity từ `type`.

---

## WF-SEARCH-003 — Duyệt cây danh mục

**Mục tiêu.** Trả cây danh mục kèm số lượng sản phẩm để dựng sidebar.

**Happy path.** `GET /api/v2/search/categories`: đọc toàn bộ document danh mục chưa
xóa, sort theo `level` rồi `category_id`, dựng cây trong bộ nhớ theo `parent_id`,
trả node lồng `children` kèm `product_count` và mốc thời gian tính số đếm.

**Failure paths.** Elasticsearch lỗi → `ELASTICSEARCH_UNAVAILABLE`. Node mồ côi
(có `parent_id` trỏ tới danh mục không tồn tại) bị bỏ khỏi cây và ghi metric, thay
vì làm hỏng cả response.

**Idempotency và concurrency.** Thuần đọc. `product_count` có thể trễ hơn kết quả
search; đây là hành vi đã biết, được nêu trong
[ADR-008](./decisions/ADR-008-category-product-count-recompute.md).

**Metrics.** Latency, số node trả về, số node mồ côi.

**Test scenarios.** Cây nhiều cấp; danh mục bị xóa không xuất hiện; node mồ côi;
`product_count` bằng 0.

---

## WF-SEARCH-004 — Facet của một danh mục

**Mục tiêu.** Trả đúng bộ lọc khả dụng cho trang danh mục, với số đếm khớp danh
sách sản phẩm hiển thị.

**Happy path.** `GET /api/v2/search/categories/{category_id}/facets`: kiểm tra danh mục tồn tại → aggregation trên `products` giới hạn
theo nhánh danh mục và bộ filter hiển thị mặc định → mỗi `terms` kèm `cardinality`
trên `product_id` → trả nhóm filter kèm `count_semantics`.

**Failure paths.** Danh mục không tồn tại → `CATEGORY_NOT_FOUND`. Aggregation quá
hạn → `SEARCH_TIMEOUT`.

**Concurrency.** Số đếm facet và số đếm trong `WF-SEARCH-001` được tính bằng cùng
bộ filter, nên phải khớp nhau trong sai số của `cardinality` xấp xỉ.

**Test scenarios.** Danh mục lá và danh mục cha; danh mục rỗng; danh mục không tồn
tại; kiểm chứng số đếm khớp giữa hai API.

---

## WF-SEARCH-005 — Chiếu catalog từ Product

**Mục tiêu.** Giữ SKU document khớp với snapshot catalog mới nhất của Product.

**Trigger.** Event trên `t.product.events`; consumer group `search-indexer`.

**Preconditions.** Event khớp Product CloudEvents v1: `id`, `type`, `time`,
`sequence = catalog_revision`, `data.product` và `data.sku` theo
[Product interfaces](../product/interfaces.md).

**Happy path.**
1. Poll batch; lọc CloudEvents `type` theo allowlist, ignore và ACK phần còn lại.
2. Validate field bắt buộc; thiếu thì ghi metric contract violation và bỏ qua.
3. Enrichment: đọc category projection theo `category_id` để materialize ancestor
   IDs/slugs; miss thì bỏ trống field category dẫn xuất và vẫn index (`SEARCH-BR-008`).
4. Transform: chuẩn hóa attribute thành token có type, tạo `filter_tokens`, tính
   `has_promotion` và copy `thumbnail_storage_key` đã được Product resolve. Giá trị
   `null` là hợp lệ; API response adapter dựng placeholder, không tự chọn từ `media[]`.
5. Sinh lệnh ghi cho từng SKU liên quan bằng
   [`catalog_patch.painless`](./schema/scripts/catalog_patch.painless) với
   `scripted_upsert`, hoặc
   [`catalog_tombstone.painless`](./schema/scripts/catalog_tombstone.painless) cho
   event xóa (không upsert).
6. Gửi `_bulk` tới `products_write`, đọc kết quả từng item, rồi commit offset.

**State/data changes.** Chỉ các field thuộc Product cộng `catalog_revision`,
`catalog_indexed_at`, `last_catalog_event_id`. Field tồn kho và rating không bị chạm
(`SEARCH-BR-009`).

**Events emitted.** Không có.

**Failure paths.** Elasticsearch timeout/429/503 → không commit offset, để Kafka
gửi lại. Mapping conflict hoặc payload hỏng → log, metric `search_payload_invalid_total`,
bỏ qua, ACK. Document đang tombstone → script bỏ qua, ghi metric zombie.
Enrichment miss → metric `search_category_enrichment_miss_total`, vẫn index.

**Timeout và retry.** Timeout ghi Elasticsearch ở mức giây; retry thuộc về Kafka
qua việc không commit offset. Không có DLQ; lỗi không tự hồi phục được phục hồi
bằng full reindex.

**Idempotency.** Guard `catalog_revision` cộng snapshot semantics: apply lại cùng
event là no-op.

**Concurrency.** Event của cùng product luôn cùng partition nên tuần tự. Event của
product khác nhau chạy song song mà không tranh chấp vì mỗi document thuộc đúng
một product.

**Recovery.** Chạy lại từ Kafka nếu event còn trong retention; ngoài retention thì
dùng `WF-SEARCH-009`.

**Metrics.** `search_indexing_total{event_type,status}`, độ trễ end-to-end từ
`timestamp` của event tới `indexed_at`, consumer lag, số zombie bị chặn.

**Test scenarios.** Tạo product nhiều SKU; cập nhật chỉ field product-level; đổi
giá; đổi trạng thái; xóa SKU rồi xóa product; event trùng; event revision cũ;
event tới sau tombstone; enrichment miss; batch có một record hỏng và các record
còn lại vẫn phải được xử lý.

---

## WF-SEARCH-006 — Chiếu snapshot danh mục Product

**Mục tiêu.** Giữ index category khớp snapshot cấu trúc Product mà không biến
Search thành owner của cây danh mục.

**Trigger.** Category CloudEvents v1 trên `t.category.events`; control event trên
`t.catalog.control.events` khi tree đổi.

**Happy path.**
1. Apply `category-upserted` bằng `sequence = category_revision`; transform path
   Product gửi thành `ancestor_ids`, `ancestor_slugs`, `level` và `is_leaf`.
2. `category-deleted` tombstone node.
3. `catalog-reindex-requested` được coalesce theo root category/revision, sau đó
   chạy reindex từ category export trước Product export. Không `_update_by_query`
   theo path trong request worker.

**State/data changes.** Category document; SKU documents chỉ nhận category data mới
qua full reindex.

**Failure paths.** Snapshot path sai/có cycle → reject event, ghi contract-drift
metric và giữ projection cũ. Reindex lỗi trước alias swap → read/write alias cũ giữ nguyên.

**Idempotency.** Guard `category_revision`; control event coalesce theo root category
và revision nên lặp lại vô hại.

**Concurrency.** Category event cùng key có ordering; reindex vẫn singleton.

**Recovery.** Export/reindex là công cụ sửa lệch category duy nhất.

**Metrics.** Số node cập nhật, category revision, số control event coalesce, thời
gian và kết quả reindex.

**Test scenarios.** Category snapshot path hợp lệ; event revision cũ; path có cycle;
rename/move phát control event; reindex category trước Product và alias không đổi khi lỗi.

---

## WF-SEARCH-007 — Chiếu tồn kho từ Inventory

**Mục tiêu.** Giữ `available_quantity` và `is_in_stock` phản ánh stock snapshot mới
nhất mà Inventory công bố.

**Trigger.** `com.ecommerce.inventory.stock-snapshot.v1` trên
`t.inventory.stock.events.v1`, key `sku_id`.

**Happy path.** Lọc event type → validate cặp `product_id` + `sku_id` và
`stock_revision` → apply lên `products_write/_update/{sku_id}` không upsert →
`is_in_stock` được tính lại → commit offset.

**Failure paths.** `document_missing_exception` → metric
`search_document_missing_total`, bỏ qua, ACK: SKU chưa được index hoặc vừa bị xóa
là tình huống bình thường. Document tombstone → script bỏ qua. Lỗi tạm thời của
Elasticsearch → không commit offset.

**Idempotency và ordering.** Guard `stock_revision` theo revision đơn điệu; key
`sku_id` đảm bảo ordering trong cùng SKU. Timestamp không được dùng làm guard.

**Concurrency.** Event tồn kho và event catalog của cùng SKU đến từ hai partition
khác nhau và có thể ghi đồng thời; chúng không chạm cùng field và guard độc lập
nên không có lost update.

**Recovery.** Gọi Inventory stock snapshot export, import theo `sku_id`, rồi replay
topic từ `replay_from_offsets`. Guard revision làm event trước snapshot hoặc duplicate
thành no-op. Contract còn `proposed`; trước producer rollout, workflow chỉ chạy ở
compatibility mode và không tuyên bố recovery hoàn toàn.

**Metrics.** Độ trễ tồn kho, tỉ lệ `document_missing`, tỉ lệ event bị guard chặn.

**Test scenarios.** Về 0 rồi có hàng lại; event revision cũ/trùng; SKU chưa index;
SKU đã tombstone; cặp `product_id`/`sku_id` không khớp; bootstrap snapshot rồi
replay event; hai event cùng timestamp nhưng revision khác nhau.

---

## WF-SEARCH-008 — Chiếu rating từ Review

**Mục tiêu.** Giữ rating hiển thị khớp số liệu tổng hợp của Review.

**Trigger.** `REVIEW_METRICS_UPDATED` trên `t.review.events`, key `product_id`.

**Happy path.** `_update_by_query` trên `products_write` theo `term product_id` với
[`review_metrics_patch.painless`](./schema/scripts/review_metrics_patch.painless),
`conflicts=proceed` và throttle; guard `review_revision`.

**Failure paths.** Không có document khớp → không phải lỗi, ghi metric. Lỗi tạm
thời → không commit offset. Query chạy quá lâu → có thể để lại trạng thái áp dụng
một phần; lần chạy lại sẽ hội tụ vì thao tác là ghi giá trị tuyệt đối.

**Idempotency và ordering.** Guard `review_revision`; key `product_id` giữ thứ tự
trong cùng product.

**Concurrency.** Chạy song song với event catalog của cùng product; hai bên chạm
tập field rời nhau.

**Metrics.** Thời gian `_update_by_query`, số document cập nhật, độ trễ rating.

**Test scenarios.** Product một SKU và nhiều SKU; event trễ; event trùng; product
chưa có document; rating giảm sau khi review bị xóa.

---

## WF-SEARCH-009 — Full reindex ba pha

**Mục tiêu.** Đổi mapping hoặc rebuild dữ liệu mà không dừng đọc và ghi.

**Trigger.** `POST /api/v2/search/reindex-runs` bởi admin, hoặc lịch bảo trì.

**Preconditions.** Cluster `green`; còn dung lượng cho một index đầy đủ kèm
replica; không có run nào chưa kết thúc; mapping mới đã được review trong
[`schema/`](./schema).

**Happy path.**
1. **Build catalog.** Tạo `products_v{N+1}` từ mapping mới. Export Category trước,
   import toàn bộ Category snapshot và lưu `replay_from_offsets` của
   `t.category.events`; rồi keyset export Product theo chunk, lưu
   `replay_from_offsets` của `t.product.events` và ghi checkpoint sau mỗi chunk để
   resume. Offsets của mỗi source do Product chụp trước scan, cố định trong export
   session. SKU mới nhận giá trị rating mặc định `0/0` cho tới khi snapshot Review
   được apply.
2. **Build stock.** Lấy Inventory stock snapshot export; import mọi SKU theo
   `stock_revision`. Export trả `replay_from_offsets` đã chụp trước lúc scan nên
   event cũ/trùng không ghi đè snapshot.
3. **Build rating.** Lấy Review rating aggregate snapshot export sau catalog.
   Import aggregate vào toàn bộ SKU khớp `product_id` theo `review_revision`.
   Export trả `replay_from_offsets` đã chụp trước lúc scan; snapshot không upsert
   SKU và không thay đổi catalog/stock field.
4. **Catch-up.** Chỉ sau khi import xong mọi snapshot, chạy consumer group tạm
   `search-reindex-{run_id}` seek inclusive từ `replay_from_offsets` Product
   (`t.category.events`, `t.product.events`), stock và review; ghi **trực tiếp** vào
   `products_v{N+1}` chứ không qua write alias. Search không tự chụp catalog
   watermark. Guard giữ nguyên như luồng thường. Kết thúc khi lag bằng 0 qua nhiều
   chu kỳ poll liên tiếp, publisher lag đã hội tụ và không phát sinh lỗi mới trong
   cửa sổ quan sát.
5. **Cutover.** Kiểm tra chênh lệch số document trong ngưỡng cho phép, sample diff
   trên tập SKU nóng, smoke test các truy vấn phổ biến, rồi chuyển cả read alias
   và write alias sang index mới bằng **một** lệnh `_aliases` atomic.

**State/data changes.** Index mới được tạo; alias trỏ sang index mới; index cũ giữ
lại tối thiểu 24 giờ.

**Failure paths.** Build lỗi → resume từ checkpoint hoặc hủy run và xóa index mới;
alias không đổi nên traffic không bị ảnh hưởng. Bất kỳ snapshot export nào lỗi hoặc
thiếu `replay_from_offsets` → không được cutover. Catch-up/publisher lag không hội tụ
→ hủy run. `catalog-reindex-requested` mới trong lúc Build/Catch-up → đánh dấu run
`SUPERSEDED`, giữ alias cũ và coalesce run mới. Kiểm tra trước cutover không đạt → hủy
run. Lỗi sau cutover → swap alias ngược về index cũ.

**Timeout và retry.** Mỗi chunk có timeout và retry có backoff cho 429/503. Toàn
bộ run có giới hạn thời gian; vượt giới hạn thì chuyển `FAILED` thay vì chạy vô
hạn.

**Idempotency.** Trigger trùng bị chặn bởi `REINDEX_ALREADY_RUNNING`. Backfill có
checkpoint nên chạy lại không nhân bản dữ liệu; ghi vào index mới đều có guard.

**Concurrency.** Đúng một run tại một thời điểm; job cần lock singleton. Consumer
group chính vẫn chạy bình thường trên index cũ trong suốt quá trình.

**Compensation.** Rollback bằng alias swap ngược. Nếu rollback muộn hơn cửa sổ
catch-up, chấp nhận mất vài phút dữ liệu mới trên index cũ và phải chạy lại reindex
([ADR-006](./decisions/ADR-006-three-phase-reindex.md)).

**Metrics.** Tiến độ từng source của build, export-session ID và offsets theo
source/topic, tuổi snapshot stock/rating, lag publisher/catch-up theo topic, số run
supersede, chênh lệch số document, thời lượng từng pha, kết quả run.

**Test scenarios.** Reindex trên catalog rỗng; rating tồn tại từ trước reindex;
product chưa có review nhận `0/0`; mutation Category/Product/Rating trong lúc export;
event cũ/trùng sau snapshot; control event supersede run; reindex khi đang có tải ghi;
hủy giữa build; catch-up không hội tụ; cutover rồi rollback; trigger trùng.

---

## WF-SEARCH-010 — Rebuild suggestion hằng ngày

**Mục tiêu.** Làm mới bộ gợi ý mà không gián đoạn API suggest.

**Trigger.** Lịch chạy hằng ngày vào giờ thấp điểm.

**Happy path.**
1. Lấy distributed lease lock `suggestion-rebuild`, sinh `run_id`, resolve read
   alias `products` và `categories` thành hai index vật lý nguồn, rồi mở PIT cho
   từng index. Lưu các source index và version hiện tại của alias
   `search_suggestions` làm metadata của run.
2. Tạo `search_suggestions_v{N+1}`.
3. Nạp ứng viên theo hai nguồn, scan toàn bộ mỗi PIT bằng `search_after` với sort
   cố định; không scan qua alias thay đổi được:
   - **Category candidate:** tên của mỗi Category có `is_deleted = false` và
     `product_count > 0`. Category rỗng không được gợi ý để buyer không chọn một
     cụm từ rồi nhận kết quả rỗng.
   - **Product candidate:** tên Product không rỗng của mỗi `product_id` có ít
     nhất một SKU thỏa `is_deleted = false`, `product_status = ACTIVE`,
     `sku_status = ACTIVE` và `is_in_stock = true`. Index catalog là SKU-first,
     nhưng một Product chỉ đóng góp một candidate theo tên Product; không tạo
     candidate riêng cho từng SKU hoặc thuộc tính biến thể.
4. Chuẩn hóa và khử trùng lặp candidate theo `(type, scope, language,
   text_normalized)`. Tính `weight` sau dedupe từ product count hoặc rating/review
   count; một nhóm Product trùng tên dùng điểm cao nhất, không cộng dồn theo số
   Product/SKU. Quy tắc chuẩn hóa, công thức và tie-break ở
   [Data §1.2](./data.md#12-chuẩn-hóa-khử-trùng-lặp-và-ranking-suggestion).
   Bulk index theo chunk.
5. Refresh index mới, rồi chạy **validation gate** trên chính index vật lý mới:
   không có bulk item failure; số document thực tế đạt `suggestion_min_docs`; nếu
   có version hiện hành thì số mới không dưới `80%` số document cũ; số unique output
   ID khớp số document thực tế; và mọi hot prefix cấu hình đều có ít nhất một kết
   quả khi dùng đúng query của API suggest.
6. Resolve lại alias `products` và `categories`. Nếu một trong hai không còn trỏ
   tới source index đã chụp ở bước 1, abort run: build không được publish một tập
   term lấy từ thế hệ catalog cũ sau khi full reindex đã cutover.
7. Atomic swap alias `search_suggestions` sang index mới, giữ version vừa bị thay
   thế làm rollback version, đóng PIT và release lock.

**Failure paths.** Không lấy được lock → bỏ qua run, không chờ hoặc chạy song song.
Lỗi scan/bulk/PIT, source alias đổi, hoặc validation gate không đạt → `ABORTED`,
xóa index mới, đóng PIT/release lock và giữ alias cũ. Phát hiện chất lượng kém sau
swap → atomic rollback alias về version trước, đánh dấu `ROLLED_BACK`; không xóa
rollback version trong cửa sổ retention.

**Idempotency.** Mỗi lần chạy tạo index mới; chạy lại trong cùng ngày chỉ tạo một
version khác và không làm hỏng dữ liệu đang phục vụ.

**Concurrency.** Singleton bằng distributed lease lock trong Elasticsearch. Lock có
`owner_run_id`, `lease_expires_at` và fencing token đơn điệu; owner renew lease trong
run và chỉ owner được release. Không renew được lease hoặc không còn fencing token
hợp lệ → owner phải abort. Mọi alias swap kiểm tra fencing token, nên khi lease hết
hạn sau khi owner chết, run sau có thể lấy lock còn owner cũ không thể cutover.

**Metrics.** Số candidate/document, thời lượng scan/bulk/validation, bulk failure,
kết quả từng check của validation gate, source index version, run abort do source
đổi, lock contention/lease renewal, version hiện hành và rollback version.

**Test scenarios.** Catalog rỗng; ngưỡng validation không đạt; swap thành công;
rollback; sản phẩm đã xóa không còn xuất hiện ở lần build kế tiếp; một Product có
nhiều SKU chỉ sinh một Product candidate; Product không còn SKU hiển thị/còn hàng
không sinh candidate; Category rỗng hoặc đã xóa không sinh candidate; khác biệt dấu,
hoa-thường hoặc whitespace trong cùng một loại chỉ sinh một suggestion; cùng text
nhưng khác `type` giữ hai suggestion; Product trùng tên không cộng dồn weight; rating,
review count và promotion cho đúng thứ tự weight đã công bố; bulk item failure;
document count tụt quá ngưỡng 80%; hot prefix rỗng; full reindex swap source alias
giữa build; hai replica cùng trigger; owner chết và lease hết hạn; swap rồi rollback.

---

## WF-SEARCH-011 — Recompute `product_count`

**Mục tiêu.** Giữ số đếm hiển thị của danh mục đúng mà không cộng trừ theo từng
event.

**Trigger.** Lịch chạy định kỳ; cũng có thể chạy thủ công sau sự cố.

**Happy path.** Aggregation `terms` theo `category_id` trên `products` với đúng bộ
filter hiển thị, kèm `cardinality` trên `product_id` → ghi giá trị tuyệt đối vào
document danh mục bằng
[`category_product_count.painless`](./schema/scripts/category_product_count.painless)
cùng mốc thời gian tính toán.

**Failure paths.** Aggregation lỗi hoặc timeout → giữ nguyên số cũ, ghi metric; số
đếm cũ tốt hơn số đếm sai. Danh mục không còn sản phẩm → ghi 0, không xóa document.

**Idempotency.** Thao tác ghi giá trị tuyệt đối, hoàn toàn tự phục hồi; chạy lại
bao nhiêu lần cũng hội tụ về cùng kết quả.

**Concurrency.** Singleton. Chạy song song với indexing là an toàn vì kết quả chỉ
là ảnh chụp tại một thời điểm.

**Metrics.** Thời lượng job, số danh mục cập nhật, độ lệch lớn nhất so với lần
trước, tuổi của `product_count_updated_at`.

**Test scenarios.** Danh mục rỗng; danh mục lớn; sản phẩm nhiều SKU chỉ được đếm
một lần; chạy hai lần liên tiếp cho cùng kết quả.

---

## WF-SEARCH-012 — Purge tombstone

**Mục tiêu.** Xóa vật lý document đã tombstone sau khi không còn nguy cơ event trễ
làm chúng sống lại.

**Trigger.** Lịch chạy hằng ngày vào giờ thấp điểm.

**Happy path.** Tìm document có `is_deleted = true` và `deleted_at` cũ hơn cửa sổ
bảo vệ → `_delete_by_query` theo lô với throttle → ghi số lượng đã xóa.

**Preconditions.** Cửa sổ bảo vệ phải dài hơn retention của mọi topic Search tiêu
thụ ([ADR-011](./decisions/ADR-011-tombstone-and-purge-window.md)).

**Failure paths.** Lỗi giữa chừng → lần chạy sau tiếp tục; không có trạng thái cần
dọn. Xóa nhầm là không thể phục hồi trực tiếp; phục hồi bằng reindex.

**Idempotency.** Điều kiện lọc theo trạng thái hiện tại nên chạy lại là an toàn.

**Concurrency.** Singleton, throttle để không ảnh hưởng traffic đọc.

**Metrics.** Số document purge, thời lượng, tuổi tombstone lớn nhất còn lại.

**Test scenarios.** Tombstone chưa tới hạn không bị xóa; tombstone quá hạn bị xóa;
event trễ tới sau khi đã purge phải không tạo lại document từ nguồn stock/review.

---

## Yêu cầu test chung

Mọi workflow ghi dữ liệu phải có test cho: happy path, biên validation, event
trùng, event cũ hơn guard, event tới sau tombstone, timeout và retry, xử lý đồng
thời, Elasticsearch hoặc Kafka không sẵn sàng, và đường phục hồi tương ứng. Test
tích hợp dùng Elasticsearch và Kafka thật qua Testcontainers, không mock client.
