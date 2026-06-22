# Search Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Events](../product/product_events.md)
*   [Review Service - Events](../review/review_events.md)
*   [Order Service - Events](../order/order_events.md)

---

Search Service là read model thuần túy. Worker chỉ làm 2 việc chính: đồng bộ projection vào Elasticsearch, và vận hành bảo trì an toàn.

**Policy chung cho shared topics (bắt buộc):** mọi worker khi nhận event hợp lệ theo envelope nhưng `event_type` ngoài allowlist của consumer group phải ignore + ACK + ghi metric `search_event_ignored_total{topic,event_type}`.

## Tổng Hợp Workers

| # | Worker | Loại hình | Mô tả |
|---|---|---|---|
| 1 | Product Indexing Worker | Long-running Kafka consumer | Đồng bộ product/SKU/category/review projection |
| 2 | Suggestions Rebuilder | Cronjob hàng ngày | Rebuild autocomplete index |
| 3 | Draft Cleanup Job | Cronjob off-peak | Dọn docs DRAFT quá hạn |
| 4 | Full Reindex Job | Manual trigger / scheduled maintenance | Zero-downtime reindex qua alias swap (3 pha: Build → Catch-up → Cutover) |


## 1. Product Indexing Worker (Kafka Consumer)

**Consumer Group:** `search-product-indexer`
**Topics:** `t.product.events`, `t.category.events`, `t.review.events`

**Nhiệm vụ:** consume events từ product + category + review topics, transform thành document schema chuẩn, ghi vào alias `products_write` và `categories_write`.

**Luồng xử lý chuẩn:**
1. Poll batch Kafka (`max.poll.records` gợi ý: 200-500).
   - Nếu gặp `event_type` không thuộc allowlist của worker này, phải ignore + ACK + ghi metric `search_event_ignored_total{topic,event_type}`.
2. Transform payload → lệnh `_update`/`_update_by_query` theo contract trong [Events](search_events.md).
   - Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`: chuẩn hoá `skus[].status -> skus[].sku_status` trước khi index.
   - Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`: nếu `skus` rỗng/null hoặc thiếu `skus[].best_price`/`skus[].has_promotion`, đánh dấu contract violation và chuyển `t.search.dlq`.
   - Idempotency và chống out-of-order được đảm bảo hoàn toàn bởi guard script trong Elasticsearch.
3. Gọi `_bulk` cho các lệnh cấp SKU, tách riêng `_update_by_query` cho event diện rộng (`PRODUCT_STATUS_CHANGED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED`, `REVIEW_METRICS_UPDATED`).
4. Parse kết quả:
   - Lỗi tạm thời (timeout, 429, 503): không commit offset, để Kafka retry.
   - `document_missing_exception` cho partial update: đẩy `t.search.dlq` để admin replay thủ công (không tự fetch full product).
   - Lỗi dữ liệu không tự hồi phục (mapping conflict/schema poison): gửi `t.search.dlq` kèm payload + metadata.
   - Batch processing phải tiếp tục xử lý hết records còn lại trừ khi gặp transient lỗi mức cụm.
5. Chỉ commit offset khi toàn bộ records trong batch đã được apply thành công.

**Throttle bắt buộc cho diện rộng:**
- `_update_by_query` (PRODUCT_STATUS_CHANGED, CATEGORY_UPDATED, CATEGORY_DELETED, REVIEW_METRICS_UPDATED) phải dùng `requests_per_second=300` + `conflicts=proceed`.

> **Phase 21:** Review events (`REVIEW_METRICS_UPDATED`) đã được gộp vào `search-product-indexer` (cùng group với product/category events). Cơ chế xử lý giữ nguyên: `_update_by_query` theo `product_id` với cùng guard `updated_at > version` script. Admin events đã được loại bỏ hoàn toàn khỏi Search Service (xem ghi chú đầu file).

## 2. Full Reindex Job (Zero-Downtime, 3 pha đơn giản)

**Loại hình:** manual trigger hoặc scheduled maintenance.

**Mục tiêu:** đổi mapping/index settings mà không downtime đọc/ghi.

**Preconditions:**
1. Cụm Elasticsearch xanh (`green`) và dung lượng đĩa đủ cho thêm 1 index full-size + replica.

### Phase 1 - Build

1. Sinh index mới `products_v{N+1}` từ template mapping/settings đã review.
2. Snapshot backfill từ `products_vN` qua scroll/reindex theo chunk (1000-5000 docs/chunk).
3. Ghi checkpoint sau mỗi chunk (`last_page`, `docs_written`, `error_count`) để resume nếu fail.
4. (Tuỳ chọn) Reconciliation pass từ Product export API cho field Product-owned:
   - `GET /api/v1/internal/products/export?page={n}&size=500`.
5. Upsert vào `products_v{N+1}` bằng script guard `version`.

### Phase 2 - Catch-up

1. Chụp watermark `wm_start`: offset từng partition của `t.product.events`, `t.category.events`, `t.review.events`.
2. Chạy consumer group tạm thời `search-reindex-{run_id}` đọc từ `wm_start`.
3. Consumer này **không** ghi qua alias `products_write`, mà ghi trực tiếp vào `products_v{N+1}`.
4. Áp dụng đúng guard idempotency/out-of-order như [Events](search_events.md#4-chiến-lược-idempotency--ordering).
5. Điều kiện hoàn tất catch-up: lag = 0 trong 3 chu kỳ poll liên tiếp, không còn retry backlog, không phát sinh lỗi mới trong 10 phút.

### Phase 3 - Cutover

1. Doc count check: sai số giữa `vN` và `v{N+1}` theo `min(0.5%, 10_000 docs)`.
2. Sample diff theo top hot SKU (random + top 100 products).
3. (Tuỳ chọn) Smoke test các query phổ biến (keyword search, filter, sort, collapse, facets).
4. 1 lệnh `_aliases` atomic duy nhất:
   ```
   POST _aliases
   {
     "actions": [
       { "remove": { "index": "products_vN", "alias": "products" } },
       { "add":    { "index": "products_v{N+1}", "alias": "products" } },
       { "remove": { "index": "products_vN", "alias": "products_write" } },
       { "add":    { "index": "products_v{N+1}", "alias": "products_write" } }
     ]
   }
   ```

**Rollback (không downtime):**
1. Khi phát hiện lỗi nghiêm trọng, swap alias ngược về `vN` bằng 1 lệnh `_aliases` atomic.
2. **Chấp nhận mất tối đa vài phút dữ liệu mới** nếu rollback muộn (không có shadow catch-up).
3. Sau khi metric ổn định (24h), xoá `products_vN` cũ.

> **Use case đặc thù: refresh `admin_name` khi admin đổi tên.** Vì Search Service không subscribe `t.admin.events` (xem ghi chú đầu file), thay đổi tên hiển thị của admin duy nhất cần trigger Full Reindex Job thủ công theo quy trình trên (hoặc incremental update qua API Product Service nếu có). Runbook vận hành phải bao gồm bước này khi admin thay đổi profile.

## 3. Search Suggestions Rebuilder

**Loại hình:** Cronjob hàng ngày.

**Mục tiêu:** tái tạo bộ gợi ý autocomplete chất lượng cao, không gián đoạn API suggest.

**Inputs:**
1. Analytics queries (top query theo traffic/CTR/conversion).
2. Category tree từ alias `categories`.
3. Hot products từ alias `products` (ACTIVE, in-stock).

**Build pipeline:**
1. Tạo `search_suggestions_v{N+1}` theo mapping chuẩn tại [Elasticsearch Schema](search_elasticsearch_schema.md#4-search-suggestions-index-mapping--ranking-contract).
2. Nạp candidates theo từng nguồn, normalize + dedupe + tính `weight`:
   ```
   weight = w_query * query_count_7d + w_ctr * ctr_30d + w_conv * conversion_rate_30d + source_boost
   ```
3. Bulk index theo chunk (1k-5k docs/chunk), retry có backoff cho lỗi 429/503.
4. Validation gate trước swap: tổng số doc >= ngưỡng, không có prefix hot bị rỗng.
5. Atomic alias swap `search_suggestions` → `search_suggestions_v{N+1}`.

**Failure semantics:**
- Fail trước swap: giữ alias cũ, API suggest không bị gián đoạn.
- Fail sau swap nhưng chất lượng kém: rollback alias về version trước.
- Ghi metric: số doc build, merge ratio, error ratio, build duration.

## 4. Draft Cleanup Job

**Loại hình:** daily off-peak job.

**Mục tiêu:** dọn docs DRAFT đã được Product Service chính thức đánh dấu hết hạn (orphaned draft).

**Điều kiện cleanup (đồng thời phải thoả cả 3):**
1. `product_status = DRAFT`
2. `created_at < now - 30d`
3. `is_deleted = false`

**Ràng buộc ownership (bắt buộc — KHÔNG dùng điều kiện trên để tự quyết định):**
- Job **không được tự xoá** chỉ dựa vào tuổi dữ liệu, vì admin có thể vẫn coi đó là bản nháp hợp lệ.
- Cleanup chỉ chạy sau khi nhận được tín hiệu ownership chính thức từ Product Service qua một trong hai cách:
  - **Cách A (ưu tiên):** Sản phẩm đã có event `PRODUCT_DELETED` → field `is_deleted = true` được set trước đó → Job tự động xoá (orphan DELETE) sau 30 ngày tombstone. Khi đó điều kiện (3) sẽ trở thành `is_deleted = true` (thay vì `false`).
  - **Cách B:** Nhận danh sách `product_id` quá hạn từ export API nội bộ của Product Service (`GET /api/v1/internal/products/orphan-drafts?older_than=30d`).
- **Tóm lại:** điều kiện (3) `is_deleted = false` chỉ áp dụng cho **Cách B** (admin vẫn giữ bản nháp nhưng Product Service thông báo quá hạn). Với **Cách A**, điều kiện là `is_deleted = true` đã được set bởi event trước đó.

**Quy trình thực thi:**
1. Gọi `GET /api/v1/internal/products/orphan-drafts?older_than=30d` để lấy danh sách `product_id` đã hết hạn (Cách B).
2. `_update_by_query` theo `product_id IN (...)` với `conflicts=proceed` + `requests_per_second=100` để đánh dấu `is_deleted = true`, `deleted_at = now`.
3. Sau 24h grace period (tách khỏi hot path), Cronjob phụ `tombstone-purger` thực sự `_delete` document.
4. Với docs đã có `is_deleted = true` từ trước (Cách A — từ event `PRODUCT_DELETED` cũ), áp dụng tương tự bước 3 sau 30 ngày tombstone.