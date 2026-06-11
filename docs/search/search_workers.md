# Search Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Events](../product/product_events.md)
*   [Review Service - Events](../review/review_events.md)
*   [Order Service - Events](../order/order_events.md)

---

Search Service là read model thuần túy. Worker chỉ làm 3 việc: đồng bộ projection vào Elasticsearch, đồng bộ cache hỗ trợ đọc nhanh, và vận hành bảo trì an toàn.

**Policy chung cho shared topics (bắt buộc):** mọi worker khi nhận event hợp lệ theo envelope nhưng `event_type` ngoài allowlist của consumer group phải ignore + ACK + ghi metric `search_event_ignored_total{topic,event_type}`.

## Tổng Hợp Kafka Topics & Consumer Groups

| Topic | Consumer Group | Mô tả |
|---|---|---|
| `t.product.events` | `search-product-indexer` | Đồng bộ product/SKU projection |
| `t.category.events` | `search-category-indexer` | Đồng bộ denormalized category fields |
| `t.review.events` | `search-review-metrics-sync` | Đồng bộ điểm đánh giá và số lượng review |
| `t.search.metrics.events` | `search-sales-metrics-sync` | Đồng bộ sold metrics theo product |
| `t.search.retry` | `search-retry-replayer` | Retry/self-heal cho các lỗi có khả năng phục hồi (ví dụ `document_missing_exception`) |
| `t.search.dlq` | `search-dlq-processor` | Parking lot cho lỗi không tự phục hồi và replay có kiểm soát |

## 1. Product Indexing Worker (Kafka Consumer)
**Loại hình:** Long-running consumer
**Consumer Group:** `search-product-indexer`

**Nhiệm vụ:** consume events từ `t.product.events`, transform thành document schema chuẩn, ghi vào alias `products_write`.

**Luồng xử lý chuẩn:**
1. Poll batch Kafka (`max.poll.records` gợi ý: 200-500).
   - Nếu gặp `event_type` không thuộc allowlist của worker này, phải ignore + ACK + ghi metric `search_event_ignored_total{topic,event_type}`.
2. Transform payload -> lệnh `_update`/`_update_by_query` theo contract trong [Events](search_events.md).
   - Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`: chuẩn hoá `skus[].status -> skus[].sku_status` trước khi index.
   - Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`: nếu `skus` rỗng/null hoặc thiếu `skus[].best_price`/`skus[].has_promotion`, đánh dấu contract violation và chuyển `t.search.dlq`.
   - Idempotency và chống out-of-order được đảm bảo hoàn toàn bởi guard scripts trong Elasticsearch (xem [Events - Chiến lược Idempotency](search_events.md#3-chiến-lược-idempotency--ordering)). Không cần dedup trong RAM.
3. Gọi `_bulk` cho các lệnh cấp SKU, tách riêng `_update_by_query` cho event diện rộng.
4. Parse kết quả:
   - Lỗi tạm thời (timeout, 429, 503): không commit offset, để Kafka retry.
   - `document_missing_exception` cho partial update: đẩy `t.search.retry` để worker tự phục hồi bằng full snapshot fetch.
   - Lỗi dữ liệu không tự hồi phục (mapping conflict/schema poison): gửi bản ghi lỗi sang `t.search.dlq` kèm nguyên payload và metadata.
   - Batch processing phải tiếp tục xử lý hết records còn lại trừ khi gặp transient lỗi mức cụm (cluster unavailable).
   - Bản ghi được coi là "park an toàn" chỉ khi produce vào `t.search.retry`/`t.search.dlq` đã nhận ACK bền vững (`acks=all`, producer idempotent).
5. Chỉ commit offset khi toàn bộ records trong batch đã:
   - apply thành công, hoặc
   - được park an toàn vào `t.search.retry` hoặc `t.search.dlq`.
   - Nếu batch có ít nhất 1 record transient chưa xử lý xong thì không commit offset batch đó.
6. Nếu event làm thay đổi category footprint thì invalidate `search:categories:tree` để category browsing không giữ số đếm cũ quá lâu:
   - Luôn invalidate với `PRODUCT_STATE_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`.
   - Với `PRODUCT_UPDATED`: invalidate luôn (không so sánh old/new fields) — đơn giản hóa, tránh logic phức tạp. Over-invalidate là vô hại.
   - Với payload legacy thiếu old/new, dùng chính sách bảo toàn đúng đắn: invalidate mỗi `PRODUCT_UPDATED`.

**Throttle bắt buộc cho diện rộng:**
- `PRODUCT_STATE_CHANGED` phải dùng `requests_per_second` (ví dụ 300-500) để tránh đẩy CPU ES lên 100%.

## 2. Category Denormalization Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-category-indexer`

**Nhiệm vụ:** đồng bộ `category_name`, `category_path` cho toàn bộ docs theo `category_id`.

**Luồng xử lý chuẩn:**
1. Consume `CATEGORY_UPDATED`/`CATEGORY_DELETED` từ `t.category.events`.
2. Dùng `_update_by_query` để update `category_name` và `category_path` cho toàn bộ docs thuộc `category_id`.
3. Với batch diện rộng, bắt buộc đặt `requests_per_second` (ví dụ 300-500) + `conflicts=proceed` cho `_update_by_query` để tránh spike CPU Elasticsearch.
4. Parse kết quả `_update_by_query`: `version_conflicts` có thể chấp nhận (do guard), nhưng `failures` không rỗng phải tách retry có kiểm soát hoặc chuyển `t.search.dlq`.
5. Invalidate `search:categories:tree` sau khi update thành công.

## 3. Review Metrics Sync Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-review-metrics-sync`

**Nhiệm vụ:** đồng bộ `product_rating_avg`, `product_rating_count` theo `product_id`.

**Guard chuẩn:**
- Nếu envelope và payload cùng có nhưng lệch nhau: contract violation -> DLQ.
- `updated_at` chỉ phục vụ observability, không dùng làm fallback ordering key.

## 4. Sales Metrics Sync Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-sales-metrics-sync`

**Nhiệm vụ:** đồng bộ `product_sold_count` theo `product_id` từ topic metrics chuyên dụng.

**Lý do tách topic:** `t.order.events` partition theo `order_id`, không phù hợp để giữ ordering metrics theo `product_id` ở quy mô lớn.

## 5. Category Tree Sync Worker
**Loại hình:** startup warmer + Pub/Sub listener + periodic self-heal

**Nhiệm vụ:** nạp category tree làm nguồn enrichment cho Product Indexing Worker.

**Nguồn dữ liệu:**
- API nội bộ Product: `GET /api/v1/internal/products/categories/tree`
- Redis Pub/Sub channel chính: `channel:search:category-refresh`
- Compatibility channel (giai đoạn chuyển tiếp): `channel:product:l1-evict`

**Ordering khởi động bắt buộc:**
1. Warm category tree thành công vào RAM + Redis.
2. Chỉ sau đó mới mở consumer `search-product-indexer`.
3. Nếu warm thất bại, áp dụng quy tắc degraded startup mode ở dưới để quyết định readiness.

**Degraded startup mode (bắt buộc):**
1. Warm API timeout mặc định 30s (configurable).
2. Nếu API timeout nhưng Redis còn snapshot category cũ, cho phép lên ready ở chế độ degraded (gắn metric/alert) và tiếp tục retry warm ngầm.
3. Chỉ fail ready cứng khi vừa warm API lỗi vừa không có snapshot category trong Redis.

**Chống stampede toàn cụm:**
- Fallback refresh phải qua lock `SETNX search:lock:category-tree-refresh 1 EX 30`.

### 5.1. Hot Query Refresh Worker (SWR)
**Loại hình:** In-process async worker pool (non-Kafka)

**Nhiệm vụ:** xử lý refresh cho `search:query:{query_hash}` khi cache hit nhưng `valid_until` đã hết hạn.

**Thành phần bắt buộc:**
1. **Singleflight (pod-local):** gom request trùng `query_hash` trong cùng pod.
2. **Bounded Refresh Queue:** hàng đợi in-memory giới hạn cứng (ví dụ 2,000 tasks/pod).
3. **Redis Dedup Key (multi-pod):** `search:refresh:{query_hash}` — key duy nhất thay thế cho cả `search:refresh:enqueue:` và `search:lock:refresh:`.
   - Scheduler: `SETNX search:refresh:{query_hash} 1 EX {ttl}` trước khi enqueue queue, `ttl = max(30, 2 * es_refresh_p99_seconds)`.
   - Worker: `GET search:refresh:{query_hash}` để xác nhận key còn tồn tại trước khi query ES. Nếu key đã expired, worker vẫn tiếp tục execute (TTL tự bảo vệ).
   - Hoàn tất: `DEL search:refresh:{query_hash}` trong `finally`.

**Worker semantics:**
1. Queue thread-safe dùng destructive `take/pop`: một queue-node chỉ có một worker lấy.
2. Worker khác không lấy cùng queue-node đó.
3. Duplicate logic task chỉ xảy ra nếu enqueue trùng key; đã chặn bằng Redis dedup key (SETNX).

**Queue full & backpressure policy:**
1. Scheduler dùng `offer` non-blocking.
2. Nếu queue full: xoá dedup key (`DEL search:refresh:{query_hash}`), tăng metric `search_refresh_queue_dropped_total`, không block request path, vẫn trả stale data.
3. Alert khi tỉ lệ drop vượt ngưỡng SLO.
4. Chính sách này là có chủ đích cho Search (stale-tolerant read path). Không dùng policy block/pause như Product Service vì Search ưu tiên giữ latency đọc ổn định và không có ràng buộc write-critical.
5. Không phục vụ stale nếu đã vượt `stale_max_until`; request path phải fallback query ES trực tiếp với timeout ngắn.

**Pod crash behavior:**
1. Task trong queue RAM có thể mất khi pod down/restart.
2. Redis dedup key `search:refresh:{query_hash}` có TTL độc lập với Pod lifecycle — nếu Pod A enqueued task và down trước khi worker take, key vẫn còn TTL. Pod B khi receive stale request sẽ SETNX fail và không enqueue thêm. Worker của Pod B hoặc Pod C sẽ take task khi TTL hết hạn.
3. Đây là chấp nhận được với SWR: request kế tiếp sẽ trigger refresh lại.
4. Thiết kế hiện tại không thêm Redis Streams/Kafka refresh topic để tránh tăng độ phức tạp vận hành.

**Pseudo-flow ngắn:**
1. Request stale gọi `scheduleRefresh(query_hash)`.
2. Singleflight callback thử `SETNX search:refresh:{query_hash} 1 EX {ttl}`. Nếu fail → dừng.
3. SETNX success → `offer` task vào local queue.
4. Worker `take()` task từ queue.
5. Worker xác nhận key còn tồn tại (`GET`). Nếu đã hết hạn → vẫn tiếp tục execute.
6. Query ES → overwrite cache + `valid_until` mới + `stale_max_until` mới.
7. `finally`: `DEL search:refresh:{query_hash}`.

## 6. Full Reindex Job (Zero-Downtime)
**Loại hình:** manual trigger hoặc scheduled maintenance

**Mục tiêu:** đổi mapping/index settings mà không downtime đọc/ghi, đồng thời không làm mất ordering guarantees của pipeline realtime.

### 6.1. Preconditions (Bắt buộc trước khi chạy)
1. Cụm Elasticsearch xanh (`green`) và dung lượng đĩa đủ cho thêm 1 index full-size + replica.
2. Kafka lag của các consumer group Search đang trong ngưỡng SLO (tránh vừa reindex vừa chữa cháy backlog lớn).
3. Freeze các thay đổi mapping ngoài kế hoạch trong suốt phiên reindex.
4. Tạo `reindex_run_id` để log/metric/audit toàn bộ pha chạy.

### 6.2. Phase A - Khởi tạo target index
1. Sinh index mới `products_v{N+1}` từ template mapping/settings đã review.
2. Áp dụng đầy đủ analyzer/mapping mới như trong [Elasticsearch Schema](search_elasticsearch_schema.md).
3. Chưa swap alias ở pha này, traffic vẫn đọc/ghi index cũ (`products_vN`).

### 6.3. Phase B - Snapshot Backfill (Base load)
1. Chụp watermark bắt đầu (`wm_start`) gồm:
   - thời điểm UTC bắt đầu backfill,
   - offset từng partition của các topic liên quan (`t.product.events`, `t.category.events`, `t.review.events`, `t.search.metrics.events`).
2. Seed snapshot từ index hiện tại `products_vN` (scroll/reindex theo chunk) để giữ nguyên các field denormalized do stream khác sở hữu (review/sales/category).
3. (Tuỳ chọn nhưng khuyến nghị) Chạy reconciliation pass từ Product internal export API cho các field Product-owned:
   - `GET /api/v1/internal/products/export?page={n}&size=500`.
   - Payload export phải có tối thiểu `skus[].sku_status`, `skus[].best_price`, `skus[].has_promotion` để tránh drift khi rebuild read-model.
4. Upsert vào `products_v{N+1}` bằng script guard `version` để chống ghi lùi.
5. Ghi checkpoint sau mỗi chunk (`last_page`, `docs_written`, `error_count`) để có thể resume nếu job fail giữa chừng.

### 6.4. Phase C - Catch-up Delta (Replay sau watermark)
1. Chạy consumer group tạm thời `search-reindex-catchup-{reindex_run_id}` đọc từ `wm_start`.
2. Consumer này **không** ghi qua alias `products_write`, mà ghi trực tiếp vào `products_v{N+1}` để tránh ảnh hưởng luồng realtime hiện tại.
3. Áp dụng đúng guard idempotency/out-of-order như [Events](search_events.md#3-chiến-lược-idempotency--ordering):
   - `version`,
4. Điều kiện hoàn tất catch-up:
   - lag của group catch-up = 0 ổn định tối thiểu 3 chu kỳ poll liên tiếp (poll interval 30s),
   - không còn retry backlog mở cho `products_v{N+1}`,
   - không phát sinh lỗi ingest mới trong tối thiểu 10 phút cuối.

### 6.5. Phase D - Validation Gates trước cutover
1. So sánh `doc_count` giữa `products_vN` và `products_v{N+1}` theo ngưỡng: `min(0.5%, 10_000 docs)`.
2. Chạy sample diff theo `sku_id` (random + top hot products) cho các trường quan trọng: giá, status, rating, sold.
3. Chạy tập truy vấn smoke test (keyword search, filter, sort, collapse, facets) trên index mới.
4. Nếu fail bất kỳ gate nào: dừng cutover, giữ alias cũ, sửa lỗi và chạy lại từ checkpoint.

### 6.6. Phase E - Atomic Alias Cutover
1. Thực hiện duy nhất một lệnh `_aliases` để swap đồng thời:
   - read alias `products` -> `products_v{N+1}`
   - write alias `products_write` -> `products_v{N+1}`
2. Không tách 2 lệnh riêng lẻ để tránh trạng thái nửa vời read/write lệch index.

### 6.7. Rollback Strategy (Không downtime)
1. Khi phát hiện lỗi nghiêm trọng ngay sau cutover, rollback bằng một lệnh `_aliases` atomic đưa cả `products` và `products_write` về `products_vN`.
2. Để rollback vẫn an toàn trong cửa sổ quan sát (vd. 24h), phải chạy **shadow catch-up** cho `products_vN` từ offset cutover (hoặc cơ chế mirror tương đương). Nếu không shadow catch-up, rollback lâu sẽ gây mất dữ liệu mới.
3. Shadow catch-up phải có consumer group riêng và cảnh báo nếu shadow lag > 5 phút.
4. Sau rollback, giữ lại toàn bộ artifact (`reindex_run_id`, diff report, error sample) để root-cause.

### 6.8. Post-cutover cleanup
1. Tiếp tục theo dõi p95/p99 latency, error rate, ingest lag ít nhất 30-60 phút.
2. Khi hết cửa sổ rollback và metric ổn định, mới xóa `products_vN`.
3. Cập nhật runbook vận hành bằng dữ liệu thực tế của lần chạy.

## 7. Search Suggestions Rebuilder
**Loại hình:** Cronjob hàng ngày (có thể chạy ad-hoc khi campaign lớn)

**Mục tiêu:** tái tạo bộ gợi ý autocomplete chất lượng cao, có ranking rõ ràng, không gián đoạn API suggest.

### 7.1. Inputs & ownership
1. Analytics queries (top query theo traffic/CTR/conversion).
2. Category tree (đảm bảo gợi ý danh mục luôn hiện diện).
3. Hot products từ alias `products` (ưu tiên sản phẩm ACTIVE, in-stock, sold cao).
4. Rebuilder chỉ build read-model; không tự ý sửa dữ liệu nghiệp vụ của Product/Category.

### 7.2. Normalization & dedupe
1. Chuẩn hóa text: trim, lowercase, unicode-folding, collapse whitespace.
2. Sinh `dedupe_key = normalized_text + type + scope`.
3. Nếu trùng key từ nhiều nguồn, merge thành 1 doc duy nhất, cộng/trộn tín hiệu ranking theo trọng số nguồn.

### 7.3. Ranking model (phiên bản tài liệu)
1. `weight` cuối cùng phải deterministic theo công thức thống nhất (ví dụ):
   - `weight = w_query * query_count_7d + w_ctr * ctr_30d + w_conv * conversion_rate_30d + w_sales * product_sold_count_30d + source_boost`.
2. Gợi ý CATEGORY có `source_boost` nền để không bị chìm hoàn toàn trước PRODUCT ở cùng prefix.
3. Cùng `weight` thì tie-break bằng `query_count_7d desc`, `text asc` để ổn định kết quả.

### 7.4. Build pipeline (Zero-downtime)
1. Acquire distributed lock `search:lock:suggestions-rebuild` (SETNX + EX) để tránh chạy trùng job.
2. Tạo `search_suggestions_v{N+1}` theo mapping chuẩn tại [Elasticsearch Schema](search_elasticsearch_schema.md#3-search-suggestions-index-mapping--ranking-contract).
3. Nạp candidates theo từng nguồn, normalize + dedupe + tính weight.
4. Bulk index theo chunk (vd. 1k-5k docs/chunk), retry có backoff cho lỗi 429/503.
5. Validation gate trước swap:
   - tổng số doc >= ngưỡng tối thiểu,
   - không có prefix hot bị rỗng,
   - tỷ lệ parse lỗi < ngưỡng cho phép.
6. Atomic alias swap `search_suggestions` -> `search_suggestions_v{N+1}`.
7. Warm Redis `search:suggest:{prefix}` cho top prefixes (2-4 ký tự đầu) để giảm cold-start.

### 7.5. Failure semantics
1. Nếu fail trước bước alias swap: giữ alias cũ, API suggest không bị gián đoạn.
2. Nếu fail sau swap nhưng chất lượng kém: rollback alias về version trước.
3. Ghi metric bắt buộc: số doc build, merge ratio, error ratio, build duration, warm-cache hit rate.

## 8. Facet Pre-warmer
**Loại hình:** Cronjob mỗi 5 phút

**Nhiệm vụ:** precompute facets cho category hot.

**Flash sale mode:**
- Khi `is_flash_sale_active=true` cho category, API bypass facet cache và query realtime aggregation để tránh số đếm sai.

**Nguồn cờ flash sale (bắt buộc):**
- Cờ `is_flash_sale_active` phải được đồng bộ từ control-plane (campaign scheduler/job vận hành) vào Redis key `search:flash_sale:category:{category_id}`.
- Nếu không có cờ, mặc định coi là `false` (không bypass facet cache).

## 9. Draft Cleanup Job
**Loại hình:** daily off-peak job

**Nhiệm vụ:** dọn docs DRAFT quá hạn.

**Điều kiện chuẩn:**
- `product_status = DRAFT`
- `created_at < now - 30d`
- `is_deleted = false`

**Ràng buộc ownership:**
- Chỉ được cleanup các docs DRAFT khi có tín hiệu ownership từ Product (ví dụ cờ `is_deleted` hoặc danh sách product_id quá hạn từ export API).
- Không được tự xoá DRAFT chỉ dựa vào tuổi dữ liệu nếu Product vẫn coi là bản nháp hợp lệ cho admin.
