---
doc_id: search-workflows
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence:
  - java/services/search-service/src/main/java/com/ecommerce/search/service
  - java/services/search-service/src/main/java/com/ecommerce/search/worker
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

**Mục tiêu.** Trả trang kết quả ổn định, đã gom nhóm theo product, kèm facet dùng
được cho bộ lọc.

**Trigger và participants.** `GET /api/v1/search/products` qua Gateway; chỉ Search
và Elasticsearch tham gia.

**Preconditions.** Không có. Index rỗng vẫn là trạng thái hợp lệ.

**Happy path.**
1. Validate và chuẩn hóa tham số: giới hạn `size`, chuẩn hóa `q` (trim, hạ chữ,
   gộp khoảng trắng), phân giải `category` theo id hoặc slug.
2. Nếu có `cursor`: decode base64, kiểm tra khớp với bộ tham số hiện tại.
3. Build query: `multi_match` với `fuzziness: AUTO` khi có `q`; filter bắt buộc
   (`is_deleted=false`, `product_status=ACTIVE`, `sku_status=ACTIVE`) cộng filter
   của buyer; mỗi giá trị filter thuộc tính kiểm tra cả `searchable_attributes` và
   `variant_attributes` với `minimum_should_match: 1`.
4. Thêm `collapse` theo `product_id`, sort đã chọn kèm tie-break `sku_id asc`,
   `search_after` khi có cursor, và các aggregation facet với `cardinality` trên
   `product_id`.
5. Transform hit: tính `discount_percentage` theo `SEARCH-BR-005`, sinh
   `next_cursor` từ sort values của hit cuối.

**State/data changes.** Không có. Đây là luồng thuần đọc.

**Output.** Danh sách item, tổng số, facet kèm `count_semantics`, và
`next_cursor` (`null` khi hết dữ liệu).

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
cursor dùng lại sau khi đổi filter; Elasticsearch timeout; kiểm tra facet đếm
DISTINCT product; kiểm tra hàng `DRAFT`/`HIDDEN`/đã xóa không bao giờ lộ ra.

---

## WF-SEARCH-002 — Gợi ý autocomplete

**Mục tiêu.** Trả gợi ý trong ngân sách vài chục millisecond để dropdown theo kịp
tốc độ gõ.

**Happy path.** Validate `q` tối thiểu 2 ký tự → query index `search_suggestions`
→ sort `weight` giảm dần, tie-break `text.keyword` → trả `text` và `type`.

**State/data changes.** Không có.

**Failure paths.** `q` quá ngắn → `INVALID_QUERY_PARAMETER`. Elasticsearch lỗi →
`ELASTICSEARCH_UNAVAILABLE`; client phải coi dropdown rỗng là chấp nhận được và
không chặn thao tác tìm kiếm.

**Timeout và retry.** Timeout 2s, không retry.

**Idempotency và concurrency.** Thuần đọc. Trong lúc rebuilder swap alias, request
đang chạy vẫn đọc index cũ; không có cửa sổ lỗi.

**Metrics.** Latency p50/p99, tỉ lệ gợi ý rỗng theo prefix, QPS.

**Test scenarios.** Prefix 1 ký tự; prefix có dấu và không dấu cùng ra một kết
quả; `limit` biên; index rỗng; alias đang swap.

---

## WF-SEARCH-003 — Duyệt cây danh mục

**Mục tiêu.** Trả cây danh mục kèm số lượng sản phẩm để dựng sidebar.

**Happy path.** Đọc toàn bộ document danh mục chưa xóa, sort theo `level` rồi
`category_id`, dựng cây trong bộ nhớ theo `parent_id`, trả cây kèm `product_count`
và mốc thời gian tính số đếm.

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

**Happy path.** Kiểm tra danh mục tồn tại → aggregation trên `products` giới hạn
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

**Preconditions.** Event có `event_id`, `event_type`, `catalog_revision` và
snapshot payload theo [Product interfaces](../product/interfaces.md).

**Happy path.**
1. Poll batch; lọc `event_type` theo allowlist, ignore và ACK phần còn lại.
2. Validate field bắt buộc; thiếu thì ghi metric contract violation và bỏ qua.
3. Enrichment: đọc danh mục theo `category_id` để lấy `category_name` và
   `category_path`; miss thì bỏ trống hai field đó và vẫn index (`SEARCH-BR-008`).
4. Transform: thuộc tính thành token `code:value`, phân giải `thumbnail_url` theo
   media của product rồi tới media của SKU, tính `has_promotion`.
5. Sinh lệnh ghi cho từng SKU liên quan bằng
   [`catalog_patch.painless`](./schema/scripts/catalog_patch.painless) với
   `scripted_upsert`, hoặc
   [`catalog_tombstone.painless`](./schema/scripts/catalog_tombstone.painless) cho
   event xóa (không upsert).
6. Gửi `_bulk` tới `products_write`, đọc kết quả từng item, rồi commit offset.

**State/data changes.** Chỉ các field thuộc Product cộng `catalog_revision`,
`indexed_at`, `last_catalog_event_id`. Field tồn kho và rating không bị chạm
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

## WF-SEARCH-006 — Chiếu danh mục và cây dẫn xuất

**Mục tiêu.** Giữ index danh mục khớp Product và giữ giá trị dẫn xuất của cây nhất
quán trên cả hai index.

**Trigger.** Event trên `t.category.events`.

**Happy path.**
1. Apply snapshot danh mục bằng
   [`category_patch.painless`](./schema/scripts/category_patch.painless), guard
   `category_version`; `CATEGORY_DELETED` thì tombstone node.
2. Recompute `path`, `level`, `is_leaf` cho toàn bộ cây từ chính index `categories`
   (quy mô hàng trăm node), ghi lại bằng
   [`category_tree_patch.painless`](./schema/scripts/category_tree_patch.painless)
   với `tree_version` tăng dần.
3. Với mỗi danh mục có `name` hoặc `path` thay đổi, đồng bộ `category_name` và
   `category_path` sang SKU document bằng `_update_by_query` trên `products_write`
   với `conflicts=proceed` và throttle.

**State/data changes.** Document danh mục; các field dẫn xuất của cây; hai field
denormalize trên SKU document.

**Failure paths.** Cây có chu trình hoặc node mồ côi → dừng bước recompute, giữ giá
trị cũ, ghi alert. `_update_by_query` lỗi giữa chừng → chạy lại toàn bộ là an toàn
vì thao tác ghi giá trị tuyệt đối.

**Idempotency.** Guard `category_version` cho snapshot; `tree_version` cho kết quả
recompute; đồng bộ denormalize là ghi đè giá trị hiện hành nên lặp lại vô hại.

**Concurrency.** Recompute cây phải chạy tuần tự trong một consumer; nhiều event
danh mục trong cùng batch chỉ cần một lần recompute.

**Recovery.** Recompute cây có thể chạy thủ công mà không cần event; đây là công cụ
sửa lệch đầu tiên trước khi nghĩ tới reindex.

**Metrics.** Số node cập nhật, thời gian recompute, số document SKU đồng bộ, số
node mồ côi.

**Test scenarios.** Tạo danh mục lá; đổi tên node cha rồi kiểm tra `category_path`
của SKU cháu; di chuyển node; xóa node; event danh mục cũ; cây có chu trình.

---

## WF-SEARCH-007 — Chiếu tồn kho từ Inventory

**Mục tiêu.** Giữ `available_quantity` và `is_in_stock` phản ánh tồn kho gần nhất
mà Inventory công bố.

**Trigger.** `INVENTORY_STOCK_CHANGED` trên `t.inventory.events`, key `sku_id`.

**Happy path.** Lọc event type → kiểm tra cặp `product_id` + `sku_id` → apply
[`stock_patch.painless`](./schema/scripts/stock_patch.painless) lên
`products_write/_update/{sku_id}` không upsert → `is_in_stock` được tính lại trong
script → commit offset.

**Failure paths.** `document_missing_exception` → metric
`search_document_missing_total`, bỏ qua, ACK: SKU chưa được index hoặc vừa bị xóa
là tình huống bình thường. Document tombstone → script bỏ qua. Lỗi tạm thời của
Elasticsearch → không commit offset.

**Idempotency và ordering.** Guard `stock_version` theo timestamp của event; key
`sku_id` đảm bảo ordering trong cùng SKU. Event trễ không thể hạ số tồn kho về giá
trị cũ.

**Concurrency.** Event tồn kho và event catalog của cùng SKU đến từ hai partition
khác nhau và có thể ghi đồng thời; chúng không chạm cùng field và guard độc lập
nên không có lost update.

**Recovery.** Không thể replay ngoài retention của Kafka; nguồn phục hồi là chính
Inventory. Nếu lệch kéo dài, vận hành phải xin Inventory phát lại snapshot hoặc
chấp nhận `is_in_stock` sai cho tới event kế tiếp của SKU đó — đây là hạn chế đã
biết và được ghi ở [ADR-009](./decisions/ADR-009-stock-projection-ownership.md).

**Metrics.** Độ trễ tồn kho, tỉ lệ `document_missing`, tỉ lệ event bị guard chặn.

**Test scenarios.** Về 0 rồi có hàng lại; event trễ; event trùng; SKU chưa index;
SKU đã tombstone; cặp `product_id`/`sku_id` không khớp.

---

## WF-SEARCH-008 — Chiếu rating từ Review

**Mục tiêu.** Giữ rating hiển thị khớp số liệu tổng hợp của Review.

**Trigger.** `REVIEW_METRICS_UPDATED` trên `t.review.events`, key `product_id`.

**Happy path.** `_update_by_query` trên `products_write` theo `term product_id` với
[`review_metrics_patch.painless`](./schema/scripts/review_metrics_patch.painless),
`conflicts=proceed` và throttle; guard `review_version`.

**Failure paths.** Không có document khớp → không phải lỗi, ghi metric. Lỗi tạm
thời → không commit offset. Query chạy quá lâu → có thể để lại trạng thái áp dụng
một phần; lần chạy lại sẽ hội tụ vì thao tác là ghi giá trị tuyệt đối.

**Idempotency và ordering.** Guard `review_version`; key `product_id` giữ thứ tự
trong cùng product.

**Concurrency.** Chạy song song với event catalog của cùng product; hai bên chạm
tập field rời nhau.

**Metrics.** Thời gian `_update_by_query`, số document cập nhật, độ trễ rating.

**Test scenarios.** Product một SKU và nhiều SKU; event trễ; event trùng; product
chưa có document; rating giảm sau khi review bị xóa.

---

## WF-SEARCH-009 — Full reindex ba pha

**Mục tiêu.** Đổi mapping hoặc rebuild dữ liệu mà không dừng đọc và ghi.

**Trigger.** `POST /internal/v1/search/reindex` bởi admin, hoặc lịch bảo trì.

**Preconditions.** Cluster `green`; còn dung lượng cho một index đầy đủ kèm
replica; không có run nào chưa kết thúc; mapping mới đã được review trong
[`schema/`](./schema).

**Happy path.**
1. **Build.** Tạo `products_v{N+1}` từ mapping mới. Backfill bằng reindex nội bộ
   theo chunk, ghi checkpoint sau mỗi chunk để resume. Với các field Product sở
   hữu, đối chiếu bổ sung từ `GET /internal/products/export`.
2. **Catch-up.** Ghi lại watermark offset của bốn topic tại thời điểm bắt đầu
   build; chạy consumer group tạm `search-reindex-{run_id}` từ watermark, ghi
   **trực tiếp** vào `products_v{N+1}` chứ không qua write alias. Guard giữ nguyên
   như luồng thường. Kết thúc khi lag bằng 0 qua nhiều chu kỳ poll liên tiếp và
   không phát sinh lỗi mới trong một cửa sổ quan sát.
3. **Cutover.** Kiểm tra chênh lệch số document trong ngưỡng cho phép, sample diff
   trên tập SKU nóng, smoke test các truy vấn phổ biến, rồi chuyển cả read alias
   và write alias sang index mới bằng **một** lệnh `_aliases` atomic.

**State/data changes.** Index mới được tạo; alias trỏ sang index mới; index cũ giữ
lại tối thiểu 24 giờ.

**Failure paths.** Build lỗi → resume từ checkpoint hoặc hủy run và xóa index mới;
alias không đổi nên traffic không bị ảnh hưởng. Catch-up không hội tụ → hủy run.
Kiểm tra trước cutover không đạt → hủy run. Lỗi sau cutover → swap alias ngược về
index cũ.

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

**Metrics.** Tiến độ build, lag catch-up, chênh lệch số document, thời lượng từng
pha, kết quả run.

**Test scenarios.** Reindex trên catalog rỗng; reindex khi đang có tải ghi; hủy
giữa build; catch-up không hội tụ; cutover rồi rollback; trigger trùng.

---

## WF-SEARCH-010 — Rebuild suggestion hằng ngày

**Mục tiêu.** Làm mới bộ gợi ý mà không gián đoạn API suggest.

**Trigger.** Lịch chạy hằng ngày vào giờ thấp điểm.

**Happy path.** Tạo `search_suggestions_v{N+1}` → nạp ứng viên từ cây danh mục và
từ sản phẩm đang hiển thị → chuẩn hóa, khử trùng lặp, tính `weight` từ chỉ số
catalog → bulk index theo chunk → **validation gate** (tổng số document đạt ngưỡng,
các prefix nóng đều có kết quả) → swap alias `search_suggestions`.

**Failure paths.** Lỗi trước swap → hủy build, alias cũ giữ nguyên, API không bị
ảnh hưởng. Validation gate không đạt → không swap. Phát hiện chất lượng kém sau
swap → rollback alias về version trước.

**Idempotency.** Mỗi lần chạy tạo index mới; chạy lại trong cùng ngày chỉ tạo một
version khác và không làm hỏng dữ liệu đang phục vụ.

**Concurrency.** Singleton; hai lần build song song bị chặn bằng lock.

**Metrics.** Số document, thời lượng build, kết quả validation gate, số version giữ
lại.

**Test scenarios.** Catalog rỗng; ngưỡng validation không đạt; swap thành công;
rollback; sản phẩm đã xóa không còn xuất hiện ở lần build kế tiếp.

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
