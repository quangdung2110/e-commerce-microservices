---
doc_id: search-adr-011
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
extended_by: ADR-019-inventory-owned-sku-availability, ADR-021-tombstone-protection-window-35d, ADR-022-post-implementation-audit-corrections
evidence:
  - docs/product/data.md
  - docs/search/schema/scripts/catalog_tombstone.painless
---

# ADR-011 — Tombstone kèm cửa sổ purge thay cho xóa ngay

## Context

Product hard delete Product và SKU, và phát `PRODUCT_DELETED`/`SKU_DELETED` làm
tombstone. Search nhận ba nguồn event độc lập cho cùng một document, nên sau khi
xóa vẫn có thể có event tồn kho hoặc rating của chính SKU đó đến trễ. Nếu document
đã bị xóa vật lý và lệnh ghi kế tiếp dùng upsert, document sẽ sống lại dưới dạng
bản ghi rỗng, không có tên và không có giá — nhưng vẫn khớp một số filter.

Thiết kế cũ giữ tombstone 30 ngày và trộn lẫn nó với việc dọn bản nháp quá hạn qua
một endpoint của Product vốn không tồn tại.

## Decision

ADR-019 extends the Inventory-specific purge gate: Search revokes Inventory readiness
after tombstone and physical purge cannot run before that revocation succeeds.
ADR-021 set the protection window to 35 days; ADR-022 replaced the number with 45 days (longest consumed
retention 30 days for `t.inventory.stock.events.v1`, plus 7 days of Kafka segment roll, plus margin).

Xóa ở Search gồm hai bước tách bạch:

1. **Tombstone.** Event xóa đặt `is_deleted = true`, `deleted_at` và
   `is_in_stock = false`. Document biến mất khỏi mọi kết quả ngay lập tức. Mọi
   script kiểm tra `is_deleted` trước tiên và bỏ qua mọi cập nhật lên document
   tombstone.
2. **Purge.** Một job revoke Inventory readiness rồi xóa vật lý document đã
   tombstone lâu hơn cửa sổ bảo vệ. Cửa
   sổ này phải dài hơn retention của mọi topic Search tiêu thụ, để không còn event
   nào có thể được phát lại sau khi document biến mất.

Ngoài ra, lệnh ghi từ nguồn tồn kho và rating không bao giờ dùng `scripted_upsert`.
Đây là lớp bảo vệ thứ hai, độc lập với cửa sổ purge.

Sản phẩm `DRAFT` không bị dọn: chúng vẫn được index và chỉ bị lọc khỏi kết quả của
buyer. Vòng đời của bản nháp thuộc về Product.

## Alternatives considered

- **Xóa vật lý ngay khi nhận tombstone.** Giải phóng dung lượng sớm nhất nhưng phụ
  thuộc hoàn toàn vào việc không nguồn nào dùng upsert.
- **Giữ tombstone vĩnh viễn.** An toàn tuyệt đối trước zombie nhưng index phình
  theo lịch sử xóa và mọi truy vấn phải mang thêm filter.
- **Dọn theo tuổi dữ liệu như job cũ.** Trộn hai vấn đề khác nhau và dựa vào một
  endpoint không tồn tại của Product.

## Consequences

Tích cực: không có document zombie; xóa có hiệu lực tức thì với buyer; quy tắc dọn
dẹp chỉ phụ thuộc dữ liệu của chính Search.

Tiêu cực: document đã xóa vẫn chiếm dung lượng trong suốt cửa sổ bảo vệ; mọi truy
vấn phải mang filter `is_deleted = false`; cửa sổ purge bị ràng buộc bởi cấu hình
retention của các topic upstream, nên khi retention đổi thì cửa sổ này phải đổi
theo.

## Migration impact

Thêm job purge và cấu hình cửa sổ bảo vệ; gỡ Draft Cleanup Job cũ cùng lời gọi tới
endpoint orphan-draft không tồn tại. Kiểm tra lại: không lệnh ghi nào từ nguồn tồn
kho hoặc rating còn dùng upsert.

## Verification

Test bắt buộc: event tồn kho tới sau tombstone không tạo lại document; tombstone
chưa tới hạn không bị purge; tombstone quá hạn bị purge; sản phẩm `DRAFT` không bị
job nào xóa.
