---
doc_id: search-interfaces
service: search
scope: current
status: implemented
owners:
  - Search Service team
evidence:
  - contracts/http/search/v2/openapi.yaml
  - contracts/events/product/v1/asyncapi.yaml
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/http/inventory/exports/v1/openapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
  - contracts/http/product/v1/openapi.yaml
  - java/services/search-service/src/main/java/com/ecommerce/search/controller
---

# Search Service — Giao diện tích hợp

> OpenAPI canonical cho HTTP v2 nằm tại
> [`contracts/http/search/v2/openapi.yaml`](../../contracts/http/search/v2/openapi.yaml).
> File này giải thích semantics, projection boundary và lộ trình tương thích; khi
> mâu thuẫn, OpenAPI là nguồn chuẩn trên wire.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumer | Contract | Status |
|---|---|---|---|---|---|---|
| Search, suggest, browse, facet | Inbound | HTTP | Search | Storefront qua Gateway | [`contracts/http/search/v2/openapi.yaml`](../../contracts/http/search/v2/openapi.yaml) | implemented |
| Full reindex admin (tạo, liệt kê, đọc, hủy, rollback) | Inbound | HTTP | Search | Admin qua Gateway | [`contracts/http/search/v2/openapi.yaml`](../../contracts/http/search/v2/openapi.yaml) | implemented |
| Catalog và tombstone | Inbound | Kafka | Product | Search | [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) | implemented |
| Category và control reindex | Inbound | Kafka | Product | Search | [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) | implemented |
| Tồn kho | Inbound | Kafka | Inventory | Search | [AsyncAPI Inventory v1](../../contracts/events/inventory/v1/asyncapi.yaml) | implemented |
| Rating aggregate | Inbound | Kafka | Review | Search | [AsyncAPI Review v1](../../contracts/events/review/v1/asyncapi.yaml) | implemented |
| Catalog snapshot export + replay offsets | Outbound | HTTP nội bộ | Product | Search | [Product OpenAPI v1](../../contracts/http/product/v1/openapi.yaml), [Product interfaces](../product/interfaces.md) | implemented |
| Availability readiness + snapshot/change export | Outbound | HTTP nội bộ | Inventory | Search | [Inventory Export OpenAPI v1](../../contracts/http/inventory/exports/v1/openapi.yaml) | implemented |
| Rating aggregate snapshot export | Outbound | HTTP nội bộ | Review | Search | [Review internal OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) | implemented |

`implemented` theo [`contracts/ADOPTION.md`](../../contracts/ADOPTION.md): provider và consumer đã chạy theo contract; chưa
có contract test chéo nên chưa `verified`.

Search **không phát event**. Không có mục “event phát ra” trong tài liệu này là
một khẳng định contract, không phải thiếu sót.

## 2. Quy ước chung cho HTTP v2

- Storefront API là `/api/v2/search/...` và anonymous qua Gateway: Gateway local chỉ expose bốn route
  `GET` (`products`, `suggestions`, `categories`, `categories/{id}/facets`) với `strip-identity-headers`
  ([gateway runtime contract](../gateway/runtime-contract.md)). Nó không nhận hay sử dụng identity claim.
  Service không được tin bất kỳ `X-User-*` header nào khi có public ingress bypass Gateway.
- Reindex là admin command qua Gateway, dùng `/api/v2/search/reindex-runs`; nó
  **không** dùng prefix `/internal`, vì `/internal/v1` được dành cho mTLS
  service-to-service như Product export và Inventory availability export. Gateway route
  của các operation admin (`GET`/`POST` trên `/reindex-runs` và `/reindex-runs/**`) có priority cao hơn
  public route, bắt buộc JWT và `ADMIN`.
- Breaking change chạy ở version path mới. V1 đã bị gỡ khỏi Search, Gateway và frontend; không có
  `/api/v1/search/**` và không có giai đoạn chạy song song.
- Mọi lỗi dùng `application/problem+json` theo RFC 9457 và
  [`Problem`](../../contracts/shared/json-schema/v1/problem.schema.json): `code`,
  `trace_id` (luôn có), `retryable` và `errors[]`. Không dùng legacy
  `error_code/message/details/request_id` body. Bảng mã lỗi đầy đủ ở [§7](#7-error-catalog).
- `X-Request-Id` của client được giữ làm `trace_id` và echo ở header response khi dài 16..64 ký tự
  `[A-Za-z0-9._:-]`; giá trị khác (OpenAPI cho phép 8..128 ký tự) bị thay bằng id Search sinh ra, vì `trace_id`
  của schema Problem chỉ nhận 16..64 ký tự.
- Giá trị rỗng hoặc chỉ khoảng trắng của `q`, `category` và `cursor` được coi như không gửi (form trình duyệt gửi `q=`).
- Mọi response là projection eventual-consistent. `is_in_stock` là snapshot
  Inventory, không phải kết quả reserve; `selling_price_vnd` không phải quotation.
- Security headers/CORS là concern của Gateway và deployment configuration; OpenAPI
  không hứa các header này là response business field.

## 3. HTTP API v2

### 3.1 `GET /api/v2/search/products`

Truy vấn cốt lõi: trang kết quả tìm kiếm và mọi trang danh sách sản phẩm.

- **Caller:** buyer qua Gateway; không yêu cầu quyền nghiệp vụ.
- **Tham số:** `q` (≤ 256 ký tự), `category` (id hoặc slug, ≤ 180), `filter` (repeatable opaque token từ facet,
  tối đa 50 lần, mỗi token 1..256 ký tự và phải là giá trị `FacetOption.value`), `price_min`, `price_max`,
  `in_stock` (mặc định `true`), `has_promotion`, `sort`, `cursor` (≤ 16384), `size`
  (mặc định 20, tối đa 100). Vượt giới hạn, token `filter` không có dạng `code:type:value`, hoặc
  `category` không khớp category nào đang hiển thị là `400 INVALID_QUERY_PARAMETER` kèm `errors[]` theo field
  (mọi vi phạm cùng lúc) — token lạ không bị bỏ qua. `cursor` quá dài là `INVALID_CURSOR`.
- **Sort:** relevance (max SKU score), price_asc/price_desc (giá SKU đại diện), newest (max updated_at), rating (max rating), luôn product_id asc khi bằng điểm.
- **Đơn vị kết quả:** một Product duy nhất trên toàn bộ snapshot; sku_id, giá, ảnh, giảm giá và tồn kho thuộc SKU có selling_price_vnd thấp nhất THỎA TẤT CẢ bộ lọc, tie sku_id asc. Lọc màu đen không lấy giá SKU trắng rẻ hơn.
- **Pagination:** signed cursor generation 2 mang PIT + vị trí Product + resolved category id + query chuẩn hóa (trừ size). PIT gia hạn 2 phút mỗi request. Đổi query/filter/sort, token sửa tay/cũ/hết hạn trả INVALID_CURSOR. Trang cuối next_cursor=null và đóng PIT; tải lại trang đầu để nhận dữ liệu mới.
- **Total:** total_products đếm chính xác Product trong snapshot. Thay total_skus là breaking change cho consumer pilot: rollout backend/frontend/types đồng bộ, không dùng cursor cũ.
- **Facet:** DISTINCT_PRODUCT, APPROXIMATE; aggregation trên toàn bộ SKU khớp cùng PIT, không chỉ SKU đại diện hoặc trang đang tải.
- **Lọc bắt buộc:** is_deleted=false, product_status=ACTIVE, sku_status=ACTIVE. in_stock=true mặc định; thiếu stock không khớp true.
- **Giới hạn:** size tối đa 100, toàn bộ grouping 5 giây, mặc định tối đa 50000 Product khớp. Không trả tổng/trang thiếu khi timeout, shard failure hay vượt cap. Không ghi catalog/index, chỉ quản lý context PIT.
- **Triển khai:** composite scan + top_hits trước sort/paging, [ADR-020](./decisions/ADR-020-product-cards-with-cheapest-eligible-sku.md). Product Service không đổi event/export hay ownership.

### 3.2 `GET /api/v2/search/suggestions`

Dropdown search-as-you-type.

- **Tham số:** `q` (bắt buộc, 2..256 ký tự), `limit` (mặc định 8, tối đa 20).
- Đọc index `search_suggestions` đã pre-compute; ranking theo `weight` giảm dần,
  tie-break `text.keyword` tăng dần. Public response chỉ trả `text` và `type`,
  không trả `weight` nội bộ.
- **Ngữ nghĩa chọn gợi ý:** mỗi item là một cụm từ để hoàn tất truy vấn. Khi buyer
  chọn item, client dùng `text` làm `q` cho `GET /api/v2/search/products` (hoặc
  điền nó vào ô tìm kiếm trước khi buyer submit). Item không phải link đến entity:
  API không trả `product_id`, `sku_id`, `category_id` hay slug để điều hướng.
  `type` chỉ là metadata để trình bày/phân loại dropdown, không được dùng để suy
  ra hoặc điều hướng tới Product, SKU hay Category.
- `q` ngắn hơn 2 ký tự hoặc dài hơn 256 ký tự trả `INVALID_QUERY_PARAMETER`.
- Timeout 2s: Elasticsearch quá hạn trả `504 SEARCH_TIMEOUT`, không bao giờ một danh sách thiếu; suggest lỗi không
  được làm hỏng trang tìm kiếm chính.

### 3.3 `GET /api/v2/search/categories`

Trả cây danh mục cho sidebar điều hướng.

- Đọc toàn bộ index `categories`, dựng cây trong bộ nhớ theo `parent_id`/`level`
  và trả node lồng `children`, không trả danh sách phẳng/internal path.
- `product_count` đọc trực tiếp từ document, không aggregate theo request; số này
  được job recompute làm mới nên có thể trễ so với kết quả search. Nó là số DISTINCT
  Product của **cả nhánh**, nên danh mục cha luôn lớn hơn hoặc bằng tổng con.
  `product_count_updated_at` là `null` cho tới khi job chạy lần đầu cho node đó.
- Danh mục `is_deleted = true` không xuất hiện trong cây. Quá hạn (5s) hoặc shard lỗi là `504 SEARCH_TIMEOUT`;
  Elasticsearch không tới được là `503 ELASTICSEARCH_UNAVAILABLE`; không bao giờ một cây thiếu node.

### 3.4 `GET /api/v2/search/categories/{category_id}/facets`

Bộ lọc khả dụng cho một trang danh mục.

- Aggregation trên `products` giới hạn trong nhánh danh mục, cùng bộ filter hiển
  thị mặc định như API search để số đếm không lệch với danh sách.
- Mỗi `terms` aggregation kèm `cardinality` trên `product_id`. Response công khai
  `count_accuracy = APPROXIMATE`; value của option là opaque token được đưa lại
  qua `filter`, không phải chuỗi do client tự ghép.
- **`label` của nhóm lấy từ `facet_definitions`, `label` của option vẫn dẫn xuất.**
  Product công bố descriptor `{code,label,type}` trong category event và export (Product
  [ADR-019](../product/decisions/ADR-019-category-facet-definitions.md)); Search ghi vào
  index `categories` và đặt `label` nhóm bằng tên admin đặt cho attribute, tra theo `code` qua
  danh mục và mọi danh mục con (kể cả khi search không có `category`). Attribute chưa có
  descriptor (snapshot từ producer cũ, hoặc chưa được map) giữ nhãn dẫn xuất từ token
  `code:type:value`. `label` của từng option luôn dẫn xuất — chuỗi đã case-fold, viết hoa chữ
  đầu — vì descriptor không mang option; `value` không đổi. Đổi tên attribute hiển thị sau
  tối đa 60 giây kể từ khi snapshot mới được áp dụng (cache nhãn, không ảnh hưởng số đếm).
- `category_id` không tồn tại trả `CATEGORY_NOT_FOUND`; một `category_id` không phải UUID cũng là
  `CATEGORY_NOT_FOUND`, không phải `400`. Aggregation quá hạn (5s) hoặc shard lỗi là `504 SEARCH_TIMEOUT`, không bao giờ
  một facet có số đếm thiếu.

### 3.5 `POST /api/v2/search/reindex-runs`

Trigger full reindex bất đồng bộ.

- **Auth:** identity và role `ADMIN` do Gateway chuyển tiếp; thiếu identity trả `401 UNAUTHORIZED`, thiếu quyền trả
  `FORBIDDEN`. Actor audit được lấy từ trusted identity, không nhận từ body.
- **Body:** chỉ có lý do trigger (1..500 ký tự). Body hỏng hoặc thiếu là `400`, sai Content-Type là `415`.
- **Run bị bỏ rơi:** trước khi nhận run mới, Search xử lý run chưa kết thúc mà owner đã chết (lease hết hạn):
  resume hoặc đánh dấu `RUN_ABANDONED` ([WF-SEARCH-014](./workflows.md)). Nếu vừa resume một run, trigger này vẫn bị từ chối
  `REINDEX_ALREADY_RUNNING`; slot được giải phóng khi run kết thúc.
- **Precondition:** cluster không `red` và free disk ≥ `min-free-disk-ratio` (1.5) × kích thước primary store của
  index hiện hành, nếu không trả `409 REINDEX_PRECONDITION_FAILED` (retryable). Cluster `yellow` được chấp nhận. Đang có
  run chưa kết thúc thì trả `REINDEX_ALREADY_RUNNING`; slot là lease `full-reindex` nên hai trigger đồng thời chỉ một cái thắng.
- **Hành vi:** trả `202 Accepted` kèm `run_id` và `status_url` ngay lập tức; công
  việc chạy nền theo [WF-SEARCH-009](./workflows.md).
- **Idempotency:** không tự dedupe theo body; ràng buộc “chỉ một run đang chạy”
  là cơ chế chống trigger trùng.
- Search cũng tự gọi thao tác này (actor `system:catalog-control`) khi cây category đổi
  ([§4.3](#43-tcatalogcontrolevents--category-reindex-control)).

### 3.6 `GET /api/v2/search/reindex-runs/{run_id}`

Đọc trạng thái một lần reindex: pha hiện tại, tiến độ build, lag catch-up, lý do và actor, thời
điểm bắt đầu/kết thúc và thông điệp lỗi nếu có (`failure.code`: `REINDEX_FAILED`, `RUN_SUPERSEDED`, `RUN_CANCELLED`,
`RUN_ABANDONED`). Trả `REINDEX_RUN_NOT_FOUND` khi `run_id` không tồn tại (kể cả khi không phải UUID).

### 3.7 `GET /api/v2/search/reindex-runs`

Liệt kê các run, mới nhất trước (`started_at` giảm dần); `status` lọc theo một trạng thái, `limit` 1..100 (mặc định 20).
Cần để tìm run do control event tự khởi chạy. Admin only.

### 3.8 `POST /api/v2/search/reindex-runs/{run_id}/cancellation`

Yêu cầu hủy một run. Run đang Build hoặc Catch-up dừng ở trang kế tiếp (tối đa vài giây) và kết thúc `CANCELLED`
(`failure.code = RUN_CANCELLED`), các index nó dựng bị xóa, alias không đổi. Response `202` mang trạng thái hiện tại;
client đọc lại run (§3.6) để thấy `CANCELLED`. Run không còn owner được hủy ngay trong request. Run đã kết thúc hoặc
đang `CUTOVER` (kiểm tra và chuyển alias) không hủy được: `409 REINDEX_RUN_NOT_CANCELLABLE`.

### 3.9 `POST /api/v2/search/reindex-runs/{run_id}/rollback`

Trả bốn alias về hai index mà một run `COMPLETED` đã thay, trong một lệnh `_aliases` atomic, và đặt run `ROLLED_BACK`.
Đồng bộ nên trả `200` kèm run. Chỉ cutover mới nhất được rollback, chỉ khi index trước đó chưa bị job dọn xóa (giữ tối thiểu
24 giờ sau cutover) và không có run khác đang chạy; ngược lại `409 REINDEX_ROLLBACK_UNAVAILABLE` (lý do trong `detail`).
Dữ liệu ghi vào index mới sau cutover không có trong index cũ: chạy reindex mới để hội tụ. Rollback của suggestion
không có API ([Operations §10](./operations.md#10-runbooks)).

### 3.10 Health

`GET /actuator/health/liveness` phản ánh tiến trình còn sống.
`GET /actuator/health/readiness` chỉ `UP` khi kết nối Elasticsearch dùng được và
consumer đã subscribe. Chi tiết ở [Operations §2](./operations.md#2-health-endpoints).
Đây là operational endpoint, không thuộc Storefront OpenAPI.

## 4. Events tiêu thụ

Năm topic, một consumer group chính `search-indexer`. Group tạm
`search-reindex-{run_id}` chỉ tồn tại trong pha catch-up của một lần reindex và
replay `t.product.events`, `t.category.events` và `t.review.events` từ replay offset do
chính owner cấp. Inventory không có offset tương ứng nên đi từ change-feed checkpoint rồi
mới subscribe live `t.inventory.stock.events.v1`. `t.catalog.control.events` tiếp tục
được theo dõi để supersede run khi category tree đổi.

Quy tắc chung cho mọi topic:

- Event có `type` ngoài allowlist thì ignore, ACK và ghi metric
  `search_event_ignored_total{topic,event_type}`. Search chia sẻ topic với consumer
  khác nên đây là hành vi bình thường, không phải lỗi.
- Field lạ trong payload được bỏ qua ở tầng deserialize; field bắt buộc thiếu là
  contract violation, ghi metric và bỏ qua event.
- Không có DLQ ([ADR-005](./decisions/ADR-005-no-dead-letter-queue.md)): lỗi tạm
  thời thì không commit offset và trả batch lại cho Kafka bằng `nack` (giao lại sau
  `search.consumer.retry-backoff`, 5s) — chỉ "không ACK" thì không đủ, xem
  [ADR-022](./decisions/ADR-022-post-implementation-audit-corrections.md); lỗi không tự hồi phục thì ghi log,
  ghi metric, bỏ qua và ACK.
- Có một lớp lỗi thứ ba: vi phạm contract readiness của Inventory, tức
  `document_missing_exception` khi apply availability. Nó **không** phải lỗi không tự
  hồi phục — Search không ACK, `nack` batch (nên nó được giao lại cho đến khi document
  tồn tại), không upsert và page cả hai owner (§4.4,
  [ADR-019](./decisions/ADR-019-inventory-owned-sku-availability.md)). Hệ quả có chủ đích: partition đó đứng lại
  cho tới khi owner sửa.
- Offset chỉ commit khi mọi record trong batch đã apply hoặc đã được phân loại là
  lỗi không tự hồi phục.

### 4.1 `t.product.events` — catalog

Producer: Product, qua outbox và Debezium, at-least-once. Contract đích là
CloudEvents 1.0 trong `contracts/events/product/v1`; Kafka key `product_id`,
`id` là event id, `sequence = catalog_revision`, snapshot nằm trong
`data.product` và `data.sku`. `data.sku.thumbnail_storage_key` luôn hiện diện: Product
đã resolve ảnh SKU trước, rồi ảnh Product; `null` nghĩa là Search phải trả placeholder.

| Event | Hành động của Search | Ghi chú |
|---|---|---|
| `com.ecommerce.catalog.sku-upserted.v1` | Upsert đúng một SKU snapshot rồi đăng ký Inventory readiness | `data.product` + `data.sku`; không chạm availability/rating |
| `com.ecommerce.catalog.sku-deleted.v1` | Tombstone đúng một SKU | Không tạo document mới |
| `com.ecommerce.catalog.product-deleted.v1` | Tombstone toàn bộ `data.sku_ids` | Không tạo document mới |

Idempotency và ordering: guard `catalog_revision` (xem [Data §5](./data.md#5-ordering-guard)).
Duplicate và event cũ bị bỏ qua trong script, không cần dedupe store.

### 4.2 `t.category.events` — danh mục

Producer: Product. Key `category_id`; contract đích là CloudEvents Category v1 với
`sequence = category_revision`. `category-upserted` mang `path` và `is_leaf` đã
được Product dẫn xuất; Search materialize `ancestor_ids`, `ancestor_slugs`, level
và node category. `category-deleted` tombstone node. Search không `_update_by_query`
theo path và không tự dựng lại tree ([ADR-013](./decisions/ADR-013-product-category-snapshots.md)).

### 4.3 `t.catalog.control.events` — category reindex control

Product phát `com.ecommerce.catalog.reindex-requested.v1` khi rename, đổi slug hoặc
đổi parent category. Search **lưu bền** request vào `search_jobs` (một document, gộp theo `root_category_id` với
`category_revision` cao nhất) trước khi ACK; nếu không lưu được thì `nack`. Request yên lặng
`search.lifecycle.control-debounce` (2 phút) mà không có run đang chạy thì Search tự khởi chạy một full reindex
(export category trước Product, rồi Catch-up) với actor `system:catalog-control`; event control không cập nhật trực
tiếp SKU document. Một control event đến **sau** khi một run bắt đầu (kiểm trước Catch-up, trước VERIFY và ngay trước
khi swap) làm run đó `SUPERSEDED`: index của nó bị xóa, alias giữ nguyên, request vẫn nằm đó để run kế tiếp xử lý.
Một run `COMPLETED` xóa mọi request đến trước lúc nó bắt đầu. Sau mỗi lần tự khởi chạy, lần tự động tiếp theo
đợi 5 × debounce để một run hỏng liên tục không dội vào Product, Inventory và Review.

### 4.4 `t.inventory.stock.events.v1` — tồn kho

Contract đích là [Inventory AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml).
Search chỉ nhận `com.ecommerce.inventory.sku-availability.changed.v1`, key `sku_id`.
Payload là snapshot đầy đủ gồm `sku_id`, `is_in_stock`, `availability_revision` và
`observed_at`;
`aggregaterevision` bằng revision đó. Inventory sở hữu việc fan-out warehouse change,
đọc mọi stock position và tính boolean. Search không lưu warehouse/position, không có
reverse lookup và chỉ copy boolean vào document bằng guard `availability_revision`.

Event này **không được tạo document**. Search gọi readiness API sau khi catalog upsert
đã tạo document; Inventory bắt buộc không publish hoặc đưa SKU vào export trước lần
đăng ký đó và phải phát snapshot hiện hành khi nhận đăng ký. Vì vậy document chưa tồn
tại lúc apply là contract violation: Search ghi metric, không ACK/commit offset và không
upsert. Document tombstone vẫn chặn event trễ. Inventory không gửi `product_id`; identity
chung là opaque `sku_id` do Product sở hữu
([`SEARCH-INV-005`](./README.md#7-critical-invariants)).

### 4.5 `t.review.events` — rating

Producer: Review. Key `product_id`. Search chỉ xử lý
`com.ecommerce.review.metrics-updated.v1`; event mang `product_id`, điểm trung
bình, tổng số review, `review_revision` và `observed_at`. Áp dụng bằng
`_update_by_query` theo `product_id` với `conflicts=proceed` và throttle, guard
`review_revision`. Không upsert. `t.review.events` cũng có lifecycle fact và hai
Notification request; chúng là message hợp lệ của topic nhưng không thuộc Search.
Search phải dispatch theo exact CloudEvents type, ACK chúng mà không persist hoặc log
payload, đặc biệt không đọc `recipient_user_id` của Notification request.

## 5. Contract nội bộ Search gọi ra

Mọi lời gọi tới `/internal/v1/**` của Product và `/exports/inventory/v1/**` của Inventory
gửi `X-Service-Identity: search-service` theo
[mesh runtime contract §6](../mesh/runtime-contract.md#6-quy-ước-identity-header); hai
service này kiểm allowlist header như defense-in-depth và trả `401` khi thiếu
([Inventory ADR-027](../inventory/decisions/ADR-027-pre-implementation-contract-corrections.md)).

Luồng thường gọi `PUT /exports/inventory/v1/search-projection-readiness/{sku_id}`
sau khi catalog upsert thành công. Khi SKU tombstone, Search gọi `DELETE` readiness
sau khi tombstone đã được ghi và bắt buộc hoàn tất trước physical purge.

Full reindex đọc `GET /internal/v1/categories/export` trước, rồi
`GET /internal/v1/products/export` của Product,
Inventory [snapshot session và change feed](../../contracts/http/inventory/exports/v1/openapi.yaml)
và Review export session — tạo bằng `POST /internal/v1/reviews/rating-aggregate-exports`,
sau đó lấy page qua `GET /internal/v1/reviews/rating-aggregate-exports/{session_id}`
([contract](../../contracts/http/review/internal/v1/openapi.yaml))
để backfill catalog, availability và rating. Product/Review trả Kafka replay boundary của
contract tương ứng; Inventory trả fixed `export_watermark`, sau đó Search đọc mọi change
có `sequence > watermark`. Search không tự đọc Kafka Admin để dựng Inventory watermark.
Row snapshot của Inventory là state hiện tại nên có thể mới hơn watermark; Search chỉ
apply row/change khi `availability_revision` lớn hơn revision đang lưu. Sequence do
sequencer của Inventory gán theo thứ tự commit, nên cursor không bỏ sót fact
([Inventory ADR-022](../inventory/decisions/ADR-022-commit-ordered-export-sequence.md)).

Snapshot session của Inventory sống 24 giờ. Page snapshot có thể trả `410
SNAPSHOT_SESSION_EXPIRED`, còn change feed trả `410 REPLAY_WINDOW_EXPIRED`; hai mã này
đòi hai cách phục hồi khác nhau, mô tả ở [WF-SEARCH-007](./workflows.md). Readiness
`DELETE` trả `409` khi Inventory giữ readiness ở `catalog_revision` mới hơn tombstone —
trạng thái mà guard `is_deleted` của Search lẽ ra đã loại trừ. Xử lý như lệch trạng thái
giữa hai service: không purge, không retry, báo động.

Hai endpoint export của Review kiểm `X-Service-Identity`: Search gửi `search-service` trên mọi lời gọi export
(không còn bearer token; [Search ADR-024](./decisions/ADR-024-drop-review-bearer-and-outbound-metrics.md),
[contracts ADR-004](../../contracts/decisions/ADR-004-service-identity-allowlist.md)), và Review giữ allowlist riêng cho từng nhóm route nội bộ nên identity này chỉ
mở đúng rating export, không đọc được PDP summary. Thiếu identity thì Review trả `401` và rating build dừng lại
thay vì import rỗng.

Không có lời gọi đồng bộ nào khác ra ngoài trong hot path.

## 6. Compatibility

- V1 legacy đã bị gỡ khỏi Search, Gateway và frontend. `/api/v1/search/**` không còn route; không được mô tả v1 là đang chạy.
- V2 không có Product detail/point-read và không hỗ trợ pseudo-filter như
  `NOT product_id`. PDP dùng [Product Detail BFF](../product-detail-bff/README.md):
  BFF gọi Product buyer-read và Inventory Availability Read API. Inventory event/export
  vẫn chỉ đăng ký Search; BFF không có consumer quyền đó và không được suy ra từ quyền
  của Search.
- Thêm field optional vào event là thay đổi tương thích: Search bỏ qua field lạ.
- Xóa field, đổi ngữ nghĩa field, đổi Kafka key hoặc đổi ý nghĩa của
  `catalog_revision` là breaking change và phải phối hợp với Search trước khi
  rollout.
- Thêm `event_type` mới vào topic đang dùng là tương thích: Search ignore và ACK.
- Với HTTP, thêm tham số optional và field response mới là tương thích; đổi hình
  dạng response, đổi mã lỗi hoặc đổi ngữ nghĩa cursor là breaking và cần major
  path mới. Consumer phải migrate trước khi version cũ bị gỡ ở lần thay đổi tiếp theo.
- Cursor không phải contract ổn định giữa các version: client phải coi nó là chuỗi
  đục và không được tự sinh.
- Trước khi một interaction đạt `implemented`: phải có OpenAPI/AsyncAPI tương ứng trong
  `contracts/` và cả provider lẫn consumer đã chạy theo nó. Để đạt `verified` (mức cao hơn,
  [`contracts/ADOPTION.md`](../../contracts/ADOPTION.md)) còn cần contract test chéo giữa Search và producer
  và kiểm tra breaking change trong CI; hai điều đó chưa có ([README §9](./README.md#9-known-gaps)).

## 7. Error catalog

Mọi lỗi là `application/problem+json` với `type`, `title`, `status`, `detail`, `instance`, `code`, `retryable`,
`trace_id` và (khi lỗi theo field) `errors[]` gồm `field` và `message`. `retryable` là gợi ý cho client.

| Code | HTTP | Retryable | Khi nào | Client nên làm gì |
|---|---|---|---|---|
| `INVALID_QUERY_PARAMETER` | 400 | Không | Tham số sai kiểu/range/độ dài/số lượng, `sort` ngoài enum, `price_max < price_min`, token `filter` không phải `FacetOption.value`, `category` không tồn tại, `reason` ngoài 1..500, body hỏng hoặc thiếu, tham số bắt buộc thiếu | Sửa request theo `errors[]` |
| `INVALID_CURSOR` | 400 | Không | Cursor sai chữ ký, đổi query/filter/sort/category, PIT hết hạn (2 phút không hoạt động), dài quá 16384, hoặc ngoài tập kết quả | Tải lại trang đầu |
| `UNAUTHORIZED` | 401 | Không | Admin operation thiếu `X-User-Id` do Gateway inject | Đăng nhập lại qua Gateway |
| `FORBIDDEN` | 403 | Không | Admin operation thiếu role `ADMIN` | — |
| `CATEGORY_NOT_FOUND` | 404 | Không | `category_id` của facet không có trong index (kể cả không phải UUID) | — |
| `REINDEX_RUN_NOT_FOUND` | 404 | Không | `run_id` không tồn tại (kể cả không phải UUID) | — |
| `NOT_FOUND` | 404 | Không | Route không tồn tại (chỉ xảy ra khi bypass Gateway) | — |
| `METHOD_NOT_ALLOWED` | 405 | Không | Sai HTTP method; header `Allow` có sẵn | — |
| `NOT_ACCEPTABLE` | 406 | Không | `Accept` không gồm JSON | — |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Không | Body không phải `application/json` | — |
| `REINDEX_ALREADY_RUNNING` | 409 | Có | Đã có run chưa kết thúc, hoặc vừa resume một run bị bỏ rơi | Đợi run kết thúc, đọc `GET /reindex-runs` |
| `REINDEX_PRECONDITION_FAILED` | 409 | Có | Cluster `red` hoặc thiếu dung lượng đĩa | Đợi cluster hồi phục |
| `REINDEX_RUN_NOT_CANCELLABLE` | 409 | Không | Run đã kết thúc hoặc đang `CUTOVER` | — |
| `REINDEX_ROLLBACK_UNAVAILABLE` | 409 | Không | Run không `COMPLETED`, không thay index nào, không phải cutover mới nhất, index trước đã bị dọn, hoặc có run khác đang chạy | Chạy reindex mới thay vì rollback |
| `ELASTICSEARCH_UNAVAILABLE` | 503 | Có | Elasticsearch không tới được, quá tải (`429`/`502`/`503`), hoặc vượt `max-matching-products` | Thử lại có backoff; ẩn dropdown suggest |
| `SEARCH_TIMEOUT` | 504 | Có | Ngân sách truy vấn hết (5s search/category/facet, 2s suggest), `timed_out`, hoặc shard lỗi | Thử lại; không dùng kết quả một phần |
| `INTERNAL_ERROR` | 500 | Không | Lỗi không lường trước hoặc Elasticsearch từ chối một truy vấn do chính Search sinh | Báo lỗi với `trace_id` |

`ELASTICSEARCH_UNAVAILABLE` (`503`) kèm `Retry-After: 2` (giây) theo [contracts ADR-005](../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md); `SEARCH_TIMEOUT` (`504`) không có header.

`401` dùng `UNAUTHORIZED`, khác `UNAUTHENTICATED` của Cart/Product/Promotion/Inventory/Checkout; tên chung chưa được chốt
(mục X3 của báo cáo audit).
