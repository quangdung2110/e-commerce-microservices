---
doc_id: order-adr-010
service: order
status: accepted
date: 2026-09-15
decision_owners:
  - Order Service team
  - Inventory Service team
  - Payment Service team
supersedes:
  - ADR-006
  - ADR-008 (automatic-refund branch only)
evidence:
  - docs/order/domain.md
  - docs/order/interfaces.md
  - docs/order/workflows.md
  - docs/inventory/decisions/ADR-018-selected-line-refund-scope-and-pre-dispatch-release.md
---

# ADR-010 — One lifetime refund case with selected full-quantity lines

## Trạng thái

`Accepted` — 2026-09-15. ADR này thay thế phần scope/gate của ADR-006. Lịch sử
ADR-006 vẫn được giữ nguyên; quyết định mới là authority cho Release 1.

## Bối cảnh

Thiết kế cũ dùng đồng thời các từ `FULL/PARTIAL`, `order item` và “một scope đang
hoạt động”. Điều đó không xác định được một Buyer có thể trả một phần số lượng của
một line hay mở case khác sau khi case đầu đã đóng. Nó cũng không có một đường
an toàn, atomic cho admin refund khi hàng chưa rời kho.

## Quyết định

- Mỗi `order_id` có **tối đa một refund case trong toàn bộ vòng đời**, bất kể
  nguồn là Buyer hay Admin và bất kể case kết thúc `SUCCEEDED`, `FAILED`,
  `CLOSED_INCOMPLETE` hay cần xử lý thủ công. Request idempotent chỉ attach vào
  case đó, không tạo case thứ hai.
- Buyer chỉ tạo case sau khi **mọi** order line đã `DELIVERED` bằng verified Delivery proof.
  Buyer chọn một hay nhiều `order_line_id`; mỗi line được chọn phải có
  `expected_quantity = order_item_lines.quantity`. Không có partial quantity và
  không dùng `sku_id` làm identity scope.
- Refund amount được Order khóa đúng một lần, không gọi Promotion và không
  tính lại discount: `min(sum(selected_line.quantity * selected_line.unit_price_minor),
  orders.final_amount_minor)`. Voucher/discount còn lại do Buyer chịu và Promotion
  wallet/budget không đổi.
- Inventory chỉ phát
  `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` khi mọi
  quantity trong selected scope đã được nhận và final-inspect. `PASS` mới về
  sellable; `FAIL` ở quarantine. Event là evidence vật lý, **không** là quyết
  định payout.
- Sau completion event, Order gọi Payment đúng một lần với stable refund
  identity, kể cả khi một hay nhiều line inspection `FAIL`. Payment/Order không
  tạo transaction/case thứ hai khi provider fail hoặc reverse; Ops xử lý case
  hiện hữu.
- Admin automatic force refund chỉ hợp lệ khi Inventory atomically xác nhận và
  release **toàn bộ** committed allocation của order trước physical dispatch.
  Lệnh đó hoặc release tất cả line, hoặc không release line nào. Có bất kỳ line
  đã dispatch thì Order từ chối automatic force refund; Finance/Ops dùng reconciliation
  decision có evidence/audit, không có partial-release workaround. Đường này không có Refund
  Scope vì không có hàng trả về.
- Pre-payment cancellation vẫn là cancel/compensation, không phải refund.
- Late-payment recovery may restore a safe paid/fulfilment lane, but if it cannot,
  Release 1 sends the case to evidence-backed reconciliation rather than an un-gated system
  auto-refund. A future automatic recovery refund requires a separate ADR and
  Inventory evidence compatible with this policy.

## Hệ quả

`FULL/PARTIAL` bị loại khỏi public refund policy. “Partial order refund” chỉ có
nghĩa là chọn một tập proper subset các line, trong khi quantity của từng line
được chọn luôn đầy đủ. Inventory giữ proof receipt/inspection; Order owns
eligibility, amount và refund case; Payment owns provider execution.

## Ảnh hưởng migration

`order_refund_cases` phải giữ lifetime uniqueness theo `order_id` và có child
`order_refund_case_lines` theo `order_line_id`. Refund case phải lưu refund request,
scope/Inventory evidence, amount đã khóa, đường Buyer/Admin và payment transaction
ID. Contract Inventory phải đổi whole-order scope thành selected-line full quantity
và có command release-committed-toàn-order atomic. Payment phải từ chối tạo refund
transaction thứ hai cho cùng `order_id`.

## Xác minh

Test Buyer chọn A×2/C×3 từ order A×2/B×1/C×3; A×1 phải bị từ chối. Test nhiều
package, mixed PASS/FAIL vẫn gọi Payment một lần, duplicate completion event,
case terminal không thể mở lại, và admin all-undispatched success versus any
dispatched no-mutation conflict.
