---
doc_id: product-detail-bff-domain
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Domain Model

## Ngôn ngữ chung

| Term | Định nghĩa |
|---|---|
| Catalog snapshot | Product detail/variant/price do Product trả |
| Availability projection | Snapshot Inventory theo SKU được BFF materialize |
| `IN_STOCK` | Snapshot hợp lệ có `available_quantity > 0` |
| `OUT_OF_STOCK` | Snapshot hợp lệ có `available_quantity = 0` |
| `UNKNOWN` | Không có snapshot, projection quá stale hoặc recovery chưa hoàn tất |
| Product display availability | Derived: có ít nhất một SKU catalog-active `IN_STOCK` |

## Quy tắc hiển thị

| ID | Quy tắc |
|---|---|
| `PDP-BR-001` | Availability được quyết định theo SKU đã chọn, không theo Product aggregate. |
| `PDP-BR-002` | Không có row hoặc row quá freshness budget phải trả `UNKNOWN`, không mặc định còn hàng. |
| `PDP-BR-003` | SKU `INACTIVE` hoặc Product không `ACTIVE` không được trả cho buyer, bất kể projection tồn kho. |
| `PDP-BR-004` | Chỉ `stock_revision` lớn hơn thay đổi row availability. |
| `PDP-BR-005` | BFF không gửi reserve/deduct/release và không dùng availability UI để xác nhận đơn. |

Public response chỉ cần `availability_status`; việc expose số lượng thật là product
policy riêng và không phải điều kiện để bật/tắt UI. `observed_at` có thể được trả cho
client nếu cần giải thích freshness, còn `stock_revision` chỉ dành cho nội bộ/trace.
