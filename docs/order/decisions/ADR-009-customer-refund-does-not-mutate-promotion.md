---
doc_id: order-adr-009
service: order
status: accepted
date: 2026-09-10
decision_owners:
  - Order Service team
  - Promotion Service team
supersedes: ADR-007
evidence:
  - docs/order/interfaces.md
  - docs/promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md
---

# ADR-009 — Customer refund không mutate Promotion

## Trạng thái

`Accepted` ở mức boundary liên service; chưa implemented. Supersede ADR-007.

## Bối cảnh

Voucher PLATFORM có ý nghĩa cho toàn đơn. Phân bổ discount xuống SKU chỉ để hoàn
promotion budget tạo nghĩa business giả và buộc Promotion phụ thuộc mọi biến thể partial
refund. Order/Payment chưa được refactor nên cách tính amount và số lần partial refund
chưa phải quyết định của ADR này.

## Quyết định

- Customer refund partial, nhiều partial hoặc full-order không gọi Promotion.
- `ORDER_REFUNDED` không có `promotion_refund_allocations[]`; Promotion không consume.
- Wallet giữ `USED`; Promotion used quantity/budget không giảm; voucher không được tái cấp.
- `order_promotions` tiếp tục là voucher toàn đơn snapshot cho lịch sử/đối soát, không là
  line allocation.
- Pre-payment cancel và post-confirm technical `cancel-confirmed` vẫn là checkout saga
  compensation, không phải customer refund.
- Chính sách amount và multiplicity của partial refund sẽ được chốt khi refactor
  Order/Payment; boundary Promotion ở trên không phụ thuộc kết quả đó.

## Phương án đã cân nhắc

- FULL hoàn budget, PARTIAL theo coverage/pro-rata: cần allocation SKU và coupling.
- Promotion tự suy luận returned item: thiếu order/refund context.
- Restore voucher khi full refund: tăng reuse/accounting và trái policy đã chọn.

## Hệ quả

Refund workflow không thể fail vì Promotion và contract nhỏ hơn. Buyer không nhận lại
voucher kể cả full refund; campaign budget vẫn ghi nhận redemption ban đầu. Order/Payment
phải tự chịu trách nhiệm correctness tiền refund khi được thiết kế lại.

## Ảnh hưởng migration

Xóa Promotion refund activity/key, bỏ allocation khỏi `ORDER_REFUNDED`, gỡ Promotion
consumer và test/runbook tương ứng. Không thay đổi Payment refund transaction trong ADR này.

## Xác minh

Contract/workflow test chứng minh FULL/PARTIAL không gọi Promotion, event không chứa
allocation, retry Payment refund độc lập và checkout technical compensation vẫn hoạt động.
