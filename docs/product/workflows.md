---
doc_id: product-workflows
service: product
scope: current
status: proposed
owners:
  - Product Service team
evidence:
  - docs/product/scripts/inventory_fast_sync.lua
verified_against_commit: null
---

# Product Service — Workflows

> Mỗi workflow phải cho developer biết chính xác điều gì xảy ra khi một bước timeout
> hoặc khi cùng một request/event được xử lý hai lần. Các workflow dưới đây mô tả
> thiết kế hiện hành; bằng chứng implementation chưa được xác minh.

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-PRODUCT-001` | Tạo product bản nháp | API | Admin | proposed |
| `WF-PRODUCT-002` | Cập nhật product và SKU | API | Admin | proposed |
| `WF-PRODUCT-003` | Chuyển trạng thái sản phẩm | API | Admin | proposed |
| `WF-PRODUCT-004` | Xóa mềm product và SKU | API | Admin | proposed |
| `WF-PRODUCT-005` | Vòng đời danh mục và cascade khi xóa | API + Event | Admin, Event Handler | proposed |
| `WF-PRODUCT-006` | Point-read SKU qua gRPC | gRPC | Cart, Order, Checkout, Promotion | proposed |
| `WF-PRODUCT-007` | Áp, cập nhật và gỡ markdown | Event | Markdown Worker | proposed |
| `WF-PRODUCT-008` | Markdown pending gate và expiry self-healing | Schedule | Scheduler, Cronjob | proposed |
| `WF-PRODUCT-009` | Inventory sync Phase 1 (lock-free) | Event | Fast-sync consumer | proposed |
| `WF-PRODUCT-010` | Inventory sync Phase 2 (DB sync + FSM) | Schedule | DB sync worker | proposed |
| `WF-PRODUCT-011` | Phát event qua outbox/CDC và retention | Internal + Schedule | Debezium, Trimmer | proposed |

---

## WF-PRODUCT-001 — Tạo product bản nháp

**Mục tiêu.** Tạo một product ở `DRAFT` cùng toàn bộ SKU và media ban đầu, và công bố
snapshot đầy đủ cho downstream.

**Actor và participants.** Admin qua Gateway; Product API; PostgreSQL; Debezium; Search.

**Trigger.** `POST /api/v1/products`.

**Preconditions.** `category_id` tồn tại và là danh mục lá; thuộc tính bắt buộc theo
danh mục có đủ; mọi SKU có `original_price > 0` và `sku_code` chưa tồn tại.

**Input.** Thông tin cấp product, danh sách SKU, danh sách media. Xem
[Interfaces](./interfaces.md#2-http-apis).

**Happy path.**

1. Validate danh mục và thuộc tính trực tiếp trên PostgreSQL (không qua cache).
2. Mở transaction: insert `products` (`status = DRAFT`), insert `product_skus`
   (`status = ACTIVE`), insert `product_media`.
3. Insert `product_outbox_events` với `PRODUCT_CREATED`, `aggregate_type = Product`,
   `partition_key = product_id`, payload là snapshot đầy đủ gồm `skus[]` có
   `best_price` và `has_promotion`.
4. Commit.
5. Nạp cache `product:sku:{sku_id}` cho từng SKU vừa tạo (tùy chọn, vì SKU `DRAFT`
   chưa được bán).
6. Trả HTTP 201.

**State/data changes.** Product, SKU, media và outbox record — tất cả trong một
transaction.

**Events emitted.** `PRODUCT_CREATED` sau khi Debezium forward.

**Failure paths.** Validation sai trả 400 và không ghi gì. Trùng `sku_code` trả 409.
Lỗi DB làm rollback toàn bộ, kể cả outbox, nên không có event mồ côi.

**Timeout và retry.** Không có retry tự động; client tự gửi lại. Vì không có
idempotency key, gửi lại sau khi commit thành công sẽ tạo product thứ hai trừ khi
`sku_code` trùng chặn lại — đây là giới hạn đã biết.

**Idempotency.** Chỉ được bảo đảm gián tiếp qua unique `sku_code`.

**Concurrency.** Không có tranh chấp: mọi row đều mới.

**Metrics.** Số product tạo, tỉ lệ lỗi validation, thời gian transaction.

**Test scenarios.** Tạo hợp lệ nhiều SKU; thiếu thuộc tính bắt buộc; danh mục không
phải lá; `original_price = 0`; `sku_code` trùng; gửi lại cùng payload hai lần.

---

## WF-PRODUCT-002 — Cập nhật product và SKU

**Mục tiêu.** Cập nhật thông tin cấp product hoặc một SKU, giữ đúng giá markdown và
làm mới cache trước khi trả response.

**Trigger.** `PUT /api/v1/products/{id}` hoặc `PUT /api/v1/products/{id}/skus/{sku_id}`.

**Preconditions.** Product tồn tại, chưa xóa mềm; với update SKU thì SKU phải thuộc
product; payload partial phải có ít nhất một field.

**Happy path.**

1. Mở transaction, lock product `FOR NO KEY UPDATE`; nếu update SKU thì lock tiếp SKU
   `FOR UPDATE` (đúng lock order Product → SKU).
2. Validate danh mục và thuộc tính nếu thay đổi; validate giá nếu có.
3. Nếu `original_price` thay đổi và SKU đang có markdown active: tính lại
   `promotional_price` theo công thức chuẩn ngay trong transaction.
4. Cập nhật row, cập nhật `updated_at`.
5. Với update cấp product: query `sku_ids[]` trong cùng transaction, insert outbox
   `PRODUCT_UPDATED` với snapshot product-level. Với update SKU: insert outbox
   `SKU_UPDATED` với snapshot SKU đầy đủ, `changed_fields[]` và
   `partition_key = sku_id`.
6. Commit.
7. Refresh cache in-band: `JSON.SET product:sku:{sku_id}` với TTL 3600 s cho từng SKU
   liên quan, trước khi trả response.
8. Trả HTTP 200.

**Events emitted.** `PRODUCT_UPDATED` hoặc `SKU_UPDATED`.

**Failure paths.** Không tìm thấy trả 404; payload rỗng trả 400; lock timeout trả 409
hoặc 503 tùy chính sách và client được phép thử lại. Nếu commit thành công nhưng
refresh cache thất bại, response vẫn trả về và cache sẽ được Event Handler làm mới khi
`SKU_UPDATED` quay lại; đây là lý do luồng in-band không được coi là bắt buộc thành
công.

**Idempotency.** Gửi lại cùng payload cho ra cùng trạng thái cuối, nhưng vẫn sinh thêm
một event `updated_at` mới.

**Concurrency.** Tranh chấp thực tế với Markdown Worker và Phase 2 trên cùng product;
lock order và `lock_timeout` là cơ chế bảo vệ. `updated_at` bảo đảm downstream không
apply bản cũ đè bản mới.

**Metrics.** Tỉ lệ lock timeout, thời gian transaction, tỉ lệ refresh cache in-band
thất bại.

**Test scenarios.** Đổi giá khi đang có markdown; đổi giá khi không có markdown; đổi
danh mục sang danh mục không phải lá; hai admin cùng sửa một product; cache refresh
thất bại sau commit.

---

## WF-PRODUCT-003 — Chuyển trạng thái sản phẩm

**Mục tiêu.** Thực hiện `SUBMIT`, `HIDE` hoặc `SHOW` đúng guard của FSM và công bố
trạng thái mới.

**Trigger.** `PATCH /api/v1/products/{id}/status`.

**Happy path.**

1. Mở transaction, lock product `FOR NO KEY UPDATE` ngay ở lệnh đầu tiên.
2. Kiểm tra guard trong cùng transaction: `SUBMIT` cần ít nhất một SKU
   `original_price > 0`; `SHOW` cần `SUM(display_quantity) > 0` và `is_deleted = false`.
3. Áp transition: `HIDE` set `admin_hidden = true`; `SHOW` reset `admin_hidden = false`;
   `SUBMIT` giữ `admin_hidden = false`.
4. Query `sku_ids[]`, insert outbox `PRODUCT_STATUS_CHANGED` kèm `old_status`,
   `new_status`, `changed_by`, `changed_by_type`, `reason`.
5. Commit, refresh cache in-band cho mọi SKU, trả HTTP 200.

**Failure paths.** Guard không thỏa trả 400 hoặc 409 (`PRODUCT_NOT_SUBMITTABLE`,
`PRODUCT_OUT_OF_STOCK`); transition không hợp lệ trả 409. Không có event nào được phát
khi guard thất bại.

**Idempotency.** `HIDE` trên product đã `HIDDEN` được phép (re-hide) và vẫn giữ
`admin_hidden = true`. `SUBMIT` lặp lại trên product `ACTIVE` bị từ chối.

**Concurrency.** `SHOW` chạy đồng thời với Phase 2 auto-hide: cả hai đều lock product
nên chỉ một thắng; kết quả cuối cùng luôn nhất quán với guard của bên chạy sau.

**Ghi chú quan trọng.** Nếu `SHOW` bị từ chối vì hết hàng, admin không cần làm gì
thêm: khi có hàng trở lại, `AUTO_REACTIVATE_IN_STOCK` sẽ tự hiện sản phẩm vì
`admin_hidden` đã là `false` sau lần `SHOW` gần nhất thành công. Nếu product bị ẩn
bằng `HIDE`, hệ thống sẽ không bao giờ tự hiện lại.

**Test scenarios.** `SUBMIT` không có SKU; `SHOW` khi tổng tồn bằng 0; `SHOW` khi có
hàng; `HIDE` hai lần liên tiếp; `SHOW` đồng thời với auto-hide.

---

## WF-PRODUCT-004 — Xóa mềm product và SKU

**Mục tiêu.** Ngừng bán một product hoặc SKU mà vẫn giữ dữ liệu lịch sử và dọn sạch
markdown liên quan.

**Trigger.** `DELETE /api/v1/products/{id}` hoặc `DELETE /api/v1/products/{id}/skus/{sku_id}`.

**Preconditions.** Product phải ở `DRAFT` hoặc `HIDDEN`; product `ACTIVE` bị từ chối.

**Happy path.**

1. Mở transaction, lock theo thứ tự Product → SKU.
2. Set `is_deleted = true`, `deleted_at = NOW()`.
3. Xóa mapping trong `product_markdown_applied` và reset `promotional_price = NULL`
   cho phạm vi bị ảnh hưởng.
4. Query `sku_ids[]` (với xóa product), insert outbox `PRODUCT_DELETED` hoặc
   `SKU_DELETED` kèm `updated_at` và các enrichment field downstream cần.
5. Commit.
6. Ghi đè cache `product:sku:{sku_id}` với `is_deleted = true` và `DEL product:markdown:active:{sku_id}`.
7. Trả HTTP 204.

**Ghi chú.** Trạng thái nghiệp vụ được giữ nguyên, không chuyển sang `HIDDEN`. Search
dùng event này để tombstone document.

**Failure paths.** Xóa product `ACTIVE` trả 400. Nếu bước 6 thất bại, cache sẽ tự hết
hạn sau TTL và lần đọc kế tiếp nạp lại từ DB với `is_deleted = true`.

**Idempotency.** Xóa lại một product đã xóa mềm là no-op và vẫn trả 204.

**Test scenarios.** Xóa product `ACTIVE`; xóa product `HIDDEN` đang có markdown; xóa
SKU cuối cùng của product; xóa hai lần.

---

## WF-PRODUCT-005 — Vòng đời danh mục và cascade khi xóa

**Mục tiêu.** Quản trị cây danh mục và bảo đảm sản phẩm không còn nằm dưới một danh
mục đã bị xóa.

**Trigger.** `POST`/`PUT`/`DELETE /api/v1/categories`; sau đó là event
`CATEGORY_DELETED` do chính service tiêu thụ.

**Happy path (nhánh xóa).**

1. API validate không còn product `ACTIVE` thuộc danh mục.
2. Transaction: xóa row, insert outbox `CATEGORY_DELETED` với
   `descendant_category_ids[]`, `partition_key = category_id`. Commit, trả 204.
3. Debezium forward event lên `t.category.events`.
4. Event Handler (`product-event-handler-group`) nhận event, xác định phạm vi gồm
   danh mục gốc và toàn bộ subtree.
5. Query product `ACTIVE`, chưa xóa mềm thuộc phạm vi đó.
6. Cập nhật theo lô 100 product, mỗi lô một transaction, lock từng product
   `FOR NO KEY UPDATE` trước khi set `status = HIDDEN`. Không phát thêm
   `PRODUCT_STATUS_CHANGED`.

**Ràng buộc.** Nhánh này chỉ đọc và ghi DB, tuyệt đối không chạm Redis.

**Race condition chấp nhận được.** Product Service (DB) và Search Service (ES) nhận
cùng event và xử lý theo thứ tự không xác định. Search guard bằng `updated_at`, còn
Cart/Checkout đọc từ cache Product chứ không từ Elasticsearch, nên khoảng lệch ngắn
không ảnh hưởng luồng mua hàng và không gây hỏng dữ liệu.

**Failure paths.** Xóa danh mục còn product `ACTIVE` trả 409. Nếu cascade thất bại
giữa chừng, các lô đã commit vẫn giữ nguyên và event được retry; thao tác set `HIDDEN`
là idempotent.

**Test scenarios.** Xóa danh mục lá rỗng; xóa danh mục có subtree; cascade thất bại
giữa chừng rồi retry; đổi tên danh mục có nhiều sản phẩm.

---

## WF-PRODUCT-006 — Point-read SKU qua gRPC

**Mục tiêu.** Trả snapshot SKU với độ trễ thấp cho Cart, Order, Checkout và Promotion.

**Trigger.** `GetSku` hoặc `GetBatchSkus`.

**Happy path (`GetSku`).**

1. `JSON.GET product:sku:{sku_id}`.
2. Cache hit: trả kết quả. Nếu TTL còn dưới ngưỡng, một request được chọn theo xác
   suất sẽ rebuild cache ở nền (probabilistic early expiration).
3. Cache miss: `SET NX EX` trên `product:lock:fetch:{sku_id}`. Thắng lock thì query
   `product_skus` join `products`, `JSON.SET` với TTL 3600 s rồi nhả lock. Trượt lock
   thì chờ ngắn và thử đọc lại cache.
4. Trả `Sku`, `SkuNotFound` hoặc `SkuUnavailable` tùy trạng thái.

**Happy path (`GetBatchSkus`).** `JSON.MGET` toàn bộ key trong một round-trip; gom
phần miss; một câu query DB duy nhất cho phần miss; nạp cache bằng pipeline; trả kết
quả gồm cả phần hit và phần vừa fetch.

**Failure paths.** Redis lỗi thì rơi thẳng về DB, latency tăng nhưng vẫn phục vụ. DB
lỗi thì trả lỗi gRPC và caller phải mở circuit breaker. Quá 100 `sku_id` trong một
request bị từ chối.

**Idempotency.** Read-only, an toàn retry trong retry budget của caller.

**Concurrency.** Nhiều request cùng miss trên một SKU được gom lại bằng distributed
lock để tránh cache stampede.

**Metrics.** Cache hit ratio, latency p99 theo RPC, số lần rebuild, số lần trượt lock.

**Test scenarios.** Cache hit; cache miss có tranh chấp lock; batch có một phần miss;
Redis không sẵn sàng; SKU đã xóa mềm; SKU `INACTIVE`.

---

## WF-PRODUCT-007 — Áp, cập nhật và gỡ markdown

**Mục tiêu.** Phản chiếu markdown rule của Promotion thành `promotional_price` và
`best_price` trên SKU, không để sót và không để kẹt giá.

**Trigger.** `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` trên
`t.promotion.markdown.events`, consumer group `product-markdown-group`.

**Happy path.**

1. Nhận event, kiểm tra allowlist; event ngoài allowlist thì skip kèm metric và DLQ.
   Chuẩn hóa `rule_id` về string ngay tại consumer boundary.
2. Với `MARKDOWN_CREATED` có `start_time` trong tương lai: đẩy job vào ZSET
   `product:markdown:pending` và kết thúc (xem [WF-PRODUCT-008](#wf-product-008--markdown-pending-gate-và-expiry-self-healing)).
3. Xác định tập SKU bị ảnh hưởng: theo `product_id` nếu `scope_type = PRODUCT`; theo
   `category_id` với product `ACTIVE` và SKU `ACTIVE` nếu `scope_type = CATEGORY`; theo
   `product_markdown_applied.rule_id` nếu là `MARKDOWN_UPDATED`/`MARKDOWN_ENDED`.
4. Group kết quả theo `product_id` trong bộ nhớ; với tập lớn thì stream theo
   `ORDER BY product_id ASC, sku_id ASC`.
5. Với mỗi `product_id`, mở một transaction riêng:
   - Lock product `FOR NO KEY UPDATE` với `lock_timeout` ngắn; timeout thì nack và
     requeue theo retry policy, không skip im lặng.
   - Lock các SKU `FOR UPDATE` và lấy `original_price` **live** từ chính query này;
     không dùng snapshot cũ trong payload.
   - Tính `promotional_price` theo công thức chuẩn, hoặc đặt `NULL` nếu là `ENDED`.
   - Batch update `product_skus`, upsert hoặc xóa `product_markdown_applied`, insert
     outbox `SKU_UPDATED` với `changed_fields = [original_price, promotional_price, best_price, has_promotion]`
     và `partition_key = sku_id`.
   - Commit để nhả lock trước khi sang product kế tiếp.
6. Sau mỗi commit, dùng Redis pipeline để set hoặc xóa `product:markdown:active:{sku_id}`.
   Worker **không** ghi `product:sku:{sku_id}`.
7. Event Handler tiêu thụ `SKU_UPDATED` và refresh point-read cache.

**Failure paths.** `MARKDOWN_ENDED` không tìm thấy mapping nào phải ack và no-op,
không phát outbox rỗng. Lock timeout thì requeue. Lỗi DB thì rollback đúng một product,
các product đã commit không bị ảnh hưởng.

**Idempotency.** Unique `(rule_id, sku_id)` chặn áp trùng; áp lại cùng rule cho ra
cùng giá; gỡ lại rule đã gỡ là no-op.

**Concurrency.** Nhiều instance worker cùng nhận partition khác nhau vẫn có thể chạm
cùng product; lock product là điểm đồng bộ duy nhất. Tuyệt đối không chạy một
`UPDATE` diện rộng theo category vì sẽ deadlock chéo với admin API.

**Metrics.** Số SKU được áp/gỡ mỗi event, thời gian xử lý mỗi product, số lần lock
timeout, độ trễ từ event tới commit cuối.

**Test scenarios.** Rule `PERCENTAGE` có và không có cap; rule `FIXED_AMOUNT` lớn hơn
giá gốc (kết quả clamp về 0); scope CATEGORY nhiều nghìn SKU; `MARKDOWN_UPDATED` sau
khi admin đã đổi `original_price`; `MARKDOWN_ENDED` cho rule không còn mapping; hai
worker cùng xử lý một product.

---

## WF-PRODUCT-008 — Markdown pending gate và expiry self-healing

**Mục tiêu.** Áp markdown đúng thời điểm bắt đầu, và bảo đảm không SKU nào kẹt giá
khuyến mãi sau khi rule đã hết hạn.

**Trigger.** Scheduler drain `product:markdown:pending` (mỗi 1–5 phút) và cronjob
expiry chạy mỗi ngày vào giờ thấp điểm.

**Happy path — pending gate.**

1. `ZRANGEBYSCORE product:markdown:pending -inf {now_epoch_ms}` để lấy job đến hạn.
2. Gọi luồng áp markdown của [WF-PRODUCT-007](#wf-product-007--áp-cập-nhật-và-gỡ-markdown) cho từng job.
3. `ZREM` sau khi xử lý xong. Job chưa xử lý xong vẫn nằm trong ZSET nên rebalance
   hoặc restart không làm mất job.

**Happy path — expiry cronjob.**

1. Acquire `product:jobs:markdown-expiry:lock` bằng `SET NX PX`; không lấy được thì
   bỏ qua vòng chạy này. Gia hạn lock nếu job chạy dài.
2. Quét `product_markdown_applied` với `expires_at < NOW()`, `ORDER BY product_id ASC`,
   `LIMIT 100`.
3. Với mỗi lô: duyệt `product_id` tăng dần, lock product cha trước, reset
   `promotional_price = NULL`, xóa mapping, insert outbox `SKU_UPDATED` với
   `change_source = MARKDOWN_EXPIRED_FALLBACK`, commit ngay để nhả lock.
4. Xóa `product:markdown:active:{sku_id}` tương ứng.
5. Lặp cho tới khi không còn record đến hạn.

**Failure paths.** Mất lock giữa chừng thì job dừng ở lô hiện tại; lô sau sẽ do lần
chạy kế tiếp xử lý. Không bao giờ gom toàn bộ record vào một transaction.

**Idempotency.** Reset một SKU đã hết markdown là no-op ở mức dữ liệu, nhưng vẫn sinh
event; vì vậy điều kiện quét phải chính xác để tránh phát event thừa.

**Ghi chú.** Cronjob này là mạng lưới an toàn cho trường hợp `MARKDOWN_ENDED` bị mất
hoặc Promotion Service lỗi, không phải cơ chế chính.

**Test scenarios.** Job pending đến hạn sau khi worker restart; nhiều pod cùng chạy
cronjob; hàng chục nghìn record hết hạn cùng lúc; rule hết hạn nhưng `MARKDOWN_ENDED`
đã xử lý trước đó.

---

## WF-PRODUCT-009 — Inventory sync Phase 1 (lock-free)

**Mục tiêu.** Phản ánh thay đổi tồn kho vào point-read cache trong vài mili giây mà
không chạm PostgreSQL.

**Trigger.** `INVENTORY_STOCK_CHANGED` trên `t.inventory.events`, consumer group
`product-inventory-fast-sync-group`.

**Happy path.**

1. Parse CloudEvents wrapper, convert `timestamp` (ISO 8601 UTC) sang epoch millis.
   Đây là `event_timestamp` dùng cho mọi staleness guard; **không** đọc mốc này từ
   payload.
2. Parse payload bắt buộc: `product_id`, `sku_id`, `available_quantity`, `change_type`.
   Suy ra `sku_status` theo `available_quantity`.
3. Thực thi [`inventory_fast_sync.lua`](./scripts/inventory_fast_sync.lua): guard theo
   `last_inventory_sync_at` và `product_id`, ghi các field tồn kho, `ZADD` vào
   `product:inventory:pending` với score là `event_timestamp`.
4. Commit offset Kafka **sau khi** Redis trả về thành công.

**State/data changes.** Chỉ Redis. PostgreSQL không bị chạm ở phase này.

**Failure paths.** Redis lỗi thì không commit offset và Kafka sẽ redeliver. Cache miss
(`-1`) không phải lỗi: item vẫn vào pending queue và Phase 2 sẽ nạp snapshot đầy đủ từ
DB. Event cũ hoặc lệch `product_id` (`0`) bị bỏ qua kèm metric.

**Idempotency.** Lua CAS bảo đảm apply lại cùng event là no-op và event cũ không bao
giờ ghi đè giá trị mới hơn.

**Concurrency.** 8–16 consumer thread trong cùng group; ordering theo partition của
Inventory. Guard theo timestamp là cơ chế bảo vệ chính khi thứ tự không được bảo đảm.

**Ràng buộc.** Phase 1 không ghi field giá, `price_updated_at` hay `product_status`.

**Metrics.** Tỉ lệ apply/stale/miss theo mã trả về của script, độ trễ xử lý, consumer
lag, độ sâu ZSET pending.

**Test scenarios.** Event mới hơn; event cũ hơn; hai event cùng timestamp; cache miss;
`product_id` không khớp; Redis timeout giữa chừng.

---

## WF-PRODUCT-010 — Inventory sync Phase 2 (DB sync và FSM)

**Mục tiêu.** Đưa tồn kho từ cache xuống PostgreSQL theo lô và chạy auto-hide /
auto-reactivate ở cấp product.

**Trigger.** Scheduled worker chạy mỗi 500 ms – 1 s.

**Happy path.**

1. Drain `product:inventory:pending` theo score với phân trang 100–500 item mỗi lượt.
2. Group theo `product_id` trong bộ nhớ.
3. Với mỗi product, mở một transaction:
   - Lock product `FOR NO KEY UPDATE` một lần cho cả lô SKU.
   - Query **toàn bộ** SKU của product, không chỉ SKU có trong lô, để đánh giá FSM
     trên đúng trạng thái đầy đủ.
   - Conditional update `display_quantity`, `sku_status`, `last_inventory_sync_at`:
     chỉ apply khi mốc trong lô mới hơn mốc đang có trong DB.
   - Đánh giá FSM: `AUTO_HIDE_OUT_OF_STOCK` khi product đang `ACTIVE` và toàn bộ SKU
     hết hàng; `AUTO_REACTIVATE_IN_STOCK` khi product `HIDDEN`, `admin_hidden = false`
     và `is_deleted = false` và đã có SKU còn hàng. Cả hai điều kiện được kiểm tra
     trong chính transaction này.
   - Insert outbox `SKU_UPDATED` cho các SKU đổi trạng thái, kèm `change_source`, và
     kèm `product_status_changed = true` cùng `new_product_status` nếu FSM đổi trạng
     thái product.
   - Commit.
4. `ZREM` các item đã xử lý khỏi ZSET **sau khi** DB commit thành công.
5. Event Handler tiêu thụ `SKU_UPDATED` và refresh `product:sku:{sku_id}`.

**Ràng buộc.** Phase 2 không ghi trực tiếp point-read cache.

**Failure paths.** Conditional update không áp được vì DB đã mới hơn: bỏ qua, coi như
đã đồng bộ. Lỗi DB hoặc lock timeout: item ở lại ZSET và được retry với backoff. Crash
sau commit nhưng trước `ZREM`: lần chạy sau xử lý lại và conditional update biến nó
thành no-op.

**Idempotency.** Bảo đảm bằng conditional update theo mốc thời gian cộng với việc chỉ
ack sau commit.

**Concurrency.** Tranh chấp với admin API trên cùng product được giải quyết bằng lock
product; lock order Product → SKU được giữ nguyên.

**Metrics.** Độ sâu và tuổi của ZSET pending, số product mỗi lượt, độ trễ event → DB,
số lần FSM auto-hide/auto-reactivate, số lần requeue.

**Test scenarios.** Toàn bộ SKU về 0 rồi có hàng lại; product có `admin_hidden = true`
có hàng trở lại (không được reactivate); item pending của SKU đã bị xóa mềm; crash
giữa commit và `ZREM`; lô lớn nhiều product; Phase 1 dừng nhưng Phase 2 vẫn drain hết
hàng đợi.

---

## WF-PRODUCT-011 — Phát event qua outbox/CDC và retention

**Mục tiêu.** Chuyển mọi record outbox lên Kafka đúng thứ tự theo `partition_key`, và
giữ bảng outbox không phình.

**Trigger.** Ghi outbox trong transaction nghiệp vụ; cronjob retention chạy hằng tuần
vào giờ thấp điểm.

**Happy path.**

1. Application insert outbox trong cùng transaction với thay đổi dữ liệu; validate
   `partition_key` khác `NULL` và khác rỗng trước khi insert.
2. PostgreSQL ghi WAL. Debezium đọc qua logical replication slot, không tốn connection
   pool của request.
3. Connector lấy trực tiếp cột `partition_key` làm Kafka message key — không dùng SMT
   để parse JSON trong payload — rồi phát lên `t.product.events` hoặc `t.category.events`
   dưới định dạng CloudEvents.
4. Retention trimmer xóa record cũ hơn cửa sổ giữ lại theo từng lô 10.000, chọn theo
   `created_at ASC`, không dùng `DELETE` toàn bảng.

**Failure paths.** Kafka Connect dừng: WAL không được tiêu thụ và replication slot
phình theo thời gian, có thể làm đầy đĩa PostgreSQL — đây là failure mode nghiêm trọng
nhất của kiến trúc này, xem [Operations](./operations.md#6-failure-modes). Lỗi khi
forward được xử lý bằng DLQ cấu hình ở Kafka Connect. Trimmer xóa nhầm record chưa
được forward nếu cửa sổ retention ngắn hơn thời gian connector dừng, nên cửa sổ phải
lớn hơn thời gian tối đa chấp nhận được của một sự cố Connect.

**Idempotency.** At-least-once: cùng một `event_id` có thể xuất hiện nhiều lần trên
Kafka; consumer phải dedupe.

**Metrics.** Độ trễ outbox → Kafka, kích thước bảng outbox, replication slot lag, số
record trimmer xóa mỗi lượt, trạng thái connector.

**Test scenarios.** Insert outbox thiếu `partition_key` (phải bị chặn ở application);
connector dừng rồi khởi động lại; trimmer chạy khi connector đang dừng; event của cùng
product luôn về cùng partition.

---

## Test scenario bắt buộc chung

Mỗi workflow cần có test cho: happy path, biên của validation, xử lý trùng lặp, timeout
và retry, request/event chạy đồng thời, dependency (Redis, Kafka, DB) không sẵn sàng, và
đường phục hồi sau lỗi. Hiện chưa có evidence về bộ test tương ứng; xem
[Development](./development.md#3-build-quality-và-tests).
