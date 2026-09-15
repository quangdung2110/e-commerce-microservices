---
doc_id: order-domain
service: order
scope: target
status: proposed
owners:
  - Order Service team
evidence: []
verified_against_commit: null
---

# Order Service — Mô hình miền nghiệp vụ

> File này gom mô hình master/sub-order, FSM, refund policy và các guard từ thiết
> kế cũ. Database field/payload chi tiết nằm ở `data.md` và `interfaces.md`.

## 1. Ngôn ngữ thống nhất

| Thuật ngữ | Định nghĩa | Không được nhầm với |
|---|---|---|
| Master Order | Giao dịch khách thanh toán một lần, aggregate root cấp order | Delivery unit theo kho |
| Order Item | Đơn vị thực hiện/giao hàng gắn với một warehouse | Một SKU line |
| Order Item Line | Snapshot một SKU, số lượng, giá, tên, ảnh, thuộc tính lúc mua | Product hiện tại |
| Checkout token | Token snapshot duy nhất do Checkout cấp, đồng thời định danh checkout workflow | `order_id` được sinh sau |
| Mốc tài chính | `PENDING_PAYMENT -> PAID` và event `ORDER_PAID` | Mốc vận hành `PAID -> PROCESSING` |
| Refund scope | Tập `order_line_id` được Buyer chọn; mỗi line luôn full ordered quantity | SKU set hoặc partial quantity của một line |
| Refund case | Control plane trọn đời, duy nhất cho một order | Payment refund record do Payment sở hữu |
| Buyer return guard | Quyền submit return một lần của Buyer | Request idempotency key TTL 7 ngày |
| Late-payment recovery | Lane phục hồi payment success đến sau timeout/cancel | Retry checkout workflow đã đóng |
| Buyer tab | Read-model value duy nhất suy ra từ master/item/payment state | FSM state lưu trực tiếp |
| Compensation | Hoạt động đảo side effect đã hoàn tất trong saga | Database rollback xuyên service |

## 2. Đối tượng miền nghiệp vụ

`Order` là aggregate root cho trạng thái giao dịch, số tiền, payment read status,
checkout identity, cancel/return reason, sequence và one-shot guard. `OrderItem`
là entity con đại diện delivery unit theo kho và có FSM riêng. `OrderItemLine` là
snapshot con của item. `OrderPromotion` là snapshot voucher toàn đơn để lưu lịch sử và
đối soát, không phải allocation theo SKU. `OrderRefundCase` là control-plane record theo
thiết kế Order hiện tại.

Inventory reservation, Promotion wallet/budget và Payment transaction thuộc
bounded context khác; Order chỉ tham chiếu identifier, snapshot và evidence cần
cho orchestration. Temporal workflow là process manager, không phải aggregate và
không thay thế invariants trong PostgreSQL.

## 3. Lệnh và truy vấn

| Loại | Năng lực | Tác nhân | Aggregate/năng lực xử lý |
|---|---|---|---|
| Lệnh | Tạo order từ checkout | Checkout Service | OrderCheckoutWorkflow/Order |
| Truy vấn | Xem order detail | Buyer owner/Admin | Order query |
| Truy vấn | Search/filter order | Buyer/Admin | PostgreSQL query model |
| Lệnh | Hủy pre-payment order | Buyer/Admin | Checkout workflow |
| Lệnh | Force refund order đã trả tiền trước dispatch | Admin | OrderRefundWorkflow + Inventory atomic release |
| Lệnh | Return selected full-quantity lines | Buyer owner | Buyer guard + OrderRefundWorkflow |
| Lệnh | Áp payment result | Payment consumer | Checkout/refund/recovery workflow |
| Lệnh | Đánh dấu delivery unit đã giao | Inventory event | OrderItem FSM |
| Lệnh | Xác nhận scope đã final-inspect | Inventory event | OrderRefundWorkflow |
| Lệnh | Auto-complete order | Inventory ship consumer | Order aggregate |

## 4. Quy tắc và bất biến nghiệp vụ

| ID | Quy tắc/bất biến | Enforcement mục tiêu | Lỗi/kết quả vi phạm |
|---|---|---|---|
| `INV-ORD-001` | Một `checkout_token` chỉ có một checkout workflow/order logic | Unique DB + WorkflowID + Redis SETNX | Attach/replay workflow hoặc conflict |
| `INV-ORD-002` | Payment/refund consumer không commit master FSM; workflow là owner | Adapter boundary + row lock trong activity | Event chỉ được signal/dedup |
| `INV-ORD-003` | Buyer chỉ submit return một lần; guard không reset | CAS `buyer_return_locked_at` | `REFUND_CUSTOMER_REQUEST_ALREADY_USED` |
| `INV-ORD-004` | Mỗi order tối đa một refund case và một Payment refund transaction trong toàn bộ vòng đời | Lifetime PK refund case + unique txn + WorkflowID | Reject/attach existing case |
| `INV-ORD-005` | Buyer refund chỉ sau Inventory final-inspect toàn bộ selected scope; Admin automatic refund chỉ sau Inventory atomic release toàn order trước dispatch | Refund case state + evidence + Temporal wait | Tiếp tục chờ/manual verification hoặc reject no-mutation |
| `INV-ORD-006` | Order chỉ complete khi tất cả item `DELIVERED` | Lock/count + CAS master | Không transition/phát event |
| `INV-ORD-007` | State, sequence và outbox cùng commit | PostgreSQL transaction | Rollback toàn transaction |
| `INV-ORD-008` | Customer refund không gọi/mutate Promotion; wallet và budget đã confirm giữ nguyên | Workflow boundary | Không phát promotion allocation |
| `INV-ORD-009` | Snapshot line/address không phụ thuộc dữ liệu upstream về sau | Persist khi create | Reject create nếu snapshot thiếu |
| `INV-ORD-010` | `final_amount = total_amount - total_discount`; chỉ PLATFORM allocation | Domain validation | Reject pricing snapshot sai |
| `INV-ORD-011` | Reserve promotion chỉ sau submit/order tồn tại, không ở Preview | Checkout saga ordering | Không tạo reservation ở preview |
| `INV-ORD-012` | Voucher PLATFORM có nghĩa toàn đơn, không phân bổ xuống item/SKU để refund | Domain boundary | Không tạo `promotion_refund_allocations[]` |
| `INV-ORD-013` | Admin action với `PAID/PROCESSING` đi refund lane, không cancel pre-payment | Cancel command branching | `409` nếu transition sai |
| `INV-ORD-014` | Buyer return chỉ hợp lệ khi toàn bộ item đã `DELIVERED` | Locked read trước CAS/start workflow | `REFUND_REQUIRES_ALL_ITEMS_DELIVERED` |
| `INV-ORD-015` | Mỗi selected `order_line_id` dùng đúng full original quantity; refund amount là gross selected lines, cap `final_amount` | Case-line snapshot + calculation lúc lock | `INVALID_REFUND_SCOPE` |
| `INV-ORD-016` | Không tính lại discount/Promotion khi refund; inspection FAIL không chặn Payment command | Workflow boundary + locked amount | Không gọi Promotion; gọi Payment sau completion fact |
| `INV-ORD-017` | Automatic admin force refund bị cấm nếu Inventory không thể release tất cả committed lines trước dispatch trong một transaction | Inventory response evidence | `ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED` |

## 5. State machine

### 5.1. Master Order FSM

Các state: `CREATED` (transient), `PENDING_PAYMENT`, `PAID`, `PROCESSING`,
`COMPLETED`, `CANCELLED`, `RETURN_REQUESTED`, `REFUNDED`.

| ID | Từ | Đến | Trigger/guard | Side effect chính |
|---|---|---|---|---|
| `T1` | `CREATED` | `PENDING_PAYMENT` | `CREATE_ORDER_ACTIVITY_COMMITTED` | Persist snapshot + `ORDER_CREATED` |
| `T2` | `PENDING_PAYMENT` | `PAID` | `PAYMENT_SUCCESS` trong workflow | `ORDER_PAID` cùng transaction |
| `T3` | `PENDING_PAYMENT` | `CANCELLED` | `PAYMENT_TIMEOUT` | Compensation rồi `ORDER_CANCELLED` |
| `T3.1` | `CANCELLED` | `PAID` | Payment success muộn và `cancel_reason=PAYMENT_TIMEOUT` | Recovery evidence + `ORDER_PAID` recovery |
| `T4` | `PENDING_PAYMENT` | `CANCELLED` | `USER_CANCEL` | Compensation pre-payment |
| `T4.1` | `PENDING_PAYMENT` | `CANCELLED` | `ADMIN_FORCE_CANCEL` | Compensation pre-payment |
| `T7` | `PAID` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_PRE_DISPATCH` + Inventory all-line release evidence | Tạo/khóa admin refund case |
| `T8` | `PAID` | `PROCESSING` | `POST_PAYMENT_SAGA_COMPLETED` | `ORDER_STATUS_CHANGED` |
| `T8.1` | `PROCESSING` | `PAID` | `POST_PAYMENT_COMPENSATION_ROLLBACK` | Revert operational state |
| `T8.2` | `PROCESSING` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_PRE_DISPATCH` + Inventory all-line release evidence | Vào admin refund lane |
| `T8.3` | `PROCESSING` | `COMPLETED` | Tất cả item `DELIVERED` + CAS | `ORDER_COMPLETED` |
| `T9` | `COMPLETED` | `RETURN_REQUESTED` | Buyer one-shot pass + mọi item delivered | Khóa selected full-quantity line scope, chờ final inspection |
| `T10` | `RETURN_REQUESTED` | `REFUNDED` | `REFUND_SUCCESS` | `ORDER_REFUNDED` |
| `T10.1` | `RETURN_REQUESTED` | `RETURN_REQUESTED` | `REFUND_PROCESSING`, `REFUND_FAILED` hoặc reversed | Chỉ cập nhật `payment_status`, có thể phát failure event |

Invalid transition không được “sửa” bằng update không điều kiện; trả conflict/no-op
theo ngữ cảnh và giữ state cũ. `REFUND_REVERSED` từ Payment được map thành
`payment_status=REFUND_FAILED`, lý do gốc giữ cho escalation.
Tên trigger legacy của T8.3 là `AUTO_COMPLETE_ALL_ITEMS_DELIVERED`; command action
legacy cho hủy là `CANCEL_ORDER`.

### 5.2. Order Item Delivery FSM

State: `PROCESSING` (initial), `DELIVERED`.

| ID | Từ | Đến | Trigger/guard |
|---|---|---|---|
| `I1` | `PROCESSING` | `DELIVERED` | `INVENTORY_SHIP_TO_CUSTOMER`, đúng order + warehouse |
Update I1 dùng `WHERE status='PROCESSING'`; duplicate cho affected row bằng 0.
Return/refund is recorded on `OrderRefundCase` and its selected line records, not
by changing a whole delivery unit state: one delivery unit can contain both selected
and unselected lines.

### 5.3. Payment read status

`payment_status` là read/UI state denormalized: `PENDING`, `PAID`, `FAILED`,
`REFUNDING`, `REFUNDED`, `REFUND_FAILED`. Cả Payment `REFUND_FAILED` và
`REFUND_REVERSED` map về `REFUND_FAILED` ở Order.

### 5.4. Refund case lifecycle

Buyer: `INITIATED -> WAITING_INVENTORY_SCOPE_INSPECTION ->
WAITING_PAYMENT_REFUND_RESULT -> SUCCEEDED|FAILED`; timeout goes to
`MANUAL_VERIFICATION_REQUIRED`. Admin pre-dispatch: `INITIATED ->
WAITING_INVENTORY_RELEASE -> WAITING_PAYMENT_REFUND_RESULT -> SUCCEEDED|FAILED`.
The two Inventory evidences are mutually exclusive. A terminal failure/reverse stays
on the same lifetime case; no second scope or refund transaction is allowed.

### 5.5. Buyer tab precedence

Mỗi order chỉ thuộc một tab, chọn theo thứ tự:

| Ưu tiên | Điều kiện | Tab |
|---|---|---|
| 1 | `PENDING_PAYMENT` | `TO_PAY` |
| 2 | `PAID/PROCESSING` và chưa item nào `DELIVERED` | `TO_SHIP` |
| 3 | `PAID/PROCESSING` và có ít nhất một item `DELIVERED` | `TO_RECEIVE` |
| 4 | `COMPLETED` và không trong return/refund | `COMPLETED` |
| 5 | `CANCELLED` | `CANCELLED` |
| 6 | `RETURN_REQUESTED/REFUNDED` hoặc payment refund state | `RETURN_REFUND` |

UI mapping: `TO_PAY=10/PENDING_PAYMENT`, `TO_SHIP=20/PROCESSING`,
`TO_RECEIVE=30/DELIVERED`, `RETURN_REFUND=40`, `COMPLETED=50`, `CANCELLED=60`,
fallback rank `99`.

## 6. Quy tắc phân quyền

- Buyer chỉ đọc/search order của chính mình (`orders.user_id=current_user_id`).
- Admin có full read/search scope.
- Buyer chỉ hủy order của mình khi `PENDING_PAYMENT`; Admin hủy pre-payment hoặc
  chỉ mở automatic force-refund khi `PAID/PROCESSING` và Inventory xác nhận tất cả
  lines chưa dispatch.
- Chỉ Buyer owner được gọi return; body phải chọn `order_line_id` thuộc order với
  full quantity đúng snapshot. Không có `FULL/PARTIAL` enum.
- Admin manual exception cho mixed/any-dispatched order là outside automatic Release 1
  và không được bypass Inventory evidence.
- Checkout, Payment, Inventory, Promotion và Temporal worker dùng service identity;
  public client không được giả mạo trusted headers hoặc signal.

## 7. Lỗi miền nghiệp vụ

| Mã lỗi | Điều kiện | Retryable | Ánh xạ |
|---|---|---|---|
| `NOT_FOUND` | Order không tồn tại hoặc không visible | Không | HTTP 404 |
| `FORBIDDEN` | Buyer không sở hữu order/role sai | Không | HTTP 403 |
| `INVALID_ORDER_TRANSITION` | State không cho phép command | Không | HTTP 409 |
| `IDEMPOTENCY_KEY_REUSED` | Redis key đã tồn tại nhưng không replay response | Không | HTTP 409 |
| `REFUND_CUSTOMER_REQUEST_ALREADY_USED` | Buyer one-shot guard đã set | Không | HTTP 409 |
| `REFUND_REQUIRES_ALL_ITEMS_DELIVERED` | Có item chưa delivered | Có sau khi giao đủ | HTTP 409 |
| `INVALID_REFUND_SCOPE` | Line không thuộc order, trùng, quantity không đúng full line, hoặc scope rỗng | Không | HTTP 400/409 |
| `ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED` | Inventory không thể atomic-release toàn bộ committed lines trước dispatch | Không | HTTP 409 |
| `PROMOTION_RECALCULATION_NEEDED` | Batch reserve promotion all-or-nothing thất bại | Có sau preview mới | HTTP 409 về Checkout |
| `PAYMENT_TIMEOUT` | Không có success trong 15 phút | Không trên workflow cũ | Cancel + có thể recovery lane |
| `REFUND_WINDOW_EXPIRED` | Payment provider từ chối refund | Theo Payment policy | Failure event/Ops |
| `MANUAL_VERIFICATION_REQUIRED` | Không có scope-inspection completion sau 30 ngày | Cần reconciliation/manual exception trên case hiện hữu; không tự bypass Payment gate | Alert/workflow state |

Timeout DB/Redis/Kafka/Temporal là lỗi hạ tầng, không phải domain error. Redis lỗi
không được tự động biến một transition không hợp lệ thành hợp lệ.

## 8. Ví dụ nghiệp vụ

1. Checkout token mới tạo order `PENDING_PAYMENT`; Inventory và Promotion reserve,
   Payment trả client secret. Success trong 15 phút commit `PAID`, sau đó deduct,
   confirm promotion và chuyển `PROCESSING`.
2. Hai `INVENTORY_SHIP_TO_CUSTOMER` trùng nhau cho cùng order/warehouse: Redis có
   thể chặn lần hai; nếu mất key, item CAS khiến lần hai không đổi state và không
   được phát completion trùng.
3. Buyer gửi return lần đầu khi tất cả item delivered: CAS guard thành công và
   workflow chờ hàng hoàn. Refund fail vẫn không mở quyền submit lần hai.
4. Payment success đến sau khi timeout đã ghi `CANCELLED(PAYMENT_TIMEOUT)`: không
   sửa checkout workflow cũ; start recovery, tái lập reservation rồi post-payment,
   hoặc vào manual verification nếu recovery không an toàn. Release 1 không có
   system auto-refund bypassing Inventory evidence.
5. Buyer refund selected full-quantity lines: Order không gọi Promotion; wallet/budget
   giữ nguyên; gross refund amount is capped at `final_amount`. Một line inspection
   `FAIL` vẫn không chặn Payment after Inventory completion.
