---
doc_id: search-adr-022
service: search
status: accepted
date: 2026-10-10
decision_owners:
  - Search Service team
supersedes: ADR-021-tombstone-protection-window-35d
clarifies: ADR-005-no-dead-letter-queue, ADR-019-inventory-owned-sku-availability
extends: ADR-006-three-phase-reindex, ADR-013-product-category-snapshots, ADR-017-product-catalog-snapshot-offset-contract
evidence:
  - note/audit-2026-10-07-docs-code-sync.md
  - contracts/http/search/v2/openapi.yaml
  - java/services/search-service/src/main/java/com/ecommerce/search/worker/job/FullReindexJob.java
  - java/services/search-service/src/test/java/com/ecommerce/search/integration/ReindexLifecycleIT.java
  - java/services/search-service/src/test/java/com/ecommerce/search/integration/ConsumerRedeliveryIT.java
---

# ADR-022 — Sửa sau kiểm chứng audit: redelivery, lỗi HTTP và vòng đời reindex

## Status

`Accepted`. Thay con số của [ADR-021](./ADR-021-tombstone-protection-window-35d.md) (35 ngày → 45 ngày; quy tắc
của [ADR-011](./ADR-011-tombstone-and-purge-window.md) giữ nguyên), làm rõ cơ chế retry mà
[ADR-005](./ADR-005-no-dead-letter-queue.md) và [ADR-019](./ADR-019-inventory-owned-sku-availability.md) đã hứa, và mở
rộng ba pha của [ADR-006](./ADR-006-three-phase-reindex.md) bằng vòng đời vận hành (lease, resume, hủy, rollback, kiểm
tra trước cutover, dọn index) cùng cách xử lý control event của [ADR-013](./ADR-013-product-category-snapshots.md) và
[ADR-017](./ADR-017-product-catalog-snapshot-offset-contract.md).

## Context

Mục "Search" của audit 2026-10-07 (S1–S7) được kiểm chứng lại ngày 2026-10-10 bằng cách đọc toàn bộ docs, contract và
code rồi chạy test. Bảy mục gốc đúng (S4, S6 đã sửa từ 2026-10-09; S5 đã cũ vì nhãn nhóm facet đã đọc
`facet_definitions`). Báo cáo bỏ sót các điểm sau, mỗi điểm đều có test đỏ trước khi sửa hoặc dẫn chứng trực tiếp trong
code (mã `S8`–`S27` là mã trong báo cáo audit):

| Mã | Phát hiện |
|---|---|
| S8 | Batch không ACK **không bao giờ được Kafka gửi lại**. `AckMode.MANUAL` không gọi `nack`; container tiếp tục poll, batch sau thành công commit offset vượt qua batch lỗi. Mọi câu "không commit offset để Kafka gửi lại" (ADR-005, ADR-019, operations §6) đều sai. Hệ quả cụ thể: Inventory readiness lỗi → SKU không bao giờ được đăng ký → không có availability. `ConsumerRedeliveryIT` đỏ. |
| S9 | `@ExceptionHandler(Exception.class)` nuốt lỗi giao thức của Spring: body hỏng, thiếu body, sai Content-Type, route lạ, sai method đều ra `500 INTERNAL_ERROR`. `trace_id` chỉ có khi client gửi `X-Request-Id`, và lọt qua schema Problem khi id ngắn hơn 16 ký tự. `HttpErrorMappingIT`. |
| S10 | Run bị `SUPERSEDED` được ghi đè thành `FAILED` ngay sau đó (`SupersededException` bị `catch (Exception)` chung bắt). |
| S11 | `ProductSearchRepository.aggregate`, `CategoryRepository` và `SuggestionRepository` không kiểm `timed_out`/shard lỗi: facet và job `product_count` ghi số đếm thiếu như thể đầy đủ, ngược với WF-SEARCH-004/011. |
| S12 | Gauge Micrometer đăng ký bằng `registry.gauge(name, number)` giữ weak reference tới số đã box, nên trả `NaN` sau lần GC đầu (`search_reindex_state`, `*_snapshot_age_*`, `search_es_bulk_in_flight`). `search_reindex_state` cũng không bao giờ về 0. |
| S13 | `catalog-reindex-requested` chỉ được nhớ trong bộ nhớ; không có code nào khởi chạy reindex. ADR-013/017 và docs Product đều giả định Search tự chạy. |
| S14 | Catch-up bỏ qua kết quả `BatchExecutor.execute()`/`ReviewEventHandler.handle()`: lỗi tạm thời của ES làm mất event mà run vẫn cutover. Ngoài ra live indexer vẫn ghi vào index cũ giữa lúc catch-up hội tụ và lúc chuyển alias; stretch đó không bao giờ tới index mới. |
| S15 | Run bị crash giữa chừng giữ slot singleton vĩnh viễn (`REINDEX_ALREADY_RUNNING` mãi mãi); trigger kiểm "có run chưa kết thúc" bằng đọc-rồi-ghi, không atomic. |
| S16 | Docs hứa mà code không có: resume theo checkpoint, sample diff và smoke test trước cutover, kiểm tra cluster/dung lượng khi trigger, dọn index cũ sau 24 giờ, trạng thái `ROLLED_BACK`, hủy run, "lịch bảo trì". |
| S17 | `ReindexRunDocument.isTerminal()` bị Jackson serialize thành field `terminal`; `search_jobs` là `dynamic: strict` nên **mọi lần ghi một run đều lỗi** (`strict_dynamic_mapping_exception`). Chưa test nào ghi run vào ES thật, nên chưa ai thấy. |
| S18 | OpenAPI khai `q ≤ 256`, `category ≤ 180`, `filter ≤ 50 phần tử × 256`, `cursor ≤ 16384` nhưng code không kiểm; token `filter` sai định dạng bị bỏ qua và trả `200`; Tomcat mặc định 8KB không nhận nổi một request hợp lệ cỡ lớn. |
| S19 | Profile `dev` (compose gốc dùng) tắt cả ba job nên gợi ý và `product_count` ở local luôn trống/0; docs không nói. |
| S20 | Metric `kafka_consumer_lag` trong operations §4 không tồn tại trong code. |
| S21 | Mã lỗi 401 của Search là `UNAUTHORIZED`, có trong code và OpenAPI nhưng không có trong bảng lỗi docs, và Search không nằm trong danh sách X3 (tên mã 401 chưa thống nhất giữa service). |
| S22 | Cửa sổ 35 ngày của ADR-021 không tính độ trễ xóa theo segment của Kafka: record sống thêm tới `segment.ms` (mặc định 7 ngày) sau `retention.ms`. |
| S23 | Hành vi có thật nhưng chưa được viết: `category` không tồn tại ở search là `400`, không phải tìm toàn catalog. |
| S24 | Cutover chuyển alias `products` và `categories` bằng hai lệnh `_aliases` riêng; docs chỉ nói "một lệnh atomic". |
| S25 | Docs liệt kê "Test bắt buộc" không tồn tại (đường listener, rebuild suggestion, recompute count, full reindex). |
| S26 | `contracts/events/product/v1/asyncapi.yaml` khai `consumer-managed-retry-with-dlq` + DLQ cho các topic Product mà Search không dùng DLQ; không có câu nói mỗi consumer chọn chính sách của mình (Review có). |
| S27 | `interactions.yaml`/ADOPTION/OpenAPI còn `proposed` và "một hit là một SKU", frontend được ghi là còn gọi v1. |

## Decision

### 1. Redelivery bằng `nack` (S8, S20)

Listener không còn "chỉ không ACK". Khi một batch không áp dụng được (lỗi tạm thời của ES, `429`/`503`, Inventory
readiness lỗi, vi phạm contract readiness `document_missing`) worker gọi `Acknowledgment.nack(index, pause)`: commit các
record trước `index`, seek về record đầu tiên chưa xử lý và giao lại sau `search.consumer.retry-backoff` (5s). Mọi ghi đều
có guard revision nên áp dụng lại là no-op. Với rating, `index` là record lỗi; với catalog/category/availability là `0`
vì cả batch đi trong một `_bulk`. Control event cũng `nack` khi không lưu được request.

Chính sách của ADR-005 giữ nguyên: lỗi không tự hồi phục vẫn log, đếm metric, bỏ qua và ACK; không DLQ. Điều thay đổi là
cơ chế: giờ "không ACK" thật sự có nghĩa "thử lại". Hệ quả có chủ đích: một record không bao giờ áp dụng được (ví dụ
availability cho SKU không có document) giữ partition đó lại cho đến khi owner sửa — đúng điều ADR-019 đã yêu cầu ("page cả
hai owner"). `DefaultErrorHandler(FixedBackOff(1s, 3))` chỉ còn xử lý exception thoát khỏi listener, tức bug của handler.

Lag lấy từ metric chuẩn của Kafka client (`MicrometerConsumerListener`), không có metric tự đặt: Prometheus
`kafka_consumer_fetch_manager_records_lag_max`, và `kafka_consumer_fetch_manager_records_lag{topic,partition}` khi
partition đã được fetch.

### 2. Lỗi HTTP (S9, S11, S21, S23)

- `ProblemExceptionHandler` kế thừa `ResponseEntityExceptionHandler`: lỗi giao thức của Spring giữ đúng status
  (`400` body hỏng/thiếu/sai kiểu tham số, `404` route lạ, `405` kèm `Allow`, `406`, `415`) và body vẫn là
  `application/problem+json` với mã `INVALID_QUERY_PARAMETER`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`,
  `UNSUPPORTED_MEDIA_TYPE`. Chi tiết parser không lộ ra ngoài.
- `RequestIdFilter` cấp một request id hiệu lực: giữ id client gửi nếu là 16..64 ký tự `[A-Za-z0-9._:-]` (giá trị hợp lệ
  của `trace_id`), nếu không thì sinh UUID. Id này được echo ở header `X-Request-Id`, vào MDC và là `trace_id` của **mọi**
  lỗi.
- `ElasticsearchException` map theo status: `429/502/503` → `ELASTICSEARCH_UNAVAILABLE`, `408/504` → `SEARCH_TIMEOUT`,
  còn lại `INTERNAL_ERROR`.
- Một câu trả lời `timed_out` hoặc có shard lỗi của ES **không bao giờ** được dùng: facet danh mục, cây danh mục, gợi ý và
  job `product_count` coi đó là `SEARCH_TIMEOUT` (job giữ số cũ).
- Search giữ `UNAUTHORIZED` cho `401`; mã này được ghi vào bảng lỗi và Search được thêm vào mục X3 của audit để chốt tên
  chung một lần cho mọi service.
- `category` không khớp category nào đang hiển thị là `400 INVALID_QUERY_PARAMETER` (field `category`), được ghi vào
  OpenAPI và docs.

### 3. Ràng buộc tham số (S18)

Code kiểm đúng các giới hạn OpenAPI và trả `400` kèm `errors[]` (mọi vi phạm cùng lúc): `q ≤ 256`, `category ≤ 180`,
`filter` tối đa 50 lần, mỗi token 1..256 ký tự và phải decode được thành `code:type:value`; `cursor > 16384` là
`INVALID_CURSOR`. Giá trị rỗng/chỉ khoảng trắng của `q`, `category`, `cursor` vẫn được coi như không gửi (form trình
duyệt gửi `q=`), và OpenAPI nói rõ. `server.max-http-request-header-size` là 40KB để một request hợp lệ ở cận trên
không bị Tomcat cắt trước khi tới controller.

### 4. Vòng đời reindex (S10, S13–S17, S24)

**Singleton bằng lease.** Một run giữ lease `full-reindex` trong `search_jobs` (tạo atomic, fencing token đơn điệu).
Trigger lấy lease; không lấy được là `REINDEX_ALREADY_RUNNING`. Run gia hạn lease mỗi `lease-renew-interval` (30s,
`lease-duration` 2m) từ một heartbeat thread riêng; mất lease thì run dừng mà không ghi đè trạng thái của owner mới.

**Stage và checkpoint.** Run là chuỗi stage `CREATE_INDICES → BUILD_CATEGORIES → BUILD_CATALOG → BUILD_AVAILABILITY →
BUILD_RATING → CATCH_UP → VERIFY → CUTOVER → DONE`. Stage hiện tại, hai index đích, cursor của từng nguồn, session
Inventory/Review, các SKU bị defer và offsets nằm trong document run (`checkpoint` là object không index nên mapping
`search_jobs` không đổi). Cursor chỉ được lưu **sau khi** trang đã vào index. Status ngoài API vẫn là
`STARTED/BUILDING/CATCHING_UP/CUTOVER/COMPLETED`.

**Resume.** `ReindexWatchdog` chạy trên mọi replica; lease hết hạn mà run chưa kết thúc nghĩa là owner đã chết. Replica
giành được lease (atomic) resume từ stage đã lưu, tối đa `max-resume-attempts` (3) lần; hết lượt thì `FAILED` với
`RUN_ABANDONED`, xóa index đang dựng và giải phóng slot. Trigger xử lý run bị bỏ rơi trước khi nhận run mới, nên
không bị chặn vĩnh viễn. Cursor export của Product là stateless nên resume giữa chừng cho đúng `replay_from_offsets`; session
Inventory/Review hết hạn thì rơi về quy tắc "session mới, trang đầu" đã có.

**Hủy.** `POST /reindex-runs/{run_id}/cancellation` ghi một document `cancel:<run_id>` riêng (run document bị job ghi đè ở
mỗi checkpoint nên không thể mang cờ). Run kiểm cờ ở mỗi trang trong Build/Catch-up và kết thúc `CANCELLED`
(`failure.code = RUN_CANCELLED`), xóa index đã dựng. Run không còn owner được hủy ngay trong request. Run đã kết thúc hoặc đang
`CUTOVER` trả `409 REINDEX_RUN_NOT_CANCELLABLE`. Trạng thái `CANCELLED` là giá trị mới của enum.

**Precondition.** Trigger từ chối `409 REINDEX_PRECONDITION_FAILED` (retryable) khi cluster `red` hoặc free disk thấp hơn
`min-free-disk-ratio` (1.5) lần kích thước primary store của index hiện hành. `yellow` được chấp nhận vì cluster một node
luôn `yellow` và vẫn phục vụ; vì thế WF-SEARCH-009 không còn ghi "green". Cluster có replica cần nâng ratio để phủ cả replica.

**Catch-up không nuốt lỗi.** Batch mà index không nhận được được seek lại và áp dụng lại với backoff; quá `bulk.max-retries`
thì run `FAILED` thay vì cutover thiếu event.

**Kiểm tra trước cutover (stage VERIFY, status `CUTOVER`).** (a) số document lệch không quá
`max-doc-count-drift-ratio`; (b) sample diff: `2 × sample-size-per-bucket` (200) SKU lấy từ index hiện hành — 100 có
`updated_at` mới nhất, 100 có `product_rating_count` lớn nhất — fetch sang index mới; lỗi nếu thiếu (trừ tombstone), nếu
một trong ba revision guard thấp hơn index hiện hành, hoặc nếu cùng `catalog_revision` mà giá/trạng thái khác; SKU live indexer
đã đổi sau khi catch-up hội tụ không tính (tail replay lo phần đó); (c) smoke: truy vấn hiển thị mặc định và aggregation
facet chạy được trên index mới, và mỗi `hot-queries` cấu hình có hit nếu index hiện hành có.

**Cutover atomic.** Bốn alias (`products`, `products_write`, `categories`, `categories_write`) chuyển bằng **một** lệnh
`_aliases`. Hai index bị thay được ghi vào checkpoint **trước** khi swap. Trước swap run kiểm lease còn là của mình và kiểm
supersede lần cuối.

**Tail replay.** Live indexer ghi vào index cũ cho tới khi alias đổi, nên những gì nó áp dụng giữa lúc catch-up hội tụ và lúc
swap không có trong index mới. Sau swap, run đọc lại từ vị trí cuối của catch-up (offsets mỗi partition + change feed
Inventory) vào index mới; guard biến phần trùng thành no-op. Tail không đọc hết thì run vẫn `COMPLETED` và ghi
`checkpoint.tail_replay = INCOMPLETE`; document tự lành ở event kế tiếp hoặc reindex sau.

**Rollback.** `POST /reindex-runs/{run_id}/rollback` (đồng bộ, `200`) trả bốn alias về hai index đã bị thay trong một lệnh,
đặt `ROLLED_BACK`, chỉ khi run là `COMPLETED`, là lần cutover mới nhất và index trước đó còn tồn tại. Nó giữ lease để không
chạy cùng run khác. Dữ liệu ghi vào index mới sau cutover không có trong index cũ; vận hành chạy reindex mới.

**Control event.** `catalog-reindex-requested` được lưu bền vào document `reindex-request:pending` (gộp theo root category,
revision cao nhất thắng, `updated_at` là event mới nhất) **trước khi** ACK. `ReindexAutoStarter` tạo run (actor
`system:catalog-control`) khi request yên lặng `control-debounce` (2m) và không có run đang chạy; sau một lần tạo run, lần tự
động kế tiếp đợi 5 × debounce để run hỏng không dội vào Product/Inventory/Review. Một request đến **sau** khi run bắt đầu
làm run `SUPERSEDED` (xóa index đang dựng, giữ alias) và request vẫn nằm đó để run kế tiếp xử lý; run `COMPLETED` xóa mọi
request đến trước lúc nó bắt đầu. Hết dòng "lịch bảo trì" trong docs.

**Liệt kê.** `GET /reindex-runs?status=&limit=` (mới nhất trước) để admin tìm run tự khởi chạy. `ReindexRun` có thêm `reason`
và `actor`.

**Dọn index.** `IndexCleanupJob` (cron hằng giờ) xóa index không còn alias phục vụ sau `old-index-retention` (24 giờ) kể từ lúc nó
ngừng là index hiện hành — thời điểm này nằm trong document `retired:<index>` do cutover/swap ghi, vì ngày tạo index
là lúc build bắt đầu, nhiều giờ trước cutover. Index chưa có marker (do phiên bản cũ hoặc làm tay) nhận marker lúc job thấy nó.
`products`/`categories`: xóa mọi version không phục vụ sau retention. `search_suggestions`: version retired mới nhất là
rollback version và luôn được giữ; các version cũ hơn xóa sau retention. Không bao giờ đụng index của run chưa kết thúc,
index đang có alias, hay index mới thấy lần đầu (kể cả làm rollback version).

Rollback của **suggestion** không có API: đó là một lệnh `_aliases` thủ công theo runbook và build vẫn ở `SWAPPED`.
`ROLLED_BACK` trong state machine suggestion chỉ là mô tả runbook, không phải trạng thái code đặt.

### 5. Cửa sổ tombstone 45 ngày (S22)

`search.tombstone.protection-window` = 45 ngày = 30 ngày (retention của `t.inventory.stock.events.v1`) + 7 ngày
(`segment.ms` mặc định: Kafka chỉ xóa cả segment, và một segment đủ điều kiện khi record mới nhất của nó già hơn
`retention.ms`) + 8 ngày biên cho lệch đồng hồ, backlog consumer và lịch purge hằng ngày.
`TombstoneProtectionWindowTest` đòi `≥ retention + segment.ms + 5 ngày`. Thay đổi này chỉ nằm trong Search; không buộc service
khác provision topic theo `segment.ms` cụ thể.

### 6. Cấu hình (S7, S19)

- `search.media.cdn-base-url` mặc định `https://cdn.local` và service **không khởi động** nếu giá trị không phải URL
  http(s) tuyệt đối hoặc có `/` cuối, như Product Detail BFF và Checkout, để ba service luôn dùng cùng một giá trị. Không
  yêu cầu https vì local phát media qua `http://localhost:8088/media`.
- Profile `dev` bật lại `product_count` (mỗi 5 phút) và rebuild suggestion (mỗi 15 phút); purge vẫn tắt vì đó là job duy
  nhất xóa dữ liệu không hoàn tác.

### 7. Trạng thái contract (S1, S2, S27)

Search HTTP v2 là `implemented` theo định nghĩa của `contracts/ADOPTION.md` (provider và frontend đã dùng contract; chưa có
contract test chéo nên chưa `verified`). Tất cả nhãn `proposed`, "một hit là một SKU" và "frontend còn gọi v1" được sửa.
OpenAPI lên `2.2.0`.

## Alternatives considered

- **Ném exception để `DefaultErrorHandler` retry.** Retry tại chỗ với backoff cố định nhưng hết lượt thì mặc định bỏ qua batch —
  đúng điều ADR-005 cấm. `nack` giữ ngữ nghĩa "thử lại cho tới khi áp dụng được".
- **Pause container thay vì `nack`.** Phức tạp hơn và vẫn phải seek lại.
- **Trigger reindex tự động ngay khi có control event.** Một chuỗi sửa category sẽ chạy nhiều run. Debounce gộp chúng.
- **Lưu request control trong bộ nhớ.** Mất khi restart giữa lúc ACK và lúc run bắt đầu.
- **Cờ hủy trong run document.** Bị job ghi đè ở checkpoint kế tiếp.
- **Giữ cutover bằng hai lệnh alias.** Giữa hai lệnh, `products` và `categories` thuộc hai thế hệ khác nhau.
- **Giữ 35 ngày và yêu cầu `segment.ms ≤ 1d` ở Inventory.** Phụ thuộc cấu hình ngoài Search mà hiện chưa có chỗ provision tường minh.
- **Rollback tự động khi VERIFY lỗi.** VERIFY chạy trước swap nên không có gì để rollback; run chỉ `FAILED` và giữ alias.

## Consequences

Tích cực: retry hoạt động đúng như tài liệu; lỗi HTTP có status và `trace_id` đúng; run không còn kẹt vĩnh viễn, có
thể hủy, resume và rollback; cutover không để lại khoảng mất event; một thay đổi cây category tự dẫn tới reindex; index cũ
được dọn có kiểm soát; reindex lần đầu thật sự ghi được run vào `search_jobs`.

Tiêu cực: một record không thể áp dụng giữ partition của nó (đúng thiết kế ADR-019, nhưng giờ thật); thêm trạng thái trong
`search_jobs` (lease, request, marker, cancel) và thêm ba job/watchdog chạy trên mọi replica; reindex tốn thêm một lần đọc
tail và thêm bước VERIFY; `GET filter=<rác>` đổi từ `200` sang `400`; response `ReindexRun` có thêm hai field bắt buộc.

## Migration impact

- Contract: OpenAPI `2.2.0` thêm `listReindexRuns`, `cancelReindexRun`, `rollbackReindexRun`, status `CANCELLED`, field
  `reason`/`actor`, mã `REINDEX_PRECONDITION_FAILED`/`REINDEX_RUN_NOT_CANCELLABLE`/`REINDEX_ROLLBACK_UNAVAILABLE`, `504`
  cho cây danh mục. Thêm field vào response và thêm giá trị enum là tương thích với consumer bỏ qua giá trị lạ; consumer
  sinh type cần sinh lại (`frontend/src/lib/api/generated/search.ts`). Client gửi token `filter` không do Search sinh ra sẽ nhận `400`.
- Dữ liệu: không đổi mapping nào. Tombstone cũ tự được giữ thêm 10 ngày vì cutoff tính theo `now - protection-window`.
- Gateway: không đổi (router reindex đã bắt `PathPrefix` với `GET` và `POST`).
- Rollback của chính ADR này: tắt `search.lifecycle.control-auto-start` và `search.lifecycle.cleanup-enabled`; các stage và
  endpoint mới không ảnh hưởng đường đọc.

## Verification

`ConsumerRedeliveryIT` (batch bị `nack` được giao lại và áp dụng khi document xuất hiện; lag có trong metric Kafka),
`IndexingConsumerTest` (`nack(index)` đúng vị trí), `HttpErrorMappingIT` (mọi lỗi giao thức, `trace_id`, giới hạn tham số,
quyền admin), `ProductSearchRepositoryAggregateTest` (`timed_out`), `SearchMetricsGaugeTest` (gauge sau GC),
`SearchJobsDocumentsMatchMappingTest` (mọi document ghi vào `search_jobs` khớp mapping strict), `FullReindexJobTest` (SUPERSEDED,
CANCELLED, mất lease, cutover atomic, verify lỗi, resume), `ReindexProgressStateTest` (checkpoint qua JSON),
`ReindexRecoveryTest`, `ReindexRunServiceTest`, `ReindexPreconditionsTest`, `ReindexAutoStarterTest`,
`ReindexControlCoordinatorTest`, `ReindexVerifierTest`, `SearchPropertiesMediaTest`, `TombstoneProtectionWindowTest` và
`ReindexLifecycleIT` (toàn pipeline trên ES + Kafka thật với Product/Inventory/Review giả lập: cutover → rollback, hủy, resume,
bỏ rơi, trigger không bị run chết chặn, verify từ chối index thụt lùi, control event → một run, dọn index).
