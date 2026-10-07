# Storefront — Một card cho mỗi sản phẩm

Search v2 trả mỗi Product đúng một item, `total_products` là tổng số Product khớp.
Frontend không tự tìm SKU rẻ nhất từ một trang SKU. Search chọn SKU có giá bán thấp
nhất thỏa tất cả bộ lọc; nếu bằng giá thì chọn sku_id tăng dần
([Search ADR-020](../search/decisions/ADR-020-product-cards-with-cheapest-eligible-sku.md)).

Grid/key và chống card lặp dùng `product_id`. Card hiển thị tên Product, giá “Từ …”,
ảnh/giảm giá/tồn kho của SKU đại diện. Link `/products/{slug}?sku={sku_id}` chọn chính
biến thể đại diện khi mở PDP; các SKU khác vẫn chọn được trong trang chi tiết.
Ví dụ Product có SKU trắng 500000 và đen 1000000: không lọc hiển thị từ 500000;
lọc đen hiển thị từ 1000000. Sort giá giảm dần cũng dựa trên minimum đủ điều kiện.

Mỗi trang tối đa 20 Product mặc định, không phải 20 SKU. “Tải thêm” tiếp tục cursor
snapshot của backend; đổi bộ lọc/sort reset cursor. Snapshot hết hạn cần tải lại trang
đầu. Không cache dữ liệu search theo ISR. Tồn kho chưa có Inventory vẫn là “Chưa xác
nhận còn hàng”; để xem chúng dùng `in_stock=false`. Mặc định chỉ còn hàng không đổi.

Backend/frontend phải rollout cùng đợt vì total_skus được thay bằng total_products.
Sinh lại `src/lib/api/generated/search.ts` từ OpenAPI, kiểm tra typecheck/test và
kiểm chứng API/HTML: mỗi product_id chỉ một card, link SKU đúng và pagination đủ.

Bộ lọc dùng facet đi kèm search response để phản ánh đúng category/query/in_stock hiện tại; endpoint facet danh mục mặc định chỉ còn hàng được dùng làm fallback khi search lỗi.
