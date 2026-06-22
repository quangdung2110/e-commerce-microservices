# Search Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)

**Cross-Service References:**
*   [Product Service - Architecture Recommendations](../product/product_architecture_recommendations.md)
*   [Order Service - Workflows](../order/order_workflow.md)

---

Tài liệu này tổng hợp các quyết định kiến trúc quan trọng cho Search Service.

## 1. CQRS với Elasticsearch — Read Model thuần túy

**Quyết định:** Search Service là **Read Model** thuần túy trong kiến trúc CQRS. Không sở hữu PostgreSQL; Elasticsearch là nguồn dữ liệu duy nhất.

**Lý do:**
- PostgreSQL cực kỳ tệ cho Full-text search trên hàng triệu sản phẩm (LIKE '%keyword%' = Full Table Scan).
- Elasticsearch sử dụng Inverted Index → < 50ms cho mọi truy vấn.
- Tách biệt Read/Write cho phép scale độc lập. Search traffic (hàng triệu End-User) không ảnh hưởng Write throughput (hàng nghìn admin).

**Trade-off chấp nhận:** Eventual Consistency. Dữ liệu trên Elasticsearch có thể trễ 1-3 giây so với PostgreSQL. Đây là trade-off chấp nhận được vì:
- User browsing sản phẩm không cần realtime accuracy.
- Các luồng cần realtime (Cart add, Order checkout) gọi trực tiếp [Product Service Point-Read API](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh), không qua Search.

## 2. Denormalization & Pre-computation

**Quyết định:** Denormalize tất cả dữ liệu thành flat document. Pre-compute các field phức tạp (`best_price`, `is_in_stock`, `has_promotion`) tại thời điểm indexing.

**Lý do:**
- Field Collapsing kết hợp với Composite array trong Elasticsearch giúp tránh thảm hoạ OOM, nhanh hơn rất nhiều so với dính líu (join) lúc query với kiểu nested.
- Pre-computed boolean fields (`is_in_stock`, `has_promotion`) biến filter phức tạp thành simple term filter → tận dụng Elasticsearch filter cache.
- `best_price` được tính trước cho phép sort by price rẻ nhất mà không cần dựa vào logic runtime array.

## 4. Vietnamese Language Support

**Quyết định:** Sử dụng ICU Tokenizer + ICU Folding thay vì Standard Tokenizer.

**Lý do:**
- Tiếng Việt có dấu: "Điện thoại" phải match "dien thoai".
- ICU Tokenizer xử lý Unicode chính xác cho ngôn ngữ Đông Nam Á.
- ICU Folding chuẩn hoá accent marks, cho phép tìm kiếm không dấu.
- Custom stop words tiếng Việt loại bỏ noise ("và", "của", "là"...).

## 5. Autocomplete với Edge N-gram

**Quyết định:** Sử dụng **2 lớp autocomplete**:
1. **Dropdown suggestion (chính):** Index riêng `search_suggestions_v1` với `suggest_index_analyzer` (Edge N-gram) — pre-compute ranking từ analytics. Phục vụ dropdown search-as-you-type qua endpoint `/api/v1/search/suggest`. Đây là cách chính thức cho autocomplete.
2. **Inline search filter (dự phòng):** Field `name.autocomplete` trên products index dùng `autocomplete_analyzer` (Edge N-gram) — dự phòng cho trường hợp muốn dùng main search làm autocomplete (multi_match kèm `name.autocomplete^2`).

**Lý do 2 lớp:**
- Search Suggestions index: ranking pre-compute, response cực nhanh (<25ms), hỗ trợ đa nguồn (analytics, hot products, categories).
- `name.autocomplete` trên products index: fallback nhanh, không cần query index riêng, nhưng tốn disk và indexing time hơn.

**Lý do Edge N-gram:**
- Linh hoạt hơn Completion Suggester: hỗ trợ fuzzy match, partial word match, scoring.
- ES query trực tiếp với p99 < 25ms là đủ nhanh, không cần cache thêm.

## 6. Single Timestamp Guard (đơn giản hoá)

**Quyết định:** Đa số event dùng **1 field `version`** duy nhất làm guard ordering. Riêng `SKU_CREATED` dùng `created_at` (ghi vào cùng field `version`).

**Lý do:**
- Đơn giản hoá đáng kể logic worker — không phải track nhiều sequence field cho cùng một document.
- Trade-off: metrics update (rating/sold) có thể bị skip nếu `updated_at` trùng millisecond. Chấp nhận được vì:
  - Kafka đảm bảo ordering trong cùng partition.
  - Search projection chấp nhận độ trễ vài giây cho metrics.

## 7. 2 Consumer Group Chính + 1 Tạm (đơn giản hoá qua Phase 21)

**Quyết định:** **2 Kafka consumer group chính** (giảm từ 3 xuống 2 tại Phase 21 — gộp review metrics vào product-indexer) + **1 consumer group tạm** dùng riêng cho Full Reindex Job.

| Group | Loại | Topics | Events |
|---|---|---|---|
| `search-product-indexer` | Chính | `t.product.events`, `t.category.events`, `t.review.events` | Product + category + review events |
| `search-dlq-processor` | Chính | `t.search.dlq` | Failed envelopes (parking lot) |
| `search-reindex-{run_id}` | Tạm (chỉ chạy khi Full Reindex) | `t.product.events`, `t.category.events`, `t.review.events` | Catch-up vào index mới |

**Lý do:**
- 2 group chính hiện tại (sau Phase 21): `search-dlq-processor` là consumer riêng của Search Service cho kênh DLQ — không thể gộp vào group khác vì topic và cơ chế xử lý (replay thủ công) hoàn toàn tách biệt. Gộp vào `search-product-indexer` sẽ khiến message bị nuốt hoặc xử lý nhầm.
- Giảm từ 4 group chính xuống 3 ban đầu bằng cách gộp `search-product-indexer` (product + category); Phase 21 tiếp tục gộp review metrics vào `search-product-indexer` (chỉ còn 1 event type `REVIEW_METRICS_UPDATED`, không cần tách riêng) **đồng thời loại bỏ hoàn toàn** group admin (hệ thống 1 admin duy nhất, không cần `_update_by_query theo admin_id`) — tổng còn 2 nhóm chính.
- Group tạm `search-reindex-{run_id}` chỉ tồn tại trong thời gian Full Reindex Job chạy, dùng để catch-up event vào index mới mà không ghi qua alias `products_write`.
- **Không subscribe `t.admin.events`:** Hệ thống chỉ có 1 admin duy nhất (toàn quyền, không bao giờ bị ban) nên không cần `_update_by_query theo admin_id` + 2 guard field `admin_*_version`. Trade-off: khi admin đổi tên hiển thị, phải trigger Full Reindex Job thủ công (xem [Workers §2](search_workers.md#2-full-reindex-job-zero-downtime-3-pha-đơn-giản)).
- Trade-off: handler file lớn hơn, nhưng logic dispatch theo `event_type` đã đơn giản (1 script PATCH chung cho đa số event + 1 script đặc thù cho `SKU_CREATED`).

## 8. 1 Topic DLQ (đơn giản hoá)

**Quyết định:** Chỉ dùng `t.search.dlq`. Bỏ `t.search.retry` (self-heal fetch full product).

**Lý do:**
- `document_missing_exception` rất hiếm trong thực tế.
- Khi xảy ra, đẩy thẳng DLQ + cảnh báo để admin replay thủ công.
- Trade-off: không có automatic recovery cho partial update miss, nhưng số lượng miss đủ nhỏ để xử lý thủ công.

## 9. 3-Pha Reindex (đơn giản hoá)

**Quyết định:** Reindex chỉ 3 pha (Build → Catch-up → Cutover), không có shadow catch-up.

**Lý do:**
- Phase đầy đủ (6 pha + shadow catch-up) quá phức tạp cho tần suất thay đổi schema thực tế.
- Trade-off: rollback sau cutover > 30 phút có thể mất vài phút dữ liệu mới. Chấp nhận được.

## 10. Search Relevance Tuning (lộ trình tương lai)

**Vấn đề:** Default scoring (`_score` = BM25) không đủ tốt cho E-Commerce. User tìm "áo thun" mong đợi sản phẩm phù hợp lên top (relevance + rating).

**Đề xuất:** Function Score Query kết hợp text relevance với business metrics:
```json
{
  "function_score": {
    "query": { "multi_match": { "query": "áo thun", "fields": ["name^3", "description"] } },
    "functions": [
      { "field_value_factor": { "field": "product_rating_avg", "modifier": "sqrt", "factor": 2 } },
      { "filter": { "term": { "has_promotion": true } }, "weight": 1.2 }
    ],
    "score_mode": "sum",
    "boost_mode": "multiply"
  }
}
```

## 11. Synonyms & Typo Handling (lộ trình tương lai)

**Vấn đề:** User gõ "dt" (viết tắt "điện thoại"), "fone" (sai spelling), "Samsung S24" (thiếu "Galaxy").

**Đề xuất:**
- **Synonym Filter** trong analyzer với file trên ConfigMap cho phép hot-reload.
- **Fuzziness AUTO** đã có trong [Search Workflow](search_workflow.md#2-api-tìm-kiếm-sản-phẩm-full-text-search--filters--facets) — cho phép 1-2 ký tự typo.

## 12. Multi-Source Data Integration

| Data Field | Source Service | Topic | Ghi chú |
|---|---|---|---|
| `product_rating_avg`, `product_rating_count` | Review Service | `t.review.events` | Realtime (denormalize qua `_update_by_query` theo `product_id`) |
| `admin_name` | Product Service (lúc tạo product) | `t.product.events` (`PRODUCT_CREATED` payload) | Snapshot 1 lần lúc index. Khi admin đổi tên → Full Reindex Job. |


**Fallback vận hành:** Nếu một trong các consumer realtime bị disable, cho phép batch sync tạm thời, nhưng phải công bố SLO freshness rõ ràng.

## 13. Tối Ưu Hiệu Năng Vận Hành

1. **Adaptive Refresh Interval (theo index):**
   - **Products (`products`):** mặc định `refresh_interval=1s` (cần realtime cho browsing). Khi ingest spike lớn (đợt import hàng loạt), tăng tạm lên `3s-5s` để tăng throughput indexing, rồi trả về `1s` sau khi ổn định.
   - **Categories (`categories`):** cố định `refresh_interval=5s` (tree ít thay đổi, không cần realtime).
   - **Suggestions (`search_suggestions`):** cố định `refresh_interval=5s` (rebuild daily là đủ).
   - Xem chi tiết settings tại [Elasticsearch Schema §2.1, §3.1, §4.1](search_elasticsearch_schema.md#21-index-settings).
2. **ES Query Cache:** Tune `indices.queries.cache.size: 15%` heap để tận dụng filter cache tự động.
3. **ILM + Rollover:** Thay vì force-merge định kỳ trên index nóng.

## Tổng Kết

Search Service được thiết kế là một **stateless Read-Only projection** của Product Service data, với triết lý **đơn giản hoá tối đa**:

- **Elasticsearch** là "database" duy nhất — chịu 100% traffic đọc và ghi.
- **Không có Redis, không có cache layer, không có refresh worker pool.**
- **Kafka consumer** (2 group chính + 1 tạm cho reindex) làm cầu nối dữ liệu — đảm bảo eventual consistency với Source of Truth.
- **1 script PATCH chung** cho đa số event + **1 guard field `version`** (timestamp ISO 8601; `updated_at` cho đa số event, `created_at` cho `SKU_CREATED`).
- **Zero-downtime operations** — 3-pha reindex qua alias swap.
- **Không subscribe `t.admin.events`** — hệ thống 1 admin duy nhất, `admin_name` snapshot lúc index.
