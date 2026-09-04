---
doc_id: search-adr-009
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
  - Product Service team
supersedes: null
evidence:
  - docs/product/README.md
  - docs/inventory/inventory_events.md
---

# ADR-009 — `is_in_stock` đến từ Inventory, không suy ra từ trạng thái catalog

## Context

Buyer mặc định chỉ muốn thấy hàng còn bán được. Thiết kế Search trước đây suy ra
`is_in_stock` từ trạng thái catalog: `is_deleted = false` và `product_status` cùng
`sku_status` đều `ACTIVE`. Cách đó đo **khả dụng catalog**, không đo tồn kho, nên
hàng hết vẫn hiện như còn.

Trong thiết kế hiện hành, Inventory là nguồn chân lý duy nhất cho tồn kho, Product
không lưu và không phát dữ liệu tồn kho, và Search consume
`INVENTORY_STOCK_CHANGED` trực tiếp từ Inventory.

## Decision

Search lưu `available_quantity` do Inventory công bố và đặt
`is_in_stock = available_quantity > 0`. Trạng thái catalog và trạng thái tồn kho là
hai chiều độc lập trên document: điều kiện hiển thị mặc định của buyer là cả hai
cùng thỏa mãn.

Event tồn kho **không được tạo document**: nếu SKU chưa được index hoặc đã
tombstone, event bị bỏ qua và chỉ ghi metric. Guard là `stock_version`, tách rời
khỏi `catalog_revision`.

Con số này chỉ để hiển thị và lọc. Quyết định bán được hay không vẫn thuộc về
reservation tại Inventory ở bước checkout.

## Alternatives considered

- **Suy ra từ trạng thái catalog.** Không cần thêm topic nhưng trả lời sai câu hỏi
  của buyer.
- **Product làm trung gian tồn kho.** Product sẽ phải lưu và phát lại dữ liệu nó
  không sở hữu; thiết kế Product hiện hành đã loại bỏ hướng này.
- **Gọi Inventory đồng bộ khi build response.** Chính xác tại thời điểm hiển thị
  nhưng đặt một lời gọi liên service vào đường đi nóng nhất và không lọc/sắp xếp
  được theo tồn kho.
- **Chỉ hiển thị mức tồn kho, không dùng để lọc.** Đơn giản hơn nhưng mất tính năng
  “chỉ xem hàng còn”, vốn là hành vi mặc định của buyer.

## Consequences

Tích cực: kết quả tìm kiếm phản ánh đúng khả năng mua; tồn kho không còn bị trộn
với ngữ nghĩa trạng thái catalog; Search có thể lọc và sắp xếp theo tồn kho.

Tiêu cực: thêm một topic và một guard nữa; số tồn kho hiển thị luôn trễ vài giây
so với Inventory và có thể sai ngay sau một đợt đặt hàng lớn. Nghiêm trọng hơn:
nếu Search bỏ lỡ một event tồn kho ngoài retention của Kafka, không có nguồn nào
để dựng lại — Inventory chỉ phát khi có biến động. Full reindex không phục hồi
được field này. Hạn chế này phải được ghi vào runbook; nếu nó trở thành sự cố lặp
lại, cần một API snapshot tồn kho từ Inventory và một ADR mới.

## Migration impact

Thêm `available_quantity` và `stock_version` vào mapping, thêm consumer cho
`t.inventory.events`, và gỡ logic suy ra `is_in_stock` từ trạng thái. Sau khi bật,
`is_in_stock` chỉ đúng dần khi từng SKU có biến động tồn kho đầu tiên; cần một đợt
mồi dữ liệu ban đầu từ Inventory.

## Verification

Test bắt buộc: về 0 thì biến mất khỏi kết quả mặc định; có hàng lại thì xuất hiện;
event trễ không hạ ngược số; event cho SKU chưa index bị bỏ qua; event cho SKU đã
tombstone bị bỏ qua.
