---
doc_id: order-adr-002
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/order/domain.md
  - docs/order/data.md
---

# ADR-002 — Master order, delivery unit theo kho và immutable snapshot

## Trạng thái

`Accepted` trong thiết kế; ngày gốc và implementation evidence chưa có.

## Bối cảnh

Một checkout có thể được thực hiện bởi nhiều kho nhưng khách chỉ thanh toán một
giao dịch. Lịch sử order phải còn đúng khi Product hoặc User sửa/xóa tên, ảnh,
thuộc tính và address.

## Quyết định

Dùng `orders` làm master payment/lifecycle aggregate; `order_items` làm delivery
unit theo `warehouse_id`; `order_item_lines` giữ SKU/product/quantity/price/name/
image/attributes snapshot. Address đầy đủ đến ward được snapshot trên delivery
unit. `order_promotions` lưu PLATFORM discount allocation đã áp.

## Phương án đã cân nhắc

- Một bảng/order line phẳng: query đơn giản nhưng khó tách delivery state theo kho.
- Gọi Product/User để dựng lịch sử: dữ liệu có thể thay đổi hoặc service lỗi.
- Master/sub-order + snapshot: thêm join/storage nhưng giữ transaction một lần và
  lịch sử ổn định; được chọn.

## Hệ quả

Hỗ trợ smart routing, delivery FSM và một logical payment transaction đã capture
(có thể có nhiều numbered Payment attempt trước đó). Snapshot tăng storage, có PII,
không tự đồng bộ upstream và cần policy retention/GDPR riêng. Inventory Preview dùng
`line_reference`; khi tạo order phải persist bridge bất biến từ reference này sang
`order_line_id` trước Reserve. Search card cần aggregate/subquery qua nhiều table.

## Ảnh hưởng migration

Cần FK/cardinality/index, validation snapshot và backfill cho order cũ. Không dùng
database Product/User làm read path của order history.

## Xác minh

Cần migration/schema test, E2E multi-warehouse, test upstream data đổi/xóa nhưng
order history không đổi, và authorization/PII redaction test.
