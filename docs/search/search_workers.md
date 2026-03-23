# Search Service - Background Workers & Jobs (Extreme Scale)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Workers (Outbox Producer)](../product/product_workers.md#1-outbox-polling-worker-producer)
*   [Product Service - Events Structure](../product/product_events.md)

---

Search Service là **Read Model** thuần túy, không có luồng ghi phức tạp. Toàn bộ Workers phục vụ 2 mục đích: (1) **Đồng bộ dữ liệu** từ Product Service vào Elasticsearch qua Kafka, và (2) **Bảo trì & Tối ưu** Elasticsearch indexes và Redis cache.

---

## Tổng Hợp Kafka Topics & Consumer Groups

| Topic | Consumer Group | Mô tả |
|---|---|---|
| `t.product.events` | `search-product-indexer` | Nhận tất cả Product events → Index vào Elasticsearch |
| `t.product.events` | `search-category-indexer` | Nhận sự kiện thay đổi Danh Mục → Cập nhật denormalized fields |

## 1. Product Indexing Worker (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục (long-running).
**Consumer Group:** `search-product-indexer`

**Sứ mệnh:** Đây là **trái tim** của Search Service. Worker này hứng mọi event từ [Product Service](../product/product_events.md) qua Kafka topic `t.product.events`, transform dữ liệu thành document denormalized, và index vào Elasticsearch. Xem chi tiết xử lý từng event type tại [Search Events](search_events.md#2-chi-tiết-xử-lý-từng-event-type).

**Chi tiết luồng hoạt động:**
1. **Consumer Poll:** Worker gọi `poll(100ms)` lắng nghe events.
2. **Batch Processing:** Gom nhóm events theo `product_id` để tối ưu. Nếu nhận 50 events cho 20 products, chỉ cần 20 Elasticsearch operations (lấy event cuối cùng / mới nhất cho mỗi product).
3. **Phân loại Event & Xử lý:**
  - `PRODUCT_CREATED` / `PRODUCT_UPDATED`: Create/Update sử dụng Document SKU độc lập. `PRODUCT_CREATED` dùng `index/upsert` đầy đủ document; `PRODUCT_UPDATED` dùng script update có guard version. Xem [Search Events 2.1, 2.2](search_events.md#2-chi-tiết-xử-lý-từng-event-type).
     - `PRODUCT_STATE_CHANGED`: Partial update trạng thái theo tất cả các SKU.
  - `PRODUCT_PRICE_UPDATED`: Partial update giá, **cấm `doc_as_upsert`** để tránh tạo document cụt khi out-of-order.
  - `PRODUCT_SKU_STATUS_CHANGED`: Cập nhật `sku_status` + `is_in_stock`, **cấm `doc_as_upsert`**.
  - `PRODUCT_DELETED`: Đánh dấu Xóa (Tombstone qua scripted upsert) thay vì hard delete để tránh lỗi lệch thứ tự event.
4. **Bulk API:** Sử dụng Elasticsearch [Bulk API](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html) để gộp nhiều operations vào 1 request HTTP, giảm round-trip overhead:
   ```
   POST _bulk
   { "index": { "_index": "products", "_id": "prod-001" } }
   { "product_id": "prod-001", "name": "...", ... }
   { "update": { "_index": "products", "_id": "prod-002" } }
   { "script": { "source": "ctx._source.status = params.status", "params": { "status": "HIDDEN" } } }
   { "delete": { "_index": "products", "_id": "prod-003" } }
   ```
5. **Commit Offset:** Sau khi Bulk API trả về success → commit Kafka offset.
6. **Error Handling:**
   - Nếu 1 document trong Bulk bị lỗi (VD: mapping conflict), log error, skip document đó, tiếp tục xử lý các document còn lại.
   - Nếu Elasticsearch cluster unreachable → nack message → Kafka auto-retry.
   - Sau 5 retries liên tiếp → push event lên Dead Letter Topic `t.search.dlq` → Alert.

**Scaling:** Tăng số partition của `t.product.events` + tăng số consumer instances. Mỗi instance xử lý subset partitions. Consumer Group đảm bảo không trùng lặp.

> **Throughput ước tính:** Với Bulk API kích thước 100 docs, Elasticsearch cluster 3 shards, throughput đạt **~5,000 documents/giây/consumer thread**.

## 2. Category Denormalization Worker
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `search-category-indexer`

**Sứ mệnh:**
Đọc Kafka Topic `t.product.events` (hoặc `t.category.events`) chứa các sự kiện liên quan tới Category (`CATEGORY_UPDATED`, v.v...). Khi một danh mục (Category) thay đổi tên, hoặc bị chuyển Hierarchy đường dẫn, worker này có nhiệm vụ quét và cập nhật lại tất cả Product Documents thuộc Category đó trong Elasticsearch để tránh sai lệch dữ liệu nội bộ ES (tên category cũ vẫn dính ở product).

**Chi tiết luồng hoạt động:**
1. Tiêu thụ message `CATEGORY_UPDATED`.
2. Gắn kèm xử lý bằng `_update_by_query` để force tất cả product có `category.id` = `updated_id` đổi tên hiển thị.
```json
POST products_write/_update_by_query
{
  "query": { "term": { "category.id": "<ID_DANH_MUC>" } },
  "script": {
    "source": "if (ctx._source.category_version != null && params.version <= ctx._source.category_version) { ctx.op = 'none'; return; } ctx._source.category.name = params.new_name; ctx._source.category_path = params.new_path; ctx._source.category_version = params.version;",
    "lang": "painless",
    "params": {
      "new_name": "...",
      "new_path": "...",
      "version": <version_của_event_category>
    }
  }
}
```
*Lưu ý (Sửa lỗi Update-By-Query Race Condition Kinh Điển):* Do `_update_by_query` quét qua hàng triệu bản ghi theo một vòng lặp batch cực lớn, nên trong lúc nó đang chạy giữa chừng, rất có thể 1 Event thay đổi thông tin Của Chính Sản Phẩm (Ví dụ `PRODUCT_UPDATED`) lại bay vào. Lúc này, nảy sinh Race Condition (Ai chiến thắng đè Data). CẤM TUYỆT ĐỐI ghi đè `name/path` một cách vô điều kiện. Phải dùng chung một tham số `version` hoặc `timestamp` từ Category Update Event, và lưu lại cột `category_version` vào trong chính Document Product lúc Update-By-Query. Đồng thời cần có cơ chế Retry tự động nếu Elasticsearch ném ra Version Conflict (`aborted` trong response json) đối với các Document bị Update giành giật.
*Lưu ý thêm:* `_update_by_query` thao tác rất nặng, nên có Rate Limiting (hoặc throttle bằng `requests_per_second` trong cURL của Elastic) để không làm lag Search Cluster.

## 3. Category Tree Sync Worker
**Loại hình:** Cache Warmer chạy khi startup + Pub/Sub listener.

**Sứ mệnh:** Đồng bộ cây danh mục (category tree) từ Product Service vào bộ nhớ local của Search Service. Dữ liệu này được Worker #1 dùng để enrichment (`category_path`, `category_name`) khi index sản phẩm.

**Chi tiết luồng hoạt động:**
1. **Khi Service khởi động:** Lập tức gọi Product Service API `GET /api/v1/products/categories/tree` (internal API). Lưu vào RAM (HashMap) + Redis (`search:categories:tree`).
2. **Sửa lỗi Race Condition (Cold Start Circular Dependency):** Rất dễ xảy ra lỗi nếu `Product Indexing Worker` bắt đầu poll Event Kafka TRƯỚC KHI cây danh mục này được tải xong. Cần quy định **Ordered Startup** trong Application Lifecycle: Worker số `3` này PHẢI hoàn tất lượt Warm-up Cache Đầu tiên thành công thì mới phát cờ `Readiness` nội bộ cho Worker số `1` bắt đầu start Kafka Consumer. Nếu tải cây danh mục thất bại, Search Service không được phép Khởi động.
3. **Lắng nghe Pub/Sub:** Subscribe kênh `channel:product:cache:evict` từ Product Service.
4. Khi nhận message invalidate → re-fetch cây danh mục mới → update RAM + Redis. Nếu cache bị Miss (chưa update), Worker số 1 BẮT BUỘC dùng cơ chế Singleflight (chỉ 1 Worker gọi API Product, sau đó broadcast RAM) để Fallback; nếu không sẽ dính Thundering Herd gây DDoS Product Service chết ngay lập tức.
5. **Cross-Pod Singleflight:** Singleflight trong RAM chỉ chặn trên từng Pod. Để chặn stampede toàn cụm, fallback category tree phải kèm lock phân tán Redis `SETNX search:lock:category-tree-refresh 1 EX 10`. Pod trượt lock dùng stale cache hoặc chờ Pub/Sub refresh.

## 4. Full Reindex Job (Zero-Downtime)
**Loại hình:** Manual trigger hoặc Scheduled Job (VD: chạy hàng tháng hoặc khi đổi mapping).

**Sứ mệnh:** Rebuild toàn bộ Elasticsearch index từ đầu, **không gây gián đoạn** traffic đang phục vụ.

**Chi tiết luồng hoạt động (Blue-Green Reindex):**
1. **Tạo Index mới:** `PUT products_v{N+1}` với mapping mới.
2. **Dual-Write (Ghi song song):** Cập nhật cấu hình Feature Flag / ENV Variable của Product Indexing Worker (Ví dụ: `INDEX_DUAL_WRITE_TARGET=products_v{N+1}`) để báo hiệu cho Worker ghi đồng thời mọi events mới vào cả 2 Index (`products_v{N}` và `products_v{N+1}`). Đảm bảo Index mới luôn có thay đổi real-time. (Elasticsearch Alias không hỗ trợ ghi song song vào nhiều index, nên bắt buộc xử lý tại Application Level).
3. **Scan & Index (Đổ Data quá khứ):** 
   - Gọi Product Service internal API `GET /api/v1/products/export?page={n}&size=500` (paginated).
   - Flag payload Reindex bằng tham số `_source._is_reindex_job = true`.
   - Mở Cờ Dual-Write trên Redis (`SET search:feature:dual_write_target products_v{N+1}`). Đồng thời hệ thống quản lý Reindex phải publish lệnh `Pub/Sub channel:search:config:reload` tới tất cả các Pods.
   - **Sửa lỗi Race Condition (Pod Sync Confirmation):** BỎ NGAY tư duy dùng "sleep 5-10 giây" mù quáng để chờ Pod cập nhật. Hệ thống phải thực hiện **Health-check đồng bộ**: Yêu cầu tất cả các Pods đẩy tín hiệu ACK trạng thái cập nhật cờ Dual-Write vào Redis Hash `search:config:dual_write_acks`. Master Job sẽ liên tục loop kiểm tra `HLEN search:config:dual_write_acks == Tổng_số_Pods_đang_hoạt_động` rồi mới được kích hoạt bước Scan & Index. Nếu sau 30 giây không thể sync đủ Pod, Job sẽ báo lỗi vòng lặp và Abort quá trình Reindex để ngăn chặn vĩnh viễn sai lệch dữ liệu. Mọi Event lỗi ghi tại đây (nếu Cluster v2 đang quá tải) không được phép bỏ qua, nều write failed ở một trong 2 Index thì event đó sẽ bị đẩy vào DLQ để nhân viên can thiệp hoặc Retry. Cập nhật này sẽ ngăn chặn các pod cũ ghi lệch vào v1.
3. **Scan & Index (Đổ Data quá khứ):** 
   - Gọi Product Service internal API `GET /api/v1/products/export?page={n}&size=500` (paginated). Lấy về `product_id` kèm theo `updated_at_us` (Microseconds).
   - **Sửa lỗi Race Condition Ghi Đè Bản Cũ (Missing Version Check):** Bắt buộc sử dụng Upsert Script với logic Versioning (với `version` bằng `updated_at_us`). Nếu Script không có version check, dữ liệu tĩnh từ Job Scan (bản siêu cũ) sẽ chèn lên dữ liệu Real-time (vừa được Dual-Write ghi thành công bên trên).
   - **Painless Script Yêu cầu:**
     ```json
     {
       "script": {
         "source": "if (ctx._source.updated_at_us != null && params.updated_at_us <= ctx._source.updated_at_us) { ctx.op = 'none'; return; } ctx._source = params.doc_source;",
         "params": {
           "updated_at_us": <timestamp_từ_DB>,
           "doc_source": { <product_mapping> }
         }
       },
       "upsert": { <product_mapping_kèm_updated_at_us> }
     }
     ```
4. **Swap Alias:**
   ```json
   POST _aliases
   {
     "actions": [
       { "remove": { "index": "products_v{N}", "alias": "products" } },
       { "add": { "index": "products_v{N+1}", "alias": "products" } },
       { "remove": { "index": "products_v{N}", "alias": "products_write" } },
       { "add": { "index": "products_v{N+1}", "alias": "products_write" } }
     ]
   }
   ```
   > **Atomic Alias Swap:** Elasticsearch thực hiện tất cả alias actions trong 1 atomic operation. Traffic chuyển sang index mới ngay lập tức, không downtime.
5. **Dừng Dual-Write & Cleanup:** Tắt flag ghi song song ở Worker. Sau khi xác nhận index mới hoạt động tốt, delete index cũ `products_v{N}`.

## 4. Search Suggestions Rebuilder
**Loại hình:** Cronjob chạy hàng ngày (VD: 5:00 AM).

**Sứ mệnh:** Rebuild index `search_suggestions` với dữ liệu mới nhất. Nguồn dữ liệu:
- **Popular keywords**: Lấy từ search analytics (query log).
- **Category names**: Lấy từ Category Tree.
- **Hot products**: Top 10,000 sản phẩm theo `sold_count` từ `products` index.

**Chi tiết luồng hoạt động:**
1. Tạo index mới `search_suggestions_v{N+1}`.
2. Query products index: `GET products/_search` sorted by `sold_count DESC`, top 10k.
3. Query search analytics (nếu có): top 5k popular search queries.
4. Bulk index vào `search_suggestions_v{N+1}`.
5. Swap alias `search_suggestions` từ old → new.
6. Delete old index.
7. **Warm Redis Cache:** Pre-populate `search:suggest:{prefix}` cho top 100 common prefixes.

## 5. Facet Pre-warmer (Cronjob)
**Loại hình:** Cronjob chạy mỗi 5 phút (Normal) / Realtime tính toán (Flash Sale).

**Sứ mệnh:** Pre-compute facets (bộ lọc sidebar) cho các Category page truy cập cao nhất. Tránh Elasticsearch phải chạy aggregation query mỗi lần user vào trang danh mục.

**Chi tiết luồng hoạt động:**
1. Lấy danh sách top 50 categories từ `search:categories:tree` (theo `product_count` giảm dần).
2. Chạy parallel Elasticsearch aggregation queries cho mỗi category:
   ```json
   {
     "size": 0,
     "query": {
       "bool": {
         "filter": [
           { "term": { "status": "ACTIVE" } },
           { "prefix": { "category_path": "dien-tu/dien-thoai" } }
         ]
       }
     },
     "aggs": {
       "brands": { ... },
       "price_ranges": { ... },
       "dynamic_attributes": { ... }
     }
   }
   ```
3. Đẩy kết quả vào Redis: `SET search:facets:{category_id} json EX 300`.
4. **Sửa lỗi Stale Facets (Giải pháp cho Flash Sale):** Cronjob 5 phút là quá chậm trong các kỳ Mega Sale khi Inventory rớt theo giây. Do đó, hệ thống trang bị cơ chế **Phân tầng Facet (Layered Faceting):** Application API sẽ đọc cờ `is_flash_sale_active` trên Redis. Nếu cờ này TRUE, nó sẽ Bypass (bỏ qua) Facet Cache và yêu cầu Elasticsearch chạy realtime Aggregation trên tập data hiện tại (chỉ apply cho category nào đang chạy flash sale), đánh đổi một chút CPU lấy tính chính xác tuyệt đối của bộ đếm (ngăn chặn tình hiển thị sai số liệu lượng hàng trong Filter Sidebar).

## 6. Elasticsearch Maintenance Jobs

### 6.1. Force Merge (Weekly)
**Loại hình:** Scheduled Job chạy hàng tuần (VD: Chủ nhật 3:00 AM).
```
POST products/_forcemerge?max_num_segments=1
```
Hợp nhất Lucene segments để tối ưu read performance. Chỉ chạy lúc thấp tải vì tốn I/O.

### 6.2. Index Stats Monitor (Continuous)
**Loại hình:** Healthcheck liên tục.
- Monitor: Document count, index size, query latency (p50, p95, p99).
- Alert nếu: query p99 > 100ms, index size vượt ngưỡng, hoặc document count chênh lệch > 5% so với Product Service (phát hiện data drift).

### 6.3. Dead Letter Queue Processor
**Loại hình:** Cronjob mỗi 10 phút.
- Scan topic `t.search.dlq`.
- Alert SysAdmin nếu có messages mới.
- Dashboard cho phép manual retry hoặc dismiss.
