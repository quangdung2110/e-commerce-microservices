---
doc_id: search-adr-002
service: search
status: superseded
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
superseded_by: ADR-012-sku-results-with-search-after
evidence:
  - docs/search/schema/products.index.json
---

# ADR-002 — Document là SKU, gom nhóm bằng field collapsing (Superseded)

## Context

Một Product có nhiều SKU, mỗi SKU có giá, tồn kho, thuộc tính biến thể và ảnh
riêng. Buyer tìm kiếm ở mức Product nhưng lọc và so sánh ở mức SKU: “iPhone 15 màu
đen 256GB dưới 30 triệu” là điều kiện trên SKU, còn kết quả hiển thị là Product.

## Decision

Mỗi Elasticsearch document là một SKU, `_id = sku_id`. Field product-level được
denormalize xuống mọi SKU của product. Kết quả trả cho buyer được gom nhóm bằng
`collapse` theo `product_id` kèm `inner_hits`.

Thuộc tính được lưu dưới dạng mảng `keyword` chứa token `code:value`
(`searchable_attributes` cho thuộc tính product-level, `variant_attributes` cho
thuộc tính biến thể). Không dùng kiểu `nested` và không dùng `join` field.

Hệ quả bắt buộc: mọi count hiển thị phải đếm DISTINCT `product_id` bằng
`cardinality` aggregation, không dùng `doc_count`.

## Alternatives considered

- **Document là Product, SKU là `nested`.** Đúng về mô hình và cho phép query theo
  cặp thuộc tính chính xác, nhưng mỗi phần tử nested là một document Lucene ẩn;
  với catalog lớn và filter đa chiều, chi phí bộ nhớ và độ trễ tăng nhanh.
- **Document là Product, SKU dẹt thành mảng song song.** Rẻ hơn nested nhưng làm
  filter theo tổ hợp thuộc tính sai lệch vì các mảng không ràng buộc với nhau.
- **Hai index Product và SKU, join ở tầng ứng dụng.** Đẩy join lên application,
  làm hỏng ranking và pagination.

## Consequences

Tích cực: filter là term filter phẳng và tận dụng bitset cache; không có rủi ro
bùng nổ bộ nhớ do nested; giá và tồn kho nằm đúng ở mức SKU.

Tiêu cực: dữ liệu product-level bị lặp trên mọi SKU nên một thay đổi product-level
là thao tác diện rộng; token `code:value` mất kiểu dữ liệu nên không so sánh số
được trên thuộc tính; `cardinality` là ước lượng nên số đếm có sai số nhỏ khi
`precision_threshold` không đủ lớn; và mọi sort phải có tie-break `sku_id asc` để
pagination ổn định.

## Migration impact

Đổi chiến lược này tương đương thay toàn bộ mapping và phải đi qua full reindex.
Client phụ thuộc vào việc mỗi item trả về là một SKU đại diện kèm biến thể trong
`inner_hits`.

## Verification

Test bắt buộc: một product nhiều SKU chỉ được đếm một lần trong facet; filter tổ
hợp thuộc tính trả đúng SKU; pagination qua nhiều trang không lặp và không bỏ sót.
