---
doc_id: order-adr-006
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/order/domain.md
  - docs/order/workflows.md
---

# ADR-006 — Một refund transaction trên mỗi order và Inventory gate

## Trạng thái

`Accepted` theo “policy B” của thiết kế cũ; ngày gốc chưa được ghi.

## Bối cảnh

Buyer có thể spam/retry return, Admin có thể force-refund và Payment event có thể
duplicate. Hoàn tiền trước khi nhận đủ hàng làm tăng rủi ro thất thoát; nhiều refund
transaction trên cùng order gây double refund và allocation khó đối soát.

## Quyết định

Mỗi order có tối đa một `OrderRefundWorkflow`, một `order_refund_cases` record và
một `refund_transaction_id` do Order khởi tạo. Trigger chỉ từ Buyer API return
hoặc Admin force-refund. Scope FULL/PARTIAL và amount khóa một lần. Workflow đăng
ký expected return với Inventory và chỉ gọi Payment sau
`INVENTORY_RETURN_RECEIVED_ALL` hoặc admin override đã xác minh. Chờ tối đa 30 ngày,
sau đó manual verification. Buyer trigger còn có permanent one-shot guard; Admin
không dùng guard này.

## Phương án đã cân nhắc

- Refund ngay khi request: nhanh nhưng chưa có bằng chứng hàng hoàn.
- Nhiều partial refund transaction: linh hoạt nhưng tăng double-spend/allocation risk.
- Single transaction + Inventory gate: chậm hơn nhưng dễ bảo toàn safety; được chọn.

## Hệ quả

Loại double refund và gắn tài chính với hàng thực tế. Buyer không thể submit lại
sau failure; cần Ops escalation/manual override; workflow có thể tồn tại 30 ngày.
Schema hiện thiếu `MANUAL_VERIFICATION_REQUIRED`, là debt phải giải quyết.

## Ảnh hưởng migration

Cần refund case PK/unique transaction, workflow signal/routing, Inventory expectation,
override authorization/audit và dashboard backlog. Order không phát refunded event
khi chưa có consumer; Inventory điều khiển restock vật lý từ scope/inspection của mình.

## Xác minh

Test concurrent triggers, exactly-one Payment refund, locked scope, duplicate/missing
Inventory signal, 30-day timeout/override, failure/reverse và permanent Buyer guard.
