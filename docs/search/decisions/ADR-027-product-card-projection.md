---
doc_id: search-adr-027
service: search
status: accepted
date: 2026-10-16
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - docs/performance/search-scale-benchmark.md
  - docs/search/schema/product_cards.index.json
  - java/services/search-service/src/main/java/com/ecommerce/search/worker/productcards/ProductCardProjector.java
  - java/services/search-service/src/main/java/com/ecommerce/search/service/ProductCardQueryBuilder.java
  - java/services/search-service/src/main/java/com/ecommerce/search/service/ProductSearchService.java
  - loadtest/search-bench/compare.py
  - loadtest/search-bench/projection_check.py
---

# ADR-027 — Projection cấp Product `product_cards` cho truy vấn storefront

> **Cập nhật 19/10/2026:** [ADR-029](./ADR-029-required-product-card-read-model.md) thay chính sách
> pilot/mặc định tắt/fallback, cách cutover và xóa generation cũ dưới đây. Projection nay bắt buộc
> cho request mới; full reindex dựng cards trước rồi chuyển alias cùng SKU/Category. Các quyết
> định mapping, query semantics và benchmark lịch sử trong ADR này vẫn có giá trị.

## Status

`Accepted`, triển khai dạng **pilot sau cờ** `search.product-index.enabled` (mặc định tắt). Mở rộng
[ADR-020](./ADR-020-product-cards-with-cheapest-eligible-sku.md): giữ nguyên mọi quy tắc nghiệp vụ của ADR-020, chỉ thay cách
thực thi khi cờ bật. Không thay ADR-020; khi cờ tắt hoặc projection chưa khớp, ADR-020 vẫn là đường phục vụ.

## Context

ADR-020 thực thi "một Product một card, SKU rẻ nhất thỏa bộ lọc" bằng cách mỗi request quét **toàn bộ** Product khớp
(composite aggregation 500 bucket một lần, kèm `top_hits`) rồi sort và cắt trang trong Search. ADR-020 tự ghi chi phí O(P) và
điều kiện: "nếu chi phí không đạt SLO, thiết kế Product projection chứa nested SKU cần ADR/migration riêng".

[Benchmark quy mô catalog](../../performance/search-scale-benchmark.md) đo được:

- Khoảng 0,1 ms cho mỗi Product khớp, dù trang chỉ trả 8 hay 20 card.
- Ngân sách 5 giây hết ở khoảng 45 000 Product khớp khi máy rảnh, và chỉ vài nghìn khi có tải đồng thời.
- Trang chủ (hai truy vấn `in_stock=false` quét toàn catalog) lỗi 100% với 16 người xem từ catalog 10 000 Product.
- Elasticsearch bão hoà CPU ngay từ 10 000 Product.

Điều kiện của ADR-020 đã xảy ra ở mức budget timeout.

Index SKU (`products`) là đích ghi của cả pipeline indexing lẫn reindex, với guard revision theo từng nguồn trên từng SKU
(ADR-015, ADR-019, ADR-022, ADR-026). Thay index đó bằng index cấp Product sẽ phải viết lại toàn bộ phía ghi.

## Decision

1. **Index đọc mới `product_cards`:** mỗi Product một document `{product_id, source_version, skus: [...]}`.
   - `skus` là `nested`, chứa **nguyên văn** các document SKU còn sống (`is_deleted = false`) của Product đó.
   - Mapping `skus` trùng từng byte với mapping `products`, cùng analysis
     ([`product_cards.index.json`](../schema/product_cards.index.json), sinh từ `products.index.json`; `ProductCardSchemaTest` chặn lệch).
   - Physical index `product_cards_v<N>` nằm sau alias `product_cards`. `_meta.source_index` ghi generation `products` mà nó phản chiếu.
2. **Index SKU vẫn là đích ghi duy nhất.** `ProductCardProjector` chỉ **đọc** index SKU:
   - **Dựng toàn bộ** khi không có generation `product_cards` nào phản chiếu index mà alias `products` đang trỏ tới (lần đầu,
     hoặc sau full reindex). Quét SKU theo `product_id` trên một PIT, ghi vào generation mới, rồi đổi alias.
   - **Change feed** khi đã khớp. Mọi script ghi đều đóng `indexed_at`, nên các document SKU có `indexed_at` từ vị trí đã áp
     dụng (trừ `lookback` 10 giây cho refresh và lệch đồng hồ) cho biết Product nào cần dựng lại.
   - Document Product ghi với `version_type=external_gte`, version là `indexed_at` lớn nhất của mọi SKU của nó, kể cả tombstone.
     Một lần dựng đọc trạng thái cũ không thể đè lần dựng đọc trạng thái mới.
   - Product không còn SKU sống thì document bị xoá theo cùng quy tắc version.
   - Chỉ một replica chạy projector, dưới lease `product-cards-projector` trong `search_jobs`. Vị trí change feed ghi vào
     `_meta.changes_through` mỗi 15 giây, để replica tiếp quản chạy tiếp từ đó thay vì bỏ qua khoảng trống.
3. **Đường đọc `GET /api/v2/search/products`** dùng `product_cards` khi cờ bật **và** `_meta.source_index` bằng index mà alias
   `products` đang trỏ tới. Kết quả kiểm tra này cache 5 giây mỗi replica. Ngược lại, request dùng ADR-020.
   Mọi quy tắc ADR-020 giữ nguyên nghĩa vì mọi điều kiện SKU nằm trong **một** truy vấn `nested`:

   | Quy tắc ADR-020 | Cách thực thi trên `product_cards` |
   |---|---|
   | Product khớp khi một SKU thỏa mọi điều kiện | Truy vấn `nested` trên `skus` |
   | SKU đại diện: rẻ nhất trong SKU khớp, hoà giá theo `sku_id asc` | `inner_hits` size 1 |
   | `price_asc`/`price_desc` theo giá SKU đại diện | Sort nested `mode: min`, `nested.filter` là chính truy vấn SKU |
   | `newest`, `rating` theo giá trị lớn nhất của SKU khớp | `mode: max`; rating thiếu tính là 0 |
   | `relevance` theo điểm cao nhất của SKU khớp | `score_mode: max` |
   | Mọi sort kết thúc bằng `product_id asc` | Sort phụ `product_id asc` |
   | `total_products` chính xác | `track_total_hits` |
   | Facet đếm Product phân biệt trên SKU khớp | `nested` → `filter` → `terms` → `reverse_nested` (đếm chính xác) |

   Mỗi request là **một** lần tìm kiếm cỡ trang trên PIT. Phân trang bằng `search_after` với cursor thế hệ 3 (ký HMAC, mang
   giá trị sort của Product cuối). Cursor luôn tiếp tục trên đường đã phát ra nó: thế hệ 2 thuộc ADR-020, thế hệ 3 thuộc ADR-027.

**Ngoài phạm vi:** facet theo danh mục (`/categories/{id}/facets`), job `product_count`, gợi ý và reindex vẫn dùng index SKU
như cũ. Không đổi contract HTTP, event hay export.

## Alternatives considered

- **Migration đầy đủ sang index Product** (index Product là đích ghi). Tiết kiệm một index nhưng phải viết lại script guard
  theo từng SKU thành script trên phần tử nested, cùng reindex, purge, verifier và count job; reindex hiện cũng đang thay đổi
  (ADR-026). Không chọn lúc này. Có thể làm sau khi pilot chứng minh projection đủ ổn định.
- **`collapse` theo `product_id`:** ADR-020 đã loại, vì `price_desc` lấy nhầm SKU đắt nhất.
- **Cache kết quả hoặc tăng `product-scan-batch-size`:** không đổi O(P), trang chủ vẫn quét toàn catalog. ADR-020 cũng cấm cache tầng ứng dụng.
- **Projection dựng từ Kafka event trực tiếp:** phải nhân đôi toàn bộ guard revision và replay. Đọc lại index SKU tái dùng
  guard đã có và luôn hội tụ về đúng trạng thái index SKU.

## Consequences

- **Tích cực:** chi phí mỗi request không còn tỉ lệ với số Product khớp. Số đo so sánh ở
  [search-scale-benchmark.md](../../performance/search-scale-benchmark.md#so-sánh-grouping-và-product_cards). Facet đếm chính xác thay vì `cardinality` xấp xỉ.
- **Độ trễ phản chiếu:** một thay đổi SKU tới `product_cards` sau khoảng 1–2 giây, đo được 1 giây trên stack benchmark (refresh
  của index SKU + chu kỳ poll 1 giây + refresh của `product_cards`). Sau full reindex, tìm kiếm quay về ADR-020 cho tới khi dựng xong
  (60 000 Product: 23,5 s trên máy benchmark).
- **Thứ tự `relevance` khi có `q`** có thể khác ADR-020 với các Product cùng điểm hoặc sát điểm. Thống kê BM25 tính theo shard,
  mà document SKU được định tuyến theo `sku_id` còn document Product theo `product_id`. Trên catalog benchmark, chỉ 5–16 trên 20 dòng của
  trang 1 trùng nhau với từ khoá phổ biến. Contract không cam kết điểm cụ thể, tổng và tập kết quả không đổi, nhưng thứ tự hiển thị **sẽ đổi**
  khi bật cờ. Chủ sản phẩm cần chấp nhận điều này trước khi bật ở production.
- **Ở catalog rất nhỏ** (1 000 Product), duyệt danh mục dưới tải chậm hơn grouping khoảng 17% vì chi phí cố định của truy vấn `nested`.
  Từ 10 000 Product, projection tốt hơn ở mọi kịch bản đã đo.
- **Chi phí:** thêm một index chứa lại toàn bộ SKU (dung lượng xấp xỉ index SKU), một luồng nền, và vài truy vấn nhỏ mỗi giây
  (lease, alias, change feed). Ghi một SKU kéo theo ghi lại cả document Product (nested được lưu thành một khối).
- **Nợ kỹ thuật được chấp nhận:** hai cách thực thi cùng tồn tại cho tới khi quyết định bỏ ADR-020. Khi Product có rất nhiều SKU,
  giới hạn `index.mapping.nested_objects.limit` (mặc định 10 000) áp dụng.

## Migration impact

- **Code:** thêm `ProductCardProjector`, `ProductCardStore`, `ProductCardIndex`, `ProductCardRepository`,
  `ProductCardQueryBuilder`, `ProductCardReadiness` và `ProductIndexProperties`. `ProductQueryBuilder` nhận tiền tố trường,
  `FacetAssembler` nhận số đếm sẵn, `SearchCursor` thêm thế hệ 3.
- **Data:** index mới `product_cards_v<N>`, tự tạo khi bật cờ. Tắt cờ thì không ai đọc hay ghi nó; có thể xoá tay.
- **Rollout:**
  1. Bật cờ trên một môi trường.
  2. Theo dõi `search_product_cards_ready`, `search_product_cards_lag_seconds` và `es_query_duration_seconds{query_type="product_cards"}`.
  3. Chạy `scripts/ci/search-bench.sh` và `loadtest/search-bench/compare.py`.
  4. Rollback bằng cách tắt cờ: request mới quay về ADR-020 ngay, còn cursor thế hệ 3 đang mở vẫn chạy tới hết PIT.
- **Contract:** không đổi. Cursor vẫn là chuỗi opaque.

## Verification

- **Unit test:** `ProductCardTest`, `ProductCardProjectorTest`, `ProductCardQueryBuilderTest`,
  `ProductSearchServiceRoutingTest`, `SearchCursorProductCardsTest`, `ProductCardSchemaTest`.
- **Tương đương trên ES thật:** [`compare.py`](../../../loadtest/search-bench/compare.py) chạy mỗi kích thước catalog trong
  benchmark: 8 kịch bản × 5 sort × 3 trang, so tổng, thứ tự, SKU đại diện, giá và facet.
- **Hành vi projection:** [`projection_check.py`](../../../loadtest/search-bench/projection_check.py) kiểm tra đổi giá, tombstone
  và đổi alias sau reindex.
- **Hiệu năng:** [search-scale-benchmark.md](../../performance/search-scale-benchmark.md).
