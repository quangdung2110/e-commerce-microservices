---
doc_id: search-adr-012
service: search
status: superseded
date: 2026-09-04
decision_owners:
  - Search Service team
supersedes: ADR-002-sku-first-document
evidence:
  - docs/search/interfaces.md
  - docs/search/workflows.md
---

# ADR-012 — API search trả SKU hit trực tiếp, phân trang bằng `search_after`

> Superseded by [ADR-020](./ADR-020-product-cards-with-cheapest-eligible-sku.md), 2026-09-30. Nội dung bên dưới giữ lại lịch sử.

## Context

ADR-002 chọn SKU làm document nhưng lại yêu cầu field collapse theo `product_id`
trong khi API phân trang bằng `search_after`, sort theo relevance/giá/rating và
thêm tie-break `sku_id`. Elasticsearch chỉ hỗ trợ kết hợp `collapse` và
`search_after` khi field sort trùng field collapse và không có secondary sort.
Thiết kế cũ vì vậy không thể đồng thời đảm bảo một Product một lần, sort đúng và
phân trang sâu ổn định.

## Decision

Index `products` vẫn là SKU-first: một document và một API result item là đúng
một SKU, `_id = sku_id`. Query không dùng `collapse` hay `inner_hits`. Mỗi item
mang các field Product đã denormalize nên client vẫn dựng được product card, nhưng
cùng một `product_id` có thể xuất hiện nhiều lần khi nhiều SKU cùng khớp.

API dùng `search_after` trên toàn bộ sort values của SKU hit cuối; mọi sort kết
thúc bằng `sku_id asc`. Cursor mang fingerprint của bộ query/filter/sort đã chuẩn
hóa và bị từ chối khi dùng cho một bộ tham số khác.

`total` là tổng số SKU khớp. Facet và category count vẫn đo DISTINCT `product_id`;
đây là số liệu điều hướng, không phải đơn vị phân trang.

## Alternatives considered

- **Giữ field collapse.** Không tương thích với sort và `search_after` cần cho
  browse thực tế.
- **Đổi sang `from`/offset.** Làm mất lợi ích phân trang sâu, bị giới hạn result
  window và có chi phí tăng theo độ sâu trang.
- **Product document chứa nested SKU.** Giữ được một Product một lần nhưng làm
  tồn kho per-SKU, guard và filter/aggregation phức tạp hơn; mỗi thay đổi SKU có
  thể ghi lại document Product lớn.
- **Một index Product-card riêng.** Có thể cân nhắc sau khi có yêu cầu bắt buộc
  về danh sách Product duy nhất, nhưng không được thêm khi chưa có query semantics
  và benchmark rõ ràng.

## Consequences

Tích cực: cursor hợp lệ cho tất cả sort; giá, tồn kho và tổ hợp thuộc tính luôn
thuộc cùng SKU; update theo event Inventory vẫn là update đúng một document.

Tiêu cực: UI có thể thấy nhiều card có cùng Product. Client nào cần một Product
duy nhất phải tự nêu requirement mới; Search không được âm thầm dedupe xuyên trang
vì làm sai cursor hoặc ranking.

## Migration impact

Gỡ `collapse` và `inner_hits` khỏi Elasticsearch request/response. Thay cursor
offset cũ bằng cursor sort-values có fingerprint; cursor cũ trở thành invalid.
Không cần đổi mapping hay reindex vì thay đổi chỉ ở query/API semantics.

## Verification

Test bắt buộc: mọi sort sinh request `search_after` không collapse; đi qua nhiều
trang không lặp/bỏ sót SKU không đổi; cursor đổi query/filter/sort bị từ chối;
một Product nhiều SKU có thể trả nhiều item; facet vẫn đếm DISTINCT Product.
