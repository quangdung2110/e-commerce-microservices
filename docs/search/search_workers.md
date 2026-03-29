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
*   [Seller Service - Design](../seller/seller_design.md)
*   [Order Service - Events](../order/order_events.md)

---

Search Service là read model thuần túy. Worker chỉ làm 3 việc: đồng bộ projection vào Elasticsearch, đồng bộ cache hỗ trợ đọc nhanh, và vận hành bảo trì an toàn.

## Tổng Hợp Kafka Topics & Consumer Groups

| Topic | Consumer Group | Mô tả |
|---|---|---|
| `t.product.events` | `search-product-indexer` | Đồng bộ product/SKU projection |
| `t.category.events` | `search-category-indexer` | Đồng bộ denormalized category fields |
| `t.review.events` | `search-review-metrics-sync` | Đồng bộ điểm đánh giá và số lượng review |
| `t.seller.events` | `search-seller-profile-sync` | Đồng bộ thông tin seller |
| `t.search.metrics.events` | `search-sales-metrics-sync` | Đồng bộ sold metrics theo product |

## 1. Product Indexing Worker (Kafka Consumer)
**Loại hình:** Long-running consumer
**Consumer Group:** `search-product-indexer`

**Nhiệm vụ:** consume events từ `t.product.events`, transform thành document schema chuẩn, ghi vào alias `products_write`.

**Luồng xử lý chuẩn:**
1. Poll batch Kafka (`max.poll.records` gợi ý: 200-500).
2. Dedup trong RAM theo `sku_id`:
   - `PRODUCT_PRICE_UPDATED`: giữ event có `event_sequence_id` cao nhất.
   - Event khác: giữ event có `version` cao nhất.
3. Transform payload -> lệnh `_update`/`_update_by_query` theo contract trong [Events](search_events.md).
4. Gọi `_bulk` cho các lệnh cấp SKU, tách riêng `_update_by_query` cho event diện rộng.
5. Parse kết quả:
   - Lỗi tạm thời (timeout, 429, 503): không commit offset, để Kafka retry.
   - Lỗi dữ liệu không tự hồi phục (mapping conflict/schema poison): gửi bản ghi lỗi sang `t.search.dlq` kèm nguyên payload và metadata.
6. Chỉ commit offset khi toàn bộ records trong batch đã:
   - apply thành công, hoặc
   - được park an toàn vào DLQ.

**Throttle bắt buộc cho diện rộng:**
- `PRODUCT_STATE_CHANGED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED` phải dùng `requests_per_second` (ví dụ 300-500) để tránh đẩy CPU ES lên 100%.

## 2. Category Denormalization Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-category-indexer`

**Nhiệm vụ:** đồng bộ `category_name`, `category_path`, `category_version` cho toàn bộ docs theo `category_id`.

**Luồng xử lý chuẩn:**
1. Consume `CATEGORY_UPDATED`/`CATEGORY_DELETED` từ `t.category.events`.
2. Dùng `_update_by_query` với guard `category_version`:
   - chỉ apply nếu `incoming.category_version > doc.category_version`.
3. Dùng retry có backoff cho version conflict transient.
4. Invalidate `search:categories:tree` sau khi update thành công.

## 3. Review Metrics Sync Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-review-metrics-sync`

**Nhiệm vụ:** đồng bộ `product_rating_avg`, `product_rating_count` theo `product_id`.

**Guard chuẩn:**
- Ưu tiên `event_sequence_id`.
- Fallback `updated_at` chỉ để tương thích ngược khi upstream chưa phát sequence.

## 4. Seller Profile Sync Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-seller-profile-sync`

**Nhiệm vụ:** đồng bộ `seller_name` và trạng thái seller vào search documents.

**Event xử lý:**
1. `SELLER_PROFILE_UPDATED` -> `_update_by_query` theo `seller_id` để cập nhật `seller_name`.
2. `SELLER_STATUS_CHANGED` ->
   - nếu payload có `visibility_policy = HIDE_LISTINGS`: cập nhật `product_status=HIDDEN` bằng script guard version policy.
   - nếu không có policy này: chỉ cập nhật `seller_status` field (không tự ý đổi trạng thái product).

## 5. Sales Metrics Sync Worker
**Loại hình:** Long-running consumer
**Consumer Group:** `search-sales-metrics-sync`

**Nhiệm vụ:** đồng bộ `product_sold_count` theo `product_id` từ topic metrics chuyên dụng.

**Lý do tách topic:** `t.order.events` partition theo `order_id`, không phù hợp để giữ ordering metrics theo `product_id` ở quy mô lớn.

## 6. Category Tree Sync Worker
**Loại hình:** startup warmer + Pub/Sub listener + periodic self-heal

**Nhiệm vụ:** nạp category tree làm nguồn enrichment cho Product Indexing Worker.

**Nguồn dữ liệu:**
- API nội bộ Product: `GET /api/v1/internal/products/categories/tree`
- Redis Pub/Sub channel: `channel:search:category-refresh`

**Ordering khởi động bắt buộc:**
1. Warm category tree thành công vào RAM + Redis.
2. Chỉ sau đó mới mở consumer `search-product-indexer`.
3. Nếu warm thất bại, service không được vào trạng thái ready.

**Chống stampede toàn cụm:**
- Fallback refresh phải qua lock `SETNX search:lock:category-tree-refresh 1 EX 10`.

### 6.1. Hot Query Refresh Worker (SWR)
**Loại hình:** In-process async worker pool (non-Kafka)

**Nhiệm vụ:** xử lý refresh cho `search:query:{query_hash}` khi cache hit nhưng `valid_until` đã hết hạn.

**Thành phần bắt buộc:**
1. **Singleflight (pod-local):** gom request trùng `query_hash` trong cùng pod.
2. **Refresh Registry (pod-local):** state machine `IDLE -> ENQUEUED -> RUNNING -> IDLE` để chặn enqueue trùng.
3. **Bounded Refresh Queue:** hàng đợi in-memory giới hạn cứng (ví dụ 2,000 tasks/pod).
4. **Distributed Lock (multi-pod):** `SETNX search:lock:refresh:{query_hash} 1 EX 10` trước khi query ES.

**Worker semantics (tránh hiểu sai):**
1. Queue thread-safe dùng destructive `take/pop`: một queue-node chỉ có một worker lấy.
2. Worker khác không lấy cùng queue-node đó.
3. Duplicate logic task chỉ xảy ra nếu enqueue trùng key; đã chặn bằng Registry + CAS.

**Queue full & backpressure policy:**
1. Scheduler dùng `offer` non-blocking.
2. Nếu queue full: rollback registry state về `IDLE`, tăng metric `search_refresh_queue_dropped_total`, không block request path, vẫn trả stale data.
3. Alert khi tỉ lệ drop vượt ngưỡng SLO.
4. Chính sách này là có chủ đích cho Search (stale-tolerant read path). Không dùng policy block/pause như Product Service vì Search ưu tiên giữ latency đọc ổn định và không có ràng buộc write-critical.

**Pod crash behavior:**
1. Task trong queue RAM có thể mất khi pod down/restart.
2. Đây là chấp nhận được với SWR: request kế tiếp tự trigger refresh lại.
3. Thiết kế hiện tại không thêm Redis Streams/Kafka refresh topic để tránh tăng độ phức tạp vận hành.

**Pseudo-flow ngắn:**
1. Request stale gọi `scheduleRefresh(query_hash)`.
2. Singleflight callback thử CAS `IDLE -> ENQUEUED`.
3. Enqueue success -> worker `take()` task, CAS `ENQUEUED -> RUNNING`.
4. Worker lấy distributed lock; lock fail thì kết thúc.
5. Lock success -> query ES -> overwrite cache + `valid_until` mới.
6. `finally`: `RUNNING -> IDLE`.

## 7. Full Reindex Job (Zero-Downtime)
**Loại hình:** manual trigger hoặc scheduled maintenance

**Mục tiêu:** đổi mapping/index settings không downtime.

**Quy trình chuẩn:**
1. Tạo index mới `products_v{N+1}` với settings/mapping mới.
2. Chạy backfill từ Product internal export API:
   - `GET /api/v1/internal/products/export?page={n}&size=500`
3. Upsert bằng script guard `version` để tránh ghi đè dữ liệu realtime mới hơn.
4. Chạy catch-up theo watermark (timestamp/offset) cho đến khi lag về 0.
5. Atomic alias swap:
   - read alias `products` -> `products_v{N+1}`
   - write alias `products_write` -> `products_v{N+1}`
6. Giữ index cũ thêm một cửa sổ an toàn (ví dụ 24h), sau đó mới xóa.

## 8. Search Suggestions Rebuilder
**Loại hình:** Cronjob hàng ngày

**Nguồn dữ liệu:**
- Top keywords từ analytics.
- Category names từ category tree.
- Hot products từ `products` index, sort theo `product_sold_count`.

**Quy trình:**
1. Build `search_suggestions_v{N+1}`.
2. Bulk index dữ liệu gợi ý.
3. Atomic swap alias `search_suggestions`.
4. Warm Redis `search:suggest:{prefix}` cho top prefixes.

## 9. Facet Pre-warmer
**Loại hình:** Cronjob mỗi 5 phút

**Nhiệm vụ:** precompute facets cho category hot.

**Flash sale mode:**
- Khi `is_flash_sale_active=true` cho category, API bypass facet cache và query realtime aggregation để tránh số đếm sai.

## 10. Draft Cleanup Job
**Loại hình:** daily off-peak job

**Nhiệm vụ:** dọn docs DRAFT quá hạn.

**Điều kiện chuẩn:**
- `product_status = DRAFT`
- `created_at < now - 30d`
- `is_deleted = false`

## 11. Elasticsearch Maintenance Jobs

### 11.1. ILM/Rollover & Segment Hygiene
- Không chạy `forcemerge max_num_segments=1` định kỳ trên index nóng.
- Áp dụng ILM rollover theo size/time.
- Chỉ forcemerge cho index đã read-only sau rollover.

### 11.2. Health & Drift Monitor
- Theo dõi: doc count, ingest lag, p95/p99 query latency, refresh backlog.
- Alert nếu:
  - p99 > 100ms kéo dài,
  - projection lag vượt SLO,
  - chênh lệch doc count > 5% so với source snapshot.

### 11.3. DLQ Processor
- Scan `t.search.dlq` theo lịch (5-10 phút/lần).
- Phân loại lỗi: transient/permanent.
- Hỗ trợ replay có kiểm soát theo batch nhỏ, có guard idempotency.
