# Search Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Architecture Recommendations](../product/product_architecture_recommendations.md)
*   [Order Service - Workflows](../order/order_workflow.md)

---

Tài liệu này tổng hợp các quyết định kiến trúc quan trọng và các khuyến nghị nâng cao cho Search Service. Một số đã được tích hợp vào thiết kế, một số là lộ trình tương lai.

## 1. ✅ CQRS với Elasticsearch (Đã Thiết Kế)

**Quyết định:** Search Service là **Read Model** thuần túy trong kiến trúc CQRS. Không sở hữu PostgreSQL — Elasticsearch là nguồn dữ liệu duy nhất.

**Lý do:**
- PostgreSQL cực kỳ tệ cho Full-text search trên hàng triệu sản phẩm (LIKE '%keyword%' = Full Table Scan).
- Elasticsearch sử dụng Inverted Index → < 10ms cho mọi truy vấn.
- Tách biệt Read/Write cho phép scale độc lập. Search traffic (hàng triệu End-User) không ảnh hưởng Write throughput (hàng nghìn admin).

**Trade-off chấp nhận:** Eventual Consistency. Dữ liệu trên Elasticsearch có thể trễ 1-3 giây so với PostgreSQL. Đây là trade-off chấp nhận được vì:
- User browsing sản phẩm không cần realtime accuracy.
- Các luồng cần realtime (Cart add, Order checkout) gọi trực tiếp [Product Service Point-Read API](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh), không qua Search.

## 2. ✅ Denormalization & Pre-computation (Đã Thiết Kế)

**Quyết định:** Denormalize tất cả dữ liệu thành flat document. Pre-compute các field phức tạp (`best_price`, `is_in_stock`, `has_promotion`) tại thời điểm indexing.

**Lý do:**
- Field Collapsing kết hợp với Composite array trong Elasticsearch giúp tránh thảm hoạ OOM, nhanh hơn rất nhiều so với dính líu (join) lúc query với kiểu nested.
- Pre-computed boolean fields (`is_in_stock`, `has_promotion`) biến filter phức tạp thành simple term filter → tận dụng Elasticsearch filter cache.
- `best_price` được tính trước cho phép sort by price rẻ nhất mà không cần dựa vào logic runtime array.

## 3. ✅ Vietnamese Language Support (Đã Thiết Kế)

**Quyết định:** Sử dụng ICU Tokenizer + ICU Folding thay vì Standard Tokenizer.

**Lý do:**
- Tiếng Việt có dấu: "Điện thoại" phải match "dien thoai".
- ICU Tokenizer xử lý Unicode chính xác cho ngôn ngữ Đông Nam Á.
- ICU Folding chuẩn hoá accent marks, cho phép tìm kiếm không dấu.
- Custom stop words tiếng Việt loại bỏ noise ("và", "của", "là"...).

## 4. ✅ Autocomplete với Edge N-gram (Đã Thiết Kế)

**Quyết định:** Sử dụng Edge N-gram tokenizer cho `name.autocomplete` field thay vì Completion Suggester.

**Lý do:**
- Edge N-gram linh hoạt hơn Completion Suggester: hỗ trợ fuzzy match, partial word match, scoring.
- Completion Suggester nhanh hơn nhưng quá cứng nhắc (chỉ prefix exact match).
- Kết hợp với Redis cache (`search:suggest:{prefix}`) → latency < 5ms cho autocomplete.

## 5. Search Relevance Tuning (Lộ Trình Tương Lai)

**Vấn đề:** Default scoring (`_score` = TF-IDF/BM25) không đủ tốt cho E-Commerce. User tìm "áo thun" mong đợi sản phẩm bán chạy nhất lên top, không phải sản phẩm có từ "áo thun" xuất hiện nhiều nhất.

**Đề xuất:**
- **Function Score Query:** Kết hợp text relevance với business metrics:
  ```json
  {
    "function_score": {
      "query": { "multi_match": { "query": "áo thun", "fields": ["name^3", "description"] } },
      "functions": [
        {
          "field_value_factor": {
            "field": "product_sold_count",
            "modifier": "log1p",
            "factor": 0.5
          }
        },
        {
          "field_value_factor": {
            "field": "product_rating_avg",
            "modifier": "sqrt",
            "factor": 2
          }
        },
        {
          "filter": { "term": { "has_promotion": true } },
          "weight": 1.2
        }
      ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  }
  ```
- **Personalization (Phase 2):** User-specific scoring dựa trên lịch sử mua, category preference, location → cần thêm User Profile Service integration.

## 6. Synonyms & Typo Handling (Lộ Trình Tương Lai)

**Vấn đề:** User gõ "dt" (viết tắt "điện thoại"), "fone" (sai spelling), "Samsung S24" (thiếu "Galaxy").

**Đề xuất:**
- **Synonym Filter** trong analyzer:
  ```json
  {
    "filter": {
      "vi_synonyms": {
        "type": "synonym",
        "synonyms": [
          "dt, điện thoại, phone, fone",
          "laptop, máy tính xách tay, notebook",
          "tai nghe, headphone, earphone, earbud"
        ]
      }
    }
  }
  ```
- **Synonym File trên S3/ConfigMap:** Cho phép update synonym list mà không cần reindex. Dùng `synonyms_path` trỏ tới file trên filesystem, kết hợp Kubernetes ConfigMap hot-reload.
- **Fuzziness AUTO** đã thiết kế trong [Search Workflow](search_workflow.md#2-api-tìm-kiếm-sản-phẩm-full-text-search--filters--facets) — cho phép 1-2 ký tự typo.

## 7. Scaling Elasticsearch Cluster

**Cấu hình khuyến nghị theo quy mô:**

| Quy mô | Shard/Index | Replica | Node | RAM/Node |
|---|---|---|---|---|
| < 1M sản phẩm | 3 | 1 | 3 Data + 3 Master | 16GB |
| 1-10M sản phẩm | 5 | 1 | 5 Data + 3 Master | 32GB |
| > 10M sản phẩm | 10+ | 2 | 10+ Data + 3 Master | 64GB |

**Best Practices:**
- **Shard sizing:** Mỗi shard nên chứa 10-50GB data. Quá nhỏ (< 1GB) tạo overhead, quá lớn (> 50GB) chậm recovery.
- **Dedicated Master Nodes:** 3 master-eligible nodes riêng, không chạy data, tránh split-brain.
- **JVM Heap:** Cấp 50% RAM cho JVM heap (tối đa 31GB để tận dụng Compressed Oops). 50% còn lại cho filesystem cache.
- **Index per version:** Pattern `products_v1`, `products_v2` + alias swap cho zero-downtime schema changes. Xem [Full Reindex Job](search_workers.md#7-full-reindex-job-zero-downtime).

## 8. Multi-Source Data Integration

**Trạng thái tích hợp:**

| Data Field | Source Service | Topic | Ghi chú |
|---|---|---|---|
| `product_rating_avg`, `product_rating_count` | Review Service | `t.review.events` | Realtime qua `REVIEW_METRICS_UPDATED` |
| `product_sold_count` | Order Metrics Projector | `t.search.metrics.events` | Realtime aggregate theo `product_id` |

**Fallback vận hành:** Nếu một trong các consumer realtime bị disable, cho phép batch sync tạm thời, nhưng phải công bố SLO freshness rõ ràng (ví dụ <= 15 phút) để tránh hiểu nhầm dữ liệu realtime.

## 9. Tối Ưu Hiệu Năng Vận Hành (Khuyến nghị triển khai)

1. **Adaptive Refresh Interval:**
  - Tải thấp: `refresh_interval=1s`.
  - Tải cao/đợt import lớn: tăng tạm lên `3s-5s` để tăng throughput indexing.
2. **Bounded Async Refresh Queue cho hot-query cache:**
  - Không spawn goroutine vô hạn khi stale-hit đồng loạt.
  - Dùng queue hữu hạn + distributed lock để giữ ES ổn định ở p99.
3. **Cache Admission theo tần suất thực:**
  - Chỉ cache query vượt ngưỡng hot (`> 50 req/phút`).
  - Tránh cache pollution cho long-tail queries.
4. **ILM + Rollover thay vì force-merge định kỳ trên index nóng:**
  - Giảm IO spike, giảm ảnh hưởng latency query trong giờ cao điểm.
5. **Projection observability chuẩn:**
  - Theo dõi `consumer lag`, `projection freshness`, `DLQ volume`, `refresh queue depth`.
  - Tạo alert theo SLO để phát hiện drift sớm.

---

## Tổng Kết

Search Service được thiết kế là một **stateless Read-Only projection** của Product Service data. Kiến trúc xoay quanh:
- **Elasticsearch** làm "database" duy nhất — tối ưu cho tìm kiếm denormalized.
- **Redis** làm hot query cache — giảm tải cho Elasticsearch during traffic spikes.
- **Kafka consumer** làm cầu nối dữ liệu — đảm bảo eventual consistency với Source of Truth (Product Service PostgreSQL).
- **Zero-downtime operations** — alias swap cho reindex, TTL-based cache cho invalidation.
