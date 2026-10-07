---
doc_id: search-adr-020
service: search
status: accepted
date: 2026-09-30
decision_owners:
  - Search Service team
supersedes:
  - ADR-012-sku-results-with-search-after
  - ADR-007-cursor-pagination (storefront product queries only)
---

# ADR-020 — Một Product một card, SKU đại diện giá thấp nhất thỏa bộ lọc

## Context

Storefront cần một card cho mỗi Product. ADR-012 trả từng SKU nên Product có nhiều
biến thể xuất hiện nhiều lần, cùng tên/ảnh nhưng khác giá. Gom các item của một
trang ở frontend không bảo đảm chọn được SKU rẻ nhất, tổng số Product hoặc phân
trang đúng. Collapse + search_after không đáp ứng mọi kiểu sort hiện có.

## Decision

Giữ index `products` theo SKU, `_id = sku_id`, ownership và revision guard hiện tại.
Query áp dụng TẤT CẢ điều kiện lên SKU trước khi gom theo `product_id`: visibility,
q, category, thuộc tính, giá, tồn kho và khuyến mãi. Mỗi Product chỉ xuất hiện một lần.
SKU đại diện có `selling_price_vnd` thấp nhất trong tập SKU khớp; nếu bằng giá thì
chọn `sku_id asc`. Giá gốc, giảm giá, ảnh, tồn kho và link PDP lấy từ cùng SKU đó.
Không tự suy ra còn hàng từ Product/SKU ACTIVE; thiếu availability vẫn là chưa xác nhận.

Các sort Product đều kết thúc bằng `product_id asc`:

- `price_asc`/`price_desc`: giá của SKU đại diện, không lấy giá SKU đắt nhất.
- `relevance`: score cao nhất của các SKU khớp.
- `newest`: updated_at lớn nhất của các SKU khớp (chưa có created_at).
- `rating`: product_rating_avg lớn nhất của các SKU khớp.

Repository mở Elasticsearch point-in-time (PIT), duyệt hết các bucket Product bằng
composite aggregation theo `product_id`. Mỗi bucket có top_hits size=1 với sort giá
tăng rồi sku_id tăng và các max metric cho ranking. Facet được tính trong cùng PIT
trên tập SKU khớp, vẫn có cardinality DISTINCT_PRODUCT và độ chính xác APPROXIMATE.
Service sort toàn bộ Product và cắt trang; total_products là số bucket chính xác.
Không dùng terms top-N có thể mất bucket giữa shard hoặc cardinality để làm tổng chính xác.

Cursor generation 2 được ký HMAC-SHA256, mang PIT id, vị trí Product, resolved category id và query chuẩn
hóa (trừ size). Tiếp tục cùng snapshot dù giá/catalog đổi hoặc alias được cutover.
Mỗi request gia hạn PIT 2 phút; hết hạn/đổi filter/sort/token sửa tay trả INVALID_CURSOR.
Trang cuối đóng PIT; cursor bỏ dở tự hết hạn. Client tải lại trang đầu để xem dữ liệu mới.
Cursor SKU cũ bị từ chối. Không có cache kết quả tầng ứng dụng hay index mới.

## Cost and limits

Mỗi trang duyệt toàn bộ tập Product khớp: O(P) bộ nhớ, O(P log P) sort và ceil(P/500)
request aggregation, cộng trang rỗng kết thúc. Đây là lựa chọn tính đúng cho rollout
hiện tại; không khẳng định đã đáp ứng catalog lớn. Ngân sách toàn bộ query 5 giây;
`search.pagination.max-matching-products` mặc định 50000 bảo vệ heap. Vượt giới hạn,
timeout hoặc shard failure trả lỗi, không trả trang/tổng thiếu. Quan sát metric
`es_query_duration_seconds{query_type="product_groups"}`, heap, open PIT và throughput.
Phải benchmark trước khi tăng cap hoặc triển khai catalog lớn; nếu chi phí không đạt
SLO, thiết kế Product projection chứa nested SKU cần ADR/migration riêng.

## Alternatives

- Frontend dedupe: mất Product qua ranh giới trang, không biết giá thấp nhất toàn tập.
- Collapse + offset: giới hạn result window; price_desc dễ lấy SKU đắt nhất để sort.
- Product projection nested SKU: phù hợp scale lớn hơn nhưng phải đổi pipeline,
  per-SKU ownership/guard và full reindex; không cần cho rollout dữ liệu hiện tại.

## Migration and verification

Thay total_skus bằng total_products tại API pilot `/api/v2/search/products` (spec 2.1.0).
Đây là thay đổi không tương thích: triển khai backend/frontend/types/client cùng đợt,
reset pagination và thông báo các consumer pilot. Không cần đổi Product event/export,
mapping, dữ liệu đã seed hay reindex. Product Service tiếp tục sở hữu giá từng SKU.

Test nhiều SKU/Product, tie giá, lọc màu + giá + stock, price_desc theo minimum,
mọi sort, phân trang nhiều batch composite và không lặp Product, snapshot khi giá
đổi hoặc thêm Product, final full page, cursor cũ/tamper/đổi tham số và PIT hết hạn.

## References

- [Elasticsearch PIT lifecycle](https://www.elastic.co/guide/en/elasticsearch/reference/8.11/point-in-time-api.html)
- [Composite aggregation pagination](https://www.elastic.co/guide/en/elasticsearch/reference/8.11/search-aggregations-bucket-composite-aggregation.html)
