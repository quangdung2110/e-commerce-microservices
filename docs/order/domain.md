---
doc_id: order-domain
service: order
scope: target
status: implemented
owners:
  - Order Service team
evidence:
  - contracts/http/order/v1/openapi.yaml
  - contracts/http/order/internal/v1/openapi.yaml
  - java/services/order-service/src/main/java/com/ecommerce/order
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
| Payment attempt | Một attempt Payment có số tăng đơn điệu và transaction ID bất biến | Retry cùng attempt hoặc payment transaction của attempt khác |
| Refund scope | Tập `order_line_id` được Buyer chọn; mỗi line luôn full ordered quantity | SKU set hoặc partial quantity của một line |
| Refund case | Control plane trọn đời, duy nhất cho một order | Payment refund record do Payment sở hữu |
| Buyer return guard | Quyền submit return một lần của Buyer | Request idempotency key TTL 7 ngày |
| Capture/cancel race | Capture được Payment chứng minh trong lúc Order còn `CANCELLING` | Success bất thường sau terminal non-capture |
| Buyer tab | Read-model value duy nhất suy ra từ master/item/payment state | FSM state lưu trực tiếp |
| Compensation | Hoạt động đảo side effect đã hoàn tất trong saga | Database rollback xuyên service |

## 2. Đối tượng miền nghiệp vụ

`Order` là aggregate root cho trạng thái giao dịch, số tiền, payment read status,
checkout identity, cancel/return reason, sequence và one-shot guard. `OrderItem`
là entity con đại diện delivery unit theo kho và chỉ có status derived. `OrderItemLine` là
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
| Lệnh | Tạo payment attempt kế tiếp | Buyer owner | Durable workflow message vào OrderCheckoutWorkflow/Payment attempt |
| Truy vấn | Xem order detail | Buyer owner/Admin | Order query |
| Truy vấn | Search/filter order | Buyer/Admin | PostgreSQL query model |
| Lệnh | Hủy pre-payment order | Buyer/Admin | Checkout workflow |
| Lệnh | Force refund order đã trả tiền trước dispatch | Admin | OrderRefundWorkflow + Inventory atomic release |
| Lệnh | Return selected full-quantity lines | Buyer owner | Buyer guard + OrderRefundWorkflow |
| Lệnh | Áp payment result | Payment consumer | Checkout/refund workflow hoặc reconciliation owner đã định danh |
| Lệnh | Đánh dấu line đã physical dispatch | Inventory event | OrderLine Fulfillment FSM |
| Lệnh | Đánh dấu line đã giao khách | Delivery verified event | OrderLine Fulfillment FSM |
| Lệnh | Xác nhận scope đã final-inspect | Inventory event | OrderRefundWorkflow |
| Lệnh | Đóng/recover scope hoàn hàng hiện hữu | Order worker/approved Finance/Ops decision | OrderRefundWorkflow/OrderRefundScopeFinalizationWorkflow |
| Lệnh | Complete order | Delivery verified-proof consumer | Order aggregate; Inventory dispatch is insufficient |
| Lệnh | Quyết định financial reconciliation | Finance/Ops workload | Existing repair checkpoint only; never direct financial mutation |

## 4. Quy tắc và bất biến nghiệp vụ

| ID | Quy tắc/bất biến | Enforcement mục tiêu | Lỗi/kết quả vi phạm |
|---|---|---|---|
| `INV-ORD-001` | Một `checkout_token` chỉ có một checkout workflow/order logic | Unique DB + WorkflowID + durable workflow dispatch | Attach/replay workflow hoặc conflict |
| `INV-ORD-002` | Payment/refund consumer không commit master FSM; workflow là owner | Adapter boundary + row lock trong activity | Event chỉ được inbox/enqueue/dedup |
| `INV-ORD-003` | Buyer chỉ submit return một lần; guard không reset | CAS `buyer_return_locked_at` | `REFUND_CUSTOMER_REQUEST_ALREADY_USED` |
| `INV-ORD-004` | Mỗi order tối đa một refund case và một Payment refund transaction trong toàn bộ vòng đời | Lifetime PK refund case + unique txn + WorkflowID | Reject/attach existing case |
| `INV-ORD-005` | Buyer refund chỉ sau Inventory final-inspect toàn bộ selected scope; Admin automatic refund chỉ sau Inventory atomic release toàn order trước dispatch | Refund case state + evidence + Temporal wait | Tiếp tục chờ/manual verification hoặc reject no-mutation |
| `INV-ORD-006` | Order chỉ complete khi tất cả `order_item_lines` `DELIVERED` bằng verified Delivery fact | Lock/count + CAS master | Không transition/phát event |
| `INV-ORD-007` | Order fact đã đăng ký phải commit state, integration sequence và outbox cùng transaction. Notification command commit cùng milestone với monotonic notification sequence riêng, không tăng integration sequence | PostgreSQL transaction + multi-destination outbox | Rollback toàn transaction |
| `INV-ORD-008` | Customer refund không gọi/mutate Promotion; wallet và budget đã confirm giữ nguyên | Workflow boundary | Không phát promotion allocation |
| `INV-ORD-009` | Snapshot line/address/category không phụ thuộc dữ liệu upstream về sau; category chỉ dùng dựng lại Promotion context | Persist khi create | Reject create nếu snapshot thiếu |
| `INV-ORD-010` | `final_amount_minor = total_amount_minor - total_discount_minor`; tất cả money là integer VND | Domain validation | Reject pricing snapshot sai |
| `INV-ORD-011` | Reserve promotion chỉ sau submit/order tồn tại, không ở Preview | Checkout saga ordering | Không tạo reservation ở preview |
| `INV-ORD-012` | Voucher PLATFORM có nghĩa toàn đơn, không phân bổ xuống item/SKU để refund | Domain boundary | Không tạo `promotion_refund_allocations[]` |
| `INV-ORD-013` | Admin action với `PAID/PROCESSING` đi refund lane, không cancel pre-payment | Cancel command branching | `409` nếu transition sai |
| `INV-ORD-014` | Buyer return chỉ hợp lệ khi master `COMPLETED` và toàn bộ line đã `DELIVERED` bằng verified proof; Release 1 không có cửa sổ thời gian trả hàng | Locked read trước CAS/start workflow | `REFUND_REQUIRES_ALL_ITEMS_DELIVERED` |
| `INV-ORD-021` | Inventory allocation plan, bridge `(checkout_line_reference, order_line_id)` và `(order_line_id, warehouse_id)` mapping phải được persist trước Reserve/dispatch consumer | Checkout snapshot + reserve checkpoint | Reject/park mismatch; không query DB Inventory trực tiếp |
| `INV-ORD-015` | Mỗi selected `order_line_id` dùng đúng full original quantity; refund amount bằng tổng immutable `net_amount_minor` của selected lines sau largest-remainder discount allocation | Case-line net snapshot + calculation lúc checkout/lock | `INVALID_REFUND_SCOPE` |
| `INV-ORD-016` | Không tính lại discount/Promotion khi refund; inspection FAIL không chặn Payment command | Workflow boundary + locked amount | Không gọi Promotion; gọi Payment sau completion fact |
| `INV-ORD-017` | Automatic admin force refund bị cấm nếu Inventory không thể release tất cả committed lines trước dispatch trong một transaction | Inventory response evidence | `ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED` |
| `INV-ORD-018` | Payment fact phải khớp mapped attempt, order, amount, currency và financial version per transaction; consumer inbox durable | `order_payment_attempts` + inbox/version guard in same transaction | Stale/mismatched event rejected to reconciliation |
| `INV-ORD-019` | Sau Payment financial watermark, active Saga chỉ forward-repair; không Inventory rollback hay Promotion `CANCEL_FINAL` | Master recovery state + remote-command checkpoint | `FINANCIAL_RECONCILIATION_REQUIRED`, never business rollback |
| `INV-ORD-020` | Admin rejected pre-dispatch release không được tạo/refuse lifetime refund case | Durable admin attempt separate from refund case | Attempt terminal no-op; Buyer guard/case remains available |
| `INV-ORD-022` | Chỉ một captured transaction được refund; chỉ durable `FAILED` fact có thể mở attempt kế tiếp dưới `INV-ORD-028` | Attempt mapping + immutable captured ID | Second/foreign capture → reconciliation |
| `INV-ORD-023` | Finance/Ops chỉ resolve reconciliation bằng evidence + named audited decision; external payout phải khớp customer/VND/full locked amount/latest Payment version và không giả provider fact | Reconciliation case + authenticated contract + order/case lock | Resume proof-backed case, guarded no-payout closure, external remediation, manual fulfillment, or accounting escalation |
| `INV-ORD-024` | Payment dispute là exception orthogonal; không tự đổi master/payment/refund FSM, release resource hoặc tạo refund | 5 exact dispute types + durable inbox/version + reconciliation case | Park mismatch; Accounting/manual decision |
| `INV-ORD-025` | Buyer terminal refund result phải atomically lưu ý định đóng Inventory scope; only matching command receipt proves closure | Frozen command checkpoint + durable dispatch + finalization reconciler | Pending/recovery/conflict; never roll back Payment or create a second refund |
| `INV-ORD-026` | Kết thúc return thiếu hàng không payout cần named Ops decision, no accepted/unknown Payment initiation, và Inventory close/cancel guards | Locked case + query evidence + Inventory atomic command | Case `CLOSED_INCOMPLETE` only after receipt; no automatic 30-day close/Payment bypass |
| `INV-ORD-027` | Kafka fact đã accepted phải tạo durable workflow message trong cùng transaction với inbox; RPC signal không nằm trên correctness path | Inbox + workflow-message outbox + workflow message-ID dedup | Không ACK nếu durable message chưa commit |
| `INV-ORD-028` | Payment deadline immutable được cap bởi earliest resource-hold expiry trừ clock skew; attempt kế tiếp chỉ cho `FAILED` trước deadline và khi holds còn hiệu lực; `EXPIRED/CANCELED` đi cleanup | Stored provider expiry + order row lock + checkout workflow message | Reject retry hoặc cancel/reconcile; không tạo PaymentIntent thiếu resource |
| `INV-ORD-029` | Revision dedup có scope theo provider stream identity, không theo order chung | Unique `(source,event_type,stream_id,source_revision)` + `(source,event_id)` | Duplicate no-op; collision khác payload bị quarantine |
| `INV-ORD-030` | Mọi trạng thái không terminal có durable convergence owner; delivery/cleanup/repair không phụ thuộc một event cuối cùng được phát lại | Reconciler + checkpoints + `TryCompleteOrder` | Alert/manual obligation thay vì mắc kẹt im lặng |
| `INV-ORD-031` | Mỗi notification business `dedup_key` của một order chỉ được cấp đúng một notification sequence/event identity xuyên cả sau khi outbox đã trim | Durable `order_notification_commands` ledger + order row lock + payload digest | Replay trả identity cũ; digest collision bị quarantine, không tăng counter/phát command mới |
| `INV-ORD-032` | Workflow message duplicate không được double-apply qua run boundary/`Continue-As-New` | Carry per-stream high-watermark + bounded recent message IDs vào new run; DB activity idempotency/checkpoint | Duplicate no-op; collision/quá cũ bất thường bị quarantine/reconcile |
| `INV-ORD-034` | `cancel_reason` được gán deterministic: Buyer `USER_CANCEL`, Admin `ADMIN_FORCE_CANCEL`, deadline khi attempt cuối `PENDING` hoặc attempt `EXPIRED` là `PAYMENT_TIMEOUT`, deadline sau attempt `FAILED` hoặc provider `CANCELED` là `PAYMENT_FAILED`, lỗi trước `PAYMENT_READY` là `PRE_PAYMENT_SAGA_FAILED` | Checkout workflow + cancellation activity dưới order lock | Không có reason tự do; Payment/Inventory reason map theo `interfaces.md` §3 ([ADR-020](./decisions/ADR-020-release-1-implementation-clarifications.md)) |
| `INV-ORD-033` | Message gặp closed/terminal target không được restart saga hoặc bị drop mù | Workflow ID reuse reject-duplicate + DB-applied/superseded proof hoặc transactional reroute tới recovery owner | Proven no-op/reroute; ambiguous case giữ retry/quarantine + alert |

## 5. State machine

### 5.1. Master Order FSM

Các state: `CREATED` (transient), `PENDING_PAYMENT`, `CANCELLING`, `PAID`,
`PROCESSING`, `COMPLETED`, `CANCELLED`, `RETURN_REQUESTED`, `REFUNDED`,
`FINANCIAL_RECONCILIATION_REQUIRED`.

| ID | Từ | Đến | Trigger/guard | Side effect chính |
|---|---|---|---|---|
| `T1` | `CREATED` | `PENDING_PAYMENT` | `CREATE_ORDER_ACTIVITY_COMMITTED` | Persist snapshot; không phát integration event |
| `T2` | `PENDING_PAYMENT` | `PAID` | `PAYMENT_SUCCESS` trong workflow | `com.ecommerce.order.paid.v1` cùng transaction |
| `T2.1` | `PENDING_PAYMENT` | `PENDING_PAYMENT` | Active attempt `PAYMENT_FAILED` trước order payment deadline | Mark attempt terminal, `payment_status=FAILED`; giữ Inventory/Promotion hold và cho phép một next-attempt message |
| `T2.2` | `PENDING_PAYMENT` | `PENDING_PAYMENT` | Durable next-attempt command passes `INV-ORD-028` | Persist next attempt/checkpoint; `payment_status=PENDING` |
| `T3` | `PENDING_PAYMENT` | `CANCELLING` | Order payment deadline, active attempt `EXPIRED/CANCELED`, hoặc explicit cancel; `cancel_reason` theo `INV-ORD-034` | Persist cancel command/dispatch; resolve Payment non-capture before cleanup |
| `T3.1` | `CANCELLING` | `CANCELLED` | Matching `CANCELED`/`ALREADY_NON_CAPTURED` + cleanup proven | Persist cancellation; không phát event |
| `T3.2` | `CANCELLING` | `PAID` | Matching `ALREADY_SUCCEEDED`/`PAYMENT_SUCCESS` | `com.ecommerce.order.paid.v1`, forward repair |
| `T4` | `PENDING_PAYMENT` | `CANCELLING` | `USER_CANCEL` | Persist cancel command/dispatch |
| `T4.1` | `PENDING_PAYMENT` | `CANCELLING` | `ADMIN_FORCE_CANCEL` | Persist cancel command/dispatch |
| `T4.2` | `PENDING_PAYMENT` | `CANCELLING` | `PRE_PAYMENT_SAGA_FAILED` and Payment create is proven absent/non-captured | Persist independent Inventory/Promotion cleanup obligations |
| `T7` | `PAID` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_PRE_DISPATCH` + Inventory all-line release evidence | Tạo/khóa admin refund case |
| `T8` | `PAID` | `PROCESSING` | `POST_PAYMENT_SAGA_COMPLETED` | Persist state; không phát event |
| `T8.1` | `PAID/PROCESSING` | `FINANCIAL_RECONCILIATION_REQUIRED` | Post-capture repair cannot prove a required checkpoint | Preserve financial fact; no business compensation |
| `T8.2` | `PROCESSING` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_PRE_DISPATCH` + Inventory all-line release evidence | Vào admin refund lane |
| `T8.3` | `PROCESSING` | `COMPLETED` | Tất cả line `DELIVERED` bởi verified Delivery fact + CAS | `com.ecommerce.order.completed.v1` |
| `T8.4` | `FINANCIAL_RECONCILIATION_REQUIRED` | `PAID` | Proof-backed repair còn required post-capture obligations | Resume original checkpoint IDs; không mint command identity mới |
| `T8.5` | `FINANCIAL_RECONCILIATION_REQUIRED` | `PROCESSING` | Tất cả required post-capture obligations đã proven | Chạy `TryCompleteOrder` ở cùng hoặc bước convergence kế tiếp |
| `T9` | `COMPLETED` | `RETURN_REQUESTED` | Buyer one-shot pass + mọi line delivered | Khóa selected full-quantity line scope, chờ final inspection |
| `T10` | `RETURN_REQUESTED` | `REFUNDED` | `REFUND_SUCCESS` | Persist refund result; không phát event |
| `T10.1` | `RETURN_REQUESTED` | `RETURN_REQUESTED` | `REFUND_PROCESSING`, `REFUND_FAILED` hoặc reversed | Chỉ cập nhật `payment_status`/evidence |
| `T10.2` | `REFUNDED` | `RETURN_REQUESTED` | `REFUND_REVERSED` for the existing refund transaction | Same case enters manual verification; không phát event |
| `T10.3` | `RETURN_REQUESTED` | `REFUNDED` | Full-amount external payout evidence approved for existing `CUSTOMER_PAYOUT_REMEDIATION/REFUND_REVERSAL` | Case `EXTERNALLY_REMEDIATED`, `refunded_at=settled_at`; giữ `payment_status=REFUND_FAILED`; không phát provider-refund fact/notification |

Invalid transition không được “sửa” bằng update không điều kiện; trả conflict/no-op
theo ngữ cảnh và giữ state cũ. `REFUND_REVERSED` từ Payment được map thành
`payment_status=REFUND_FAILED`, lý do gốc giữ cho escalation.
Success evidence xuất hiện sau một Payment terminal non-capture không chạy `T3.2` và
không hồi sinh `CANCELLED`; nó tạo financial-reconciliation obligation vì Payment v2
không cho phép terminal provider intent capture trở lại.
Tên trigger legacy của T8.3 là `AUTO_COMPLETE_ALL_ITEMS_DELIVERED`; implementation mới
chỉ nhận delivery proof v1 per-line. Command action legacy cho hủy là `CANCEL_ORDER`.

### 5.2. Order Line Fulfillment FSM

State lưu trên `order_item_lines.fulfillment_status`: `PROCESSING` (initial),
`DISPATCHED`, `DELIVERED`. `order_items.status` là projection derived cho UI/grouping,
không được cập nhật trực tiếp từ một line fact.

| ID | Từ | Đến | Trigger/guard |
|---|---|---|---|
| `I1` | `PROCESSING` | `DISPATCHED` | Canonical Inventory physical-dispatch fact, đúng order + line + warehouse |
| `I2` | `DISPATCHED` | `DELIVERED` | [Delivery verified fact v1](../../contracts/events/delivery/v1/asyncapi.yaml), đúng order + line + warehouse + unique proof ID/digest |
Update I1/I2 dùng `WHERE fulfillment_status=:old`; duplicate/stale event là no-op sau
durable inbox. Inventory physical dispatch must never be mapped to customer delivery or
`ORDER_COMPLETED`. Sau mỗi line transition Order transactionally recalculate parent status
và gọi `TryCompleteOrder`. Cùng convergence guard chạy sau
`PAID/FINANCIAL_RECONCILIATION_REQUIRED -> PROCESSING`, vì delivery proof có thể đã
được persist trong lúc financial repair chưa hoàn tất. I2 hoặc convergence run cuối cùng
mới có thể CAS master `PROCESSING -> COMPLETED`.
Return/refund is recorded on `OrderRefundCase` and its selected line records, not
by changing a whole delivery unit state: one delivery unit can contain both selected
and unselected lines.

### 5.3. Payment read status

`payment_status` là read/UI state denormalized: `PENDING`, `PAID`, `FAILED`,
`REFUNDING`, `REFUNDED`, `REFUND_FAILED`. Cả Payment `REFUND_FAILED` và
`REFUND_REVERSED` map về `REFUND_FAILED` ở Order; when reversal arrives after master
`REFUNDED`, T10.2 reopens the same refund case for manual verification.

### 5.4. Payment attempt lifecycle

Order persists a numbered attempt before it exposes Payment client data. Retry init/cancel
always targets the same `payment_transaction_id`; `financial_state_version` only orders
facts from that transaction. Release 1 only permits a next attempt after `FAILED`, trước
immutable order payment deadline và khi Inventory/Promotion holds vẫn được giữ.
`EXPIRED` hoặc proven `CANCELED` đi cancellation cleanup và không retry-eligible.
`PAYMENT_SUCCESS` atomically freezes
`captured_payment_transaction_id`. A second success, an unknown transaction, a currency or
amount mismatch, or an event for a non-active attempt that cannot be explained as the
captured attempt is parked in `FINANCIAL_RECONCILIATION_REQUIRED`.

Payment dispute facts (`OPENED`, funds withdrawn/reinstated, won/lost) preserve the
current master and `payment_status`. They attach to a typed `PAYMENT_DISPUTE`
obligation and its append-only evidence/version stream under the order reconciliation
envelope. A dispute is not a
customer refund and cannot consume, reopen or create the lifetime refund transaction.

### 5.5. Refund case lifecycle

Buyer: `INITIATED -> WAITING_INVENTORY_SCOPE_INSPECTION ->
WAITING_PAYMENT_REFUND_RESULT -> SUCCEEDED|FAILED`; timeout goes to
`MANUAL_VERIFICATION_REQUIRED` and opens a `RETURN_MANUAL_VERIFICATION` reconciliation
obligation (also opened when Inventory durably rejects scope registration). Admin pre-dispatch: the release wait is the Admin attempt (`PENDING`); the lifetime case is
created after `RELEASED` and goes `INITIATED -> WAITING_PAYMENT_REFUND_RESULT ->
SUCCEEDED|FAILED` (`WAITING_INVENTORY_RELEASE` stays in the enum but is not stored,
ADR-020 §20).
The two Inventory evidences are mutually exclusive. A normal terminal failure stays
on the same lifetime case; a provider reversal after success changes `SUCCEEDED ->
MANUAL_VERIFICATION_REQUIRED` and reopens the master financial lane. No second scope
or refund transaction is allowed.

Buyer may also follow `MANUAL_VERIFICATION_REQUIRED -> CLOSED_INCOMPLETE` after
an approved `CLOSE_RETURN_WITHOUT_REFUND` decision and a matching Inventory
`CLOSED_INCOMPLETE` receipt, or `CANCELLED` receipt when no return was received.
The case remains manual until that receipt; master stays `RETURN_REQUESTED`, payment
`PAID`, refund transaction null and the Buyer guard remains consumed. This is a
closed case without payout, not master `REFUNDED`.

`FAILED|MANUAL_VERIFICATION_REQUIRED -> EXTERNALLY_REMEDIATED` is allowed only through
the audited external-financial-remediation decision for the existing payout/reversal
obligation, with immutable evidence that the customer received exactly the locked VND
amount. It may converge the open master lane back to `REFUNDED`, but preserves
`payment_status=REFUND_FAILED`, the original refund transaction/reversal and all evidence.
It emits neither the Payment-success-based `ORDER_REFUNDED` notification nor an Order fact.

Scope finalization is a separate control state:

| From | To | Guard |
|---|---|---|
| `NOT_REQUESTED` | `PENDING` | Financial result or approved no-payout decision atomically freezes intent/dispatch |
| `PENDING/RECOVERY_REQUIRED` | `COMPLETED` | Matching accepted receipt for the frozen command |
| `PENDING` | `RECOVERY_REQUIRED` | Retry budget exhausted; same command remains durable |
| `PENDING/RECOVERY_REQUIRED` | `CONFLICT` | Durable rejection or mismatched receipt; audited reconciliation required |
| `CONFLICT` | `COMPLETED` | Reconciliation recovers the matching accepted original-command receipt; no replacement |
| `CONFLICT` | `PENDING` | Corrected audited decision after proven rejection; new generation/command, prior evidence retained |
| `CONFLICT` | `NOT_REQUESTED` | Approved resume after proven no-payout command rejection and full completion evidence; clear active intent, retain rejected generation/history |

Recovery keeps the same command ID/body. Accepted/unknown commands cannot be replaced.
Admin uses only `NOT_REQUIRED`; `COMPLETED` has no outgoing Inventory transition.

`SUCCEEDED`/`FAILED` financial cases can therefore have unfinished finalization.
Complete Buyer return scope uses `CLOSE/REFUNDED` after success or `CLOSE/REJECTED`
after terminal failure/reversal without a prior intent; `REJECTED` is not inspection
`FAIL`. Reversal never changes a frozen close reason or reopens terminal Inventory.
Incomplete close is only `IN_PROGRESS`, missing expected units, and all received units
final-inspected; zero-receipt `REGISTERED` uses cancel. Pending no-payout intent blocks
Payment initiation/resume. See [WF-ORDER-008a](./workflows.md#wf-order-008a--durable-refund-scope-finalization)
and [ADR-013](./decisions/ADR-013-durable-refund-scope-finalization.md).

### 5.6. Buyer tab precedence

Mỗi order chỉ thuộc một tab, chọn theo thứ tự:

| Ưu tiên | Điều kiện | Tab |
|---|---|---|
| 1 | `PENDING_PAYMENT/CANCELLING` | `TO_PAY` (cancelling shows pending resolution) |
| 2 | `PAID/PROCESSING` và chưa line nào `DISPATCHED/DELIVERED` | `TO_SHIP` |
| 3 | `PAID/PROCESSING` và có ít nhất một line `DISPATCHED/DELIVERED` | `TO_RECEIVE` |
| 4 | `COMPLETED` và không trong return/refund | `COMPLETED` |
| 5 | `CANCELLED` | `CANCELLED` |
| 6 | `RETURN_REQUESTED/REFUNDED/FINANCIAL_RECONCILIATION_REQUIRED` hoặc payment refund state | `RETURN_REFUND`/manual-review UI |

Optional UI sort rank: `TO_PAY=10`, `TO_SHIP=20`, `TO_RECEIVE=30`,
`RETURN_REFUND=40`, `COMPLETED=50`, `CANCELLED=60`, fallback rank `99`. Rank chỉ
phục vụ sắp xếp; tab luôn được derive bằng bảng precedence phía trên, không ánh xạ
trực tiếp từ một master/line state đơn lẻ.

## 6. Quy tắc phân quyền

- Buyer chỉ đọc/search order của chính mình (`orders.user_id=current_user_id`).
- Admin có full read/search scope.
- Buyer chỉ hủy order của mình khi `PENDING_PAYMENT`; Admin hủy pre-payment hoặc
  chỉ mở automatic force-refund khi `PAID/PROCESSING` và Inventory xác nhận tất cả
  lines chưa dispatch.
- Chỉ Buyer owner được gọi return; body phải chọn `order_line_id` thuộc order với
  full quantity đúng snapshot. Không có `FULL/PARTIAL` enum.
- Admin manual exception cho mixed/any-dispatched order là outside automatic Release 1
  and follows the financial reconciliation decision/audit lane; it cannot alter Payment or
  Promotion state directly.
  và không được bypass Inventory evidence.
- Checkout, Payment, Inventory, Promotion và Temporal worker dùng service identity do
  service mesh thực thi theo principal (Order gọi Inventory/Promotion/Payment với principal
  `order-service`; chỉ `checkout-service` được gọi
  `POST /internal/v1/checkout-order-submissions` —
  [mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
  public client không được giả mạo trusted headers hoặc signal, và `X-Service-Identity` chỉ là
  attribution.

## 7. Lỗi miền nghiệp vụ

| Mã lỗi | Điều kiện | Retryable | Ánh xạ |
|---|---|---|---|
| `NOT_FOUND` | Order không tồn tại hoặc không visible | Không | HTTP 404 |
| `FORBIDDEN` | Buyer không sở hữu order/role sai | Không | HTTP 403 |
| `INVALID_ORDER_TRANSITION` | State không cho phép command | Không | HTTP 409 |
| `IDEMPOTENCY_KEY_REUSED` | Durable request record đã tồn tại nhưng không replay được response, hoặc canonical payload khác | Không | HTTP 409 |
| `REFUND_CUSTOMER_REQUEST_ALREADY_USED` | Buyer one-shot guard đã set | Không | HTTP 409 |
| `REFUND_REQUIRES_ALL_ITEMS_DELIVERED` | Có order line chưa delivered (legacy error-code name) | Có sau khi giao đủ | HTTP 409 |
| `INVALID_REFUND_SCOPE` | Line không thuộc order, trùng, quantity không đúng full line, hoặc scope rỗng | Không | HTTP 422 |
| `ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED` | Inventory không thể atomic-release toàn bộ committed lines trước dispatch | Không | HTTP 409 |
| `PROMOTION_RECALCULATION_NEEDED` | Batch reserve promotion all-or-nothing thất bại | Có sau preview mới | HTTP 409 về Checkout |
| `PAYMENT_TIMEOUT` | Không có success trước immutable payment deadline | Không | Vào `CANCELLING`, resolve terminal non-capture rồi cleanup; success bất thường sau terminal non-capture chỉ mở reconciliation obligation |
| `REFUND_WINDOW_EXPIRED` | Payment provider từ chối refund | Theo Payment policy | Failure event/Ops |
| `MANUAL_VERIFICATION_REQUIRED` | Không có scope-inspection completion sau 30 ngày | Cần reconciliation/manual exception trên case hiện hữu; không tự bypass Payment gate | Alert/workflow state |
| `REFUND_SCOPE_FINALIZATION_CONFLICT` | Inventory từ chối frozen close/cancel guard hoặc evidence không khớp | Cần audited reconciliation; không đổi endpoint/reason bằng retry | Finalization `CONFLICT`; operation manual review |

Timeout DB/Redis/Kafka/Temporal là lỗi hạ tầng, không phải domain error. Redis lỗi
chỉ làm mất request replay fast-path; fallback durable phải được dùng. Redis không được
tự động biến một transition không hợp lệ thành hợp lệ.

## 8. Ví dụ nghiệp vụ

1. Checkout token mới tạo order `PENDING_PAYMENT`; Inventory và Promotion reserve,
   Payment trả client secret. Matching success trong 15 phút commit `PAID`, sau đó
   Inventory CommitPayment và Promotion confirm theo forward repair rồi `PROCESSING`.
2. Hai Inventory line-dispatch event trùng nhau: durable inbox/revision chặn lần hai;
   line CAS vẫn khiến retry vô hại. Fact chỉ chuyển line sang `DISPATCHED`, không phát
   delivery/completion.
3. Buyer gửi return lần đầu khi tất cả line delivered: CAS guard thành công và
   workflow chờ hàng hoàn. Refund fail vẫn không mở quyền submit lần hai.
4. Payment capture thắng race khi order còn `CANCELLING`: Payment trả
   `ALREADY_SUCCEEDED`/fact success, Order không release resource mà chuyển sang paid
   forward repair. Một success bất thường sau terminal non-capture/`CANCELLED` chỉ tạo
   reconciliation evidence; không tự hồi sinh order hay re-reserve Inventory.
5. Buyer refund selected full-quantity lines: Order không gọi Promotion; wallet/budget
   giữ nguyên; refund amount là tổng immutable selected-line net amounts. Một line inspection
   `FAIL` vẫn không chặn Payment after Inventory completion.
