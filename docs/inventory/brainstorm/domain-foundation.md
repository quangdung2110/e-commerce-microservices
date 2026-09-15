---
doc_id: inventory-domain-foundation
service: inventory
scope: target
status: proposed
owners:
  - Inventory Service team
reviewers:
  - Order Service team
  - Payment Service team
  - Warehouse operations
evidence:
  - docs/inventory/brainstorm/design-charter.md
  - docs/inventory/brainstorm/context-map.md
verified_against_commit: null
---

# Inventory Service — Domain Foundation (Phase 2)

> Working document cho glossary, stock bucket, aggregate và invariant của thiết kế
> đích. Nội dung ghi **Đã chốt** là domain decision đã được owner xác nhận; các phần
> còn lại vẫn là proposal cho tới khi được chốt. Không mô tả schema hoặc implementation.

## 1. Inputs đã chốt từ Phase 0–1

- Không oversell là invariant tuyệt đối.
- Stock theo warehouse; một line item phải fulfil trọn vẹn từ đúng một kho.
- Reserve toàn order là all-or-nothing: thiếu một line item là `REJECT_ALL`, không
  backorder hoặc partial fulfillment ở release đầu.
- `REJECT_ALL` chỉ nói về việc chấp nhận/giữ stock ban đầu: toàn bộ line phải reserve
  được trong cùng một operation. Mỗi line lấy đủ quantity từ một warehouse, nhưng các
  line khác nhau có thể ở các warehouse khác nhau và dispatch độc lập sau payment.
- Reservation timeout 15 phút do Order Saga/Orchestrator sở hữu; Inventory không tự
  release, chỉ alert orphan sau 60 phút.
- Shipment/delivery state thuộc Order, nhưng Inventory vẫn phải ghi nhận **physical
  stock movement** tại thời điểm được domain model xác định.
- Hàng hoàn receipt vào inspection/quarantine, tuyệt đối chưa sellable ngay.
- Stock adjustment tăng/giảm là admin-only, có reason code và audit.
- Mọi command order mutation đi qua Order Temporal Saga Worker và idempotent.

## 2. Ubiquitous language

| Term | Definition | Không được nhầm với |
|---|---|---|
| Warehouse | Kho do Inventory sở hữu master và operational state. | Địa chỉ giao khách. |
| Stock position | Tồn kho của một `sku_id` tại một `warehouse_id`. | Tổng stock toàn hệ thống. |
| On hand | Đơn vị vật lý hiện có tại kho, bao gồm cả đơn vị đang reserved, committed hoặc quarantine. | Số có thể bán. |
| Available to sell | Đơn vị còn có thể reserve cho order mới. | On hand. |
| Reservation | Giữ tạm stock cho order trước payment; hết hạn/lỗi payment có thể release. | Payment authorization. |
| Committed allocation | Stock của order đã payment-success nhưng còn nằm tại kho, chờ physical dispatch. | Shipment/delivery state. |
| Quarantine | Hàng vật lý đã receipt nhưng không sellable cho tới inspection pass. | Hàng sellable hoặc disposal. |
| Physical dispatch | Movement giảm `on_hand` khi một line allocation thực sự rời kho. | Order `SHIPPED`/`DELIVERED` state. |
| Stock movement | Fact audit bất biến giải thích một thay đổi của stock bucket. | State hiện tại. |
| Refund scope | Tập selected order line bất biến của đúng một refund case lifetime; mỗi line full quantity, owner là Order. | SKU set hoặc partial quantity. |
| Inspection completion | Fact rằng tất cả item trong refund scope đã có kết quả inspection. | Refund payout hoặc restock sellable. |

## 3. Stock bucket model — Accepted

### 3.1 Quyết định đã chốt

`on_hand` chỉ có một nghĩa: số lượng vật lý hiện vẫn ở kho. Payment không được làm
giảm `on_hand`; physical dispatch mới làm điều đó.

```text
available_to_sell = on_hand - reserved - committed - quarantine
```

Trong đó tất cả bucket là số nguyên không âm. `reserved`, `committed` và `quarantine`
là các phần loại trừ nhau của `on_hand`.

| Bucket | Bán được ngay? | Hàng có còn vật lý ở kho? | Ý nghĩa |
|---|---:|---:|---|
| `available_to_sell` | Có | Có | Có thể reserve cho order mới. |
| `reserved` | Không | Có | Chờ payment trong tối đa 15 phút. |
| `committed` | Không | Có | Payment đã thành công; chờ physical dispatch. |
| `quarantine` | Không | Có | Return receipt, chờ inspection pass/fail. |
| Đã dispatch | Không | Không | Không còn là một bucket tồn kho; `on_hand` đã giảm. |

### 3.2 Lý do chọn

- Không gọi stock đã payment-success là “physical đã giảm” khi hàng vẫn nằm ở kho.
- Tách rõ Order payment/delivery state khỏi Inventory physical movement mà không làm
  Inventory sở hữu shipment lifecycle.
- Cancellation/refund trước dispatch có thể chuyển `committed` trở lại `available_to_sell`
  theo command hợp lệ, không cần dựng lại `on_hand`.
- Return receipt tăng `on_hand` và `quarantine`, nhưng không tăng sellable cho tới inspection pass.

### 3.3 Ví dụ xuyên suốt — 100 đơn vị tại một kho

Kho A bắt đầu có 100 đơn vị SKU-X đều sellable. Một order reserve rồi thanh toán 2 đơn vị;
sau đó thủ kho dispatch chúng. `available_to_sell` giảm từ lúc reserve, còn `on_hand` chỉ
giảm lúc hàng thực sự rời kho.

| Thời điểm | `on_hand` | `available_to_sell` | `reserved` | `committed` | `quarantine` | Diễn giải |
|---|---:|---:|---:|---:|---:|---|
| Ban đầu | 100 | 100 | 0 | 0 | 0 | Toàn bộ hàng có thể bán. |
| Order reserve 2 | 100 | 98 | 2 | 0 | 0 | Hai đơn vị vẫn ở kho nhưng không bán cho order khác. |
| Payment success | 100 | 98 | 0 | 2 | 0 | Hàng đã thuộc order đã trả tiền, vẫn chờ physical dispatch. |
| Physical dispatch 2 | 98 | 98 | 0 | 0 | 0 | Hai đơn vị thực sự rời kho; Inventory ghi movement dispatch. |

Nếu hai đơn vị đã dispatch được khách trả lại, flow tiếp tục như sau:

| Thời điểm | `on_hand` | `available_to_sell` | `reserved` | `committed` | `quarantine` | Diễn giải |
|---|---:|---:|---:|---:|---:|---|
| Return receipt 2 | 100 | 98 | 0 | 0 | 2 | Hàng đã về kho nhưng bị cách ly, chưa được bán lại. |
| Inspection pass 2 | 100 | 100 | 0 | 0 | 0 | Hàng được phép trở lại sellable stock. |

Outcome của inspection fail được chốt tại `INV-OPS-010`: quantity fail ở `quarantine` cho tới
khi Admin `HOLD`, `DISPOSE` hoặc `RETURN_TO_SUPPLIER` theo evidence bắt buộc.

## 4. State transitions và policy đã chốt

| Trigger / command | `on_hand` | `available_to_sell` | `reserved` | `committed` | `quarantine` | Ghi chú |
|---|---:|---:|---:|---:|---:|---|
| Restock sellable | `+q` | `+q` | — | — | — | Receipt hàng mới/sellable. |
| Reserve | — | `-q` | `+q` | — | — | Order command; all-or-nothing toàn order. |
| Release do payment fail / expiry / stock discrepancy | — | `+q` | `-q` | — | — | Chỉ Order Saga, chỉ khi root `RESERVED`. `STOCK_DISCREPANCY` yêu cầu `order_exception_id` và release toàn root atomically. |
| Commit sale sau payment | — | — | `-q` | `+q` | — | Payment không đổi physical stock. |
| Cancel/refund trước dispatch | — | `+q` | — | `-q` | — | Chỉ Order Saga; normal reason là `ORDER_CANCELLED` hoặc `REFUND_BEFORE_DISPATCH`. Nếu order đã partial dispatch, bắt buộc `POST_DISPATCH_EXCEPTION` kèm `order_exception_id`; target line luôn phải `COMMITTED` và chưa physical dispatch. |
| Physical dispatch | `-q` | — | — | `-q` | — | Chỉ Warehouse Admin ghi `RecordPhysicalDispatch` tại warehouse đã allocation; một line có thể dispatch độc lập, Order vẫn sở hữu delivery state. |
| Return receipt | `+q` | — | — | — | `+q` | Scope phải đã được Order đăng ký. |
| Inspection pass | — | `+q` | — | — | `-q` | Hàng trở lại sellable stock. |
| Inspection fail | — | — | — | — | — | Ghi `FAILED` nhưng vẫn giữ hàng trong quarantine; fail không tự làm hàng rời kho. |
| Final disposition: dispose / return-to-supplier | `-q` | — | — | — | `-q` | Admin chọn disposition khi hàng thực sự rời kho; cần reason code và audit. |
| Stock adjustment tăng | `+q` | `+q` | — | — | — | Admin-only; phải có reason code và audit. |
| Stock adjustment giảm | `-q` | `-q` | — | — | — | Chỉ được lấy từ `available_to_sell`, với `q <= available_to_sell`. |

`—` nghĩa là không đổi. Giảm stock qua adjustment thông thường không được đụng tới
`reserved`, `committed` hay `quarantine`. Một thiếu hụt vật lý liên quan các bucket đó
phải đi qua exception workflow có chủ đích: xác định allocation/return bị ảnh hưởng,
để Order Saga xử lý commitment với khách nếu cần, sau đó mới tạo movement phù hợp.

### 4.1 Guard bắt buộc

1. `ReleaseReservation` chỉ hợp lệ từ root `RESERVED` với reason `PAYMENT_FAILED`,
   `RESERVATION_EXPIRED` hoặc `STOCK_DISCREPANCY`. Reason `STOCK_DISCREPANCY` bắt buộc có
   `order_exception_id` và luôn release toàn root. `ReleaseCommittedAllocation` chỉ được Order Saga gửi khi
   Inventory xác nhận quantity chưa từng physical dispatch: normal reason là
   `ORDER_CANCELLED` hoặc `REFUND_BEFORE_DISPATCH`; khi một line khác đã dispatch thì
   bắt buộc `POST_DISPATCH_EXCEPTION` kèm `order_exception_id`. Transition là
   `committed → available_to_sell`; nó không thay thế payment refund.
2. `InspectionFailed` là kết quả đánh giá chất lượng, không phải physical disposition.
   Hàng vẫn thuộc `quarantine` tới khi Admin quyết định `DISPOSE`,
   `RETURN_TO_SUPPLIER` hoặc `HOLD`.
3. Adjustment giảm bị từ chối nếu `quantity > available_to_sell`. Không được âm thầm
   giảm `on_hand` làm tổng `reserved + committed + quarantine` lớn hơn `on_hand`.
4. `RecordPhysicalDispatch` chỉ do Warehouse Admin thực hiện khi hàng thực sự rời
   warehouse đã allocation; line phải `COMMITTED`, command idempotent và có physical
   dispatch audit/movement. Inventory phát fact cho Order, không tự đặt shipment state.
   Warehouse `inactive`/`outage` sau allocation không phủ nhận fact này và không tự
   release/re-route reservation hoặc committed allocation có sẵn.

## 5. Aggregate, consistency boundary và reservation lifecycle

| Domain object | Identity | Trách nhiệm | Ghi chú |
|---|---|---|---|
| Warehouse | `warehouse_id` | Master data và active/inactive/outage state. | Aggregate độc lập. |
| Stock position | `(sku_id, warehouse_id)` | Giữ các bucket và enforce invariant local. | Đơn vị tranh chấp/concurrency chính. |
| Order reservation | `order_id` | Root lifecycle cho một lần giữ stock toàn order; mang `expires_at` và command history. | Chỉ tồn tại sau ReserveOrder thành công. |
| Reservation line | `(order_id, order_line_id)` | Allocation bất biến `sku_id`, quantity, `warehouse_id` và lifecycle vật lý của đúng một line. | Đúng một warehouse cho toàn quantity của line. |
| Refund scope | `order_id` lifetime unique, `refund_request_id` unique | Lưu immutable selected full-quantity scope được Order đăng ký và tiến trình receipt/inspection. | Không sở hữu refund payout/lifecycle. |
| Stock movement | `movement_id` | Audit append-only cho mọi stock mutation. | Không là aggregate mutable. |

`ReserveOrder` là một inventory transaction/business operation, bao gồm OrderReservation,
tất cả ReservationLine và mọi StockPosition được chọn cho order. Kết quả phải
all-or-nothing: hoặc tất cả allocation được tạo cùng tất cả bucket mutation, hoặc không
tạo reservation line và không có stock mutation nào sau `REJECT_ALL`. Phase 6 chỉ chọn
cơ chế kỹ thuật để thực hiện boundary này; semantic atomicity đã được chốt ở Phase 2.

### 5.1 State machine của ReservationLine

```text
RESERVED ── payment success ──> COMMITTED ── physical dispatch ──> DISPATCHED
    │                                │
    └────────────── release ─────────┘
                         ↓
                     RELEASED
```

| State | Ý nghĩa | Bucket tương ứng | Transition hợp lệ |
|---|---|---|---|
| `RESERVED` | Hàng được giữ cho order trước payment. | `reserved` | `CommitPayment` hoặc `ReleaseOrderReservation`. |
| `COMMITTED` | Payment đã thành công; hàng còn vật lý tại kho và chờ dispatch. | `committed` | `RecordPhysicalDispatch` do Warehouse Admin tại warehouse allocation, hoặc `ReleaseCommittedAllocation` trước dispatch do Order Saga. |
| `DISPATCHED` | Toàn bộ quantity của line allocation đã thực sự rời kho. | Không còn bucket; `on_hand` đã giảm. | Không release trực tiếp; return đi qua RefundScope. |
| `RELEASED` | Allocation được trả về sellable stock trước dispatch. | Không còn `reserved`/`committed`; quantity quay lại `available_to_sell`. | Terminal. |

`expires_at` là deadline nghiệp vụ của state `RESERVED`, không phải state `EXPIRED` tự
động. Sau deadline 15 phút, `CommitPayment` mới bị từ chối; Order Saga phải phát lệnh
release rõ ràng. Retry cùng `command_id` của một commit đã thành công vẫn trả lại kết quả
gốc, kể cả khi retry đến sau deadline.

### 5.2 State tổng hợp của OrderReservation

| State | Điều kiện dẫn xuất từ các ReservationLine | Ý nghĩa |
|---|---|---|
| `RESERVED` | Mọi line đều `RESERVED`. | Toàn order đang chờ payment. |
| `COMMITTED` | Mọi line đều `COMMITTED`. | Payment toàn order đã thành công; chưa line nào dispatch. |
| `PARTIALLY_DISPATCHED` | Có ít nhất một line `DISPATCHED` nhưng không phải toàn bộ line đều `DISPATCHED`. | Fact inventory: các kho đang dispatch độc lập; không phải shipment/delivery state. |
| `DISPATCHED` | Mọi line đều `DISPATCHED`. | Toàn bộ allocation đã rời kho. |
| `RELEASED` | Mọi line đều `RELEASED`; không line nào dispatch. | Order reservation đã giải phóng toàn bộ. |

`CommitPayment` chuyển toàn bộ line `RESERVED → COMMITTED` trong cùng operation; không
được có order với một line `COMMITTED` còn line khác `RESERVED`. `DispatchLineAllocation`
chỉ chuyển line thuộc warehouse đã allocation và không bắt Inventory sở hữu trạng thái
shipment hoặc delivery của Order.

### 5.3 RefundScope và inspection lifecycle

RefundScope là aggregate tách biệt OrderReservation. Order Saga phải đăng ký scope bất
biến tại Inventory trước bất kỳ return receipt nào. Scope release đầu luôn chứa toàn bộ
order line và toàn bộ quantity của từng line; Inventory không gọi Order để kiểm tra lại
scope, chỉ enforce nội dung scope đã được đăng ký.

```text
REGISTERED ── receipt đầu tiên ──> IN_PROGRESS ── mọi item final inspected ──> INSPECTION_COMPLETED ── Order Saga CloseRefundScope ──> CLOSED
     │                                  │
     └── cancel trước receipt ──> CANCELLED
                                        │
                       một phần hàng không về; mọi hàng đã nhận final inspected
                                        ↓
                            Order Saga CloseIncompleteRefundScope
                                        ↓
                            CLOSED_INCOMPLETE
```

| RefundScope state | Điều kiện / transition | Quy tắc |
|---|---|---|
| `REGISTERED` | `RegisterRefundScope` thành công; chưa return receipt nào. | Một order không thể có quá một scope đang giữ refund lock; scope và các line không được sửa. |
| `IN_PROGRESS` | Receipt hợp lệ đầu tiên được Warehouse Admin nhận. | Có thể receipt và inspection nhiều kiện/lần; không tự close khi còn item chưa về. |
| `INSPECTION_COMPLETED` | Mọi quantity kỳ vọng trong scope đã có kết quả `PASS` hoặc `FAIL`. | Terminal cho receipt/inspection; tạo đúng một `AllItemsInspectionCompleted` fact qua transactional outbox, nhưng vẫn giữ refund lock. |
| `CLOSED` | Chỉ Order Saga gửi `CloseRefundScope` sau khi refund request đạt trạng thái cuối tại Order. | Terminal; có `terminal_reason` như `REFUNDED`, `REJECTED` hoặc `CANCELLED`; giải phóng quyền đăng ký scope tiếp theo. |
| `CANCELLED` | Order Saga hủy scope khi toàn bộ line có `received_quantity = 0`. | Terminal; từ chối cancel khi đã có receipt để không che giấu stock/quarantine mutation; giải phóng refund lock. |
| `CLOSED_INCOMPLETE` | Chỉ Order Saga gửi `CloseIncompleteRefundScope` khi còn quantity chưa receipt nhưng mọi quantity đã receipt đã final inspected. | Terminal; có reason như `RETURN_WINDOW_EXPIRED`, `PACKAGE_LOST`, `CUSTOMER_WITHDREW`; không phát completion fact và giải phóng refund lock. |

Mỗi RefundScopeLine mang `order_line_id`, `sku_id`, `expected_quantity` bất biến cùng
ba progress counter `received_quantity`, `passed_quantity`, `failed_quantity`. Một line
được xem là inspection completed khi `received_quantity = expected_quantity` và
`passed_quantity + failed_quantity = expected_quantity`.

```text
0 ≤ passed_quantity + failed_quantity ≤ received_quantity ≤ expected_quantity
```

| Command / fact | Preconditions chính | Stock mutation | Scope/line progress |
|---|---|---|---|
| `RegisterRefundScope` | Từ Order Saga, idempotent; không có scope khác cùng order đang giữ refund lock (`REGISTERED`, `IN_PROGRESS`, `INSPECTION_COMPLETED`). | Không có. | Tạo scope `REGISTERED` và các line bất biến. |
| `RecordReturnReceipt` | Từ Admin; scope `REGISTERED`/`IN_PROGRESS`, actual SKU khớp immutable scope line, `q` không vượt quantity chưa receipt. | `on_hand +q`, `quarantine +q` tại warehouse thực tế nhận hàng. | `received_quantity +q`; receipt đầu tiên đưa scope thành `IN_PROGRESS`. Wrong SKU/line hoặc over-receipt từ chối toàn command, không mutation. |
| `RecordInspectionPass` | Từ Admin; tham chiếu receipt line chưa final-inspected tại đúng warehouse nhận. | `quarantine -q`, `available_to_sell +q`. | `passed_quantity +q`; `PASS` là final, không re-inspect fail thành pass ở Release 1. |
| `RecordInspectionFail` | Từ Admin; tham chiếu receipt line chưa final-inspected tại đúng warehouse nhận. | Không đổi bucket; hàng vẫn `quarantine`. | `failed_quantity +q`; `FAIL` là final và chờ disposition. |
| `RecordQuarantineDisposition` | Từ Admin; quantity failed chưa disposition tại đúng warehouse, với reason/evidence bắt buộc. | `HOLD`: không đổi; `DISPOSE`/`RETURN_TO_SUPPLIER`: `on_hand -q`, `quarantine -q`. | Không đổi progress/completion fact của RefundScope. |
| `ReverseQuarantineDisposition` | Từ Admin; tham chiếu `DISPOSE`/`RETURN_TO_SUPPLIER` chưa reverse, có physical verification item đang ở cùng warehouse. | `on_hand +q`, `quarantine +q`. | Không đổi inspection fail history hoặc RefundScope completion; không trở về sellable trực tiếp. |
| `CompleteInspection` | Điều kiện completion đúng với toàn bộ RefundScopeLine. | Không có ngoài outbox record. | Scope thành `INSPECTION_COMPLETED`; fact completion chỉ tạo một lần. |
| `CloseRefundScope` | Từ Order Saga, idempotent; scope đang `INSPECTION_COMPLETED`; mang terminal reason của Order. | Không có. | Scope thành `CLOSED`; chỉ lúc này refund lock được giải phóng. |
| `CloseIncompleteRefundScope` | Từ Order Saga, idempotent; scope `IN_PROGRESS`, còn `received < expected`, và mọi received quantity đã final inspected. | Không có stock mutation mới. | Scope thành `CLOSED_INCOMPLETE`; không tạo `AllItemsInspectionCompleted`. |

Receipt vật lý có thể đến qua nhiều kiện dù refund scope là tập selected full-quantity
lines. Đây không phải partial quantity: Inventory chỉ nhận cumulative receipt không
vượt quantity đã đăng ký. Nếu một phần hàng không bao giờ về kho, scope giữ `IN_PROGRESS`; Inventory
không tự đóng scope, không tự refund và không phát completion event. Sau khi Order đã
quyết định terminal reason, Order Saga có thể gửi `CloseIncompleteRefundScope` nhưng chỉ
khi mọi hàng đã receipt đã final inspected. Transition này thành `CLOSED_INCOMPLETE`,
không phải `INSPECTION_COMPLETED` và không có completion fact.

Inspection fail không ngăn scope completion: nó là kết quả inspection cuối cùng. Quantity
fail vẫn ở `quarantine` cho đến khi Admin dùng `DISPOSE`, `RETURN_TO_SUPPLIER` hoặc `HOLD`.
`HOLD` không phải sellable state và không terminal; physical disposition cần evidence rằng hàng
đã rời kho. Nếu disposition được ghi sai hoặc supplier trả item về, chỉ reversal chuyên biệt với
physical verification mới đưa hàng về `quarantine`, không phải sellable. Các disposition này
không thay đổi fact completion đã phát và không thuộc refund payout.

`INSPECTION_COMPLETED` không phải là kết thúc refund request: Order/Payment vẫn có thể
đang quyết định hoặc xử lý payout. Inventory không suy luận kết quả đó. Sau khi request
đạt trạng thái cuối ở Order, chỉ Order Saga mới được gửi `CloseRefundScope`; Admin và
Payment không có quyền gọi transition này.

## 6. Domain invariants đã chốt

| ID | Invariant | Điểm enforce do Inventory sở hữu | Hệ quả |
|---|---|---|---|
| `INV-DOM-001` | Với mọi stock position: `available_to_sell + reserved + committed + quarantine == on_hand`, và mọi bucket `>= 0`. | Mọi stock-bucket mutation trên StockPosition. | Không oversell và không tạo stock âm. |
| `INV-DOM-002` | Một line item active có đúng một warehouse allocation, đủ toàn bộ quantity. | Allocation validation của `ReserveOrder`/ReservationLine. | Không split fulfillment ở release đầu. |
| `INV-DOM-003` | ReserveOrder thành công toàn bộ hoặc không để lại ReservationLine/stock bucket mutation nào. | Atomic boundary của `ReserveOrder`. | `REJECT_ALL` thực sự là all-or-nothing. |
| `INV-DOM-004` | Cùng command ID chỉ áp dụng business mutation một lần. | Command history/idempotency record cùng mutation. | Temporal/client retry an toàn. |
| `INV-DOM-005` | Return receipt không có immutable refund scope đã đăng ký bị từ chối. | RefundScope validation trước receipt. | Không thể inspection/refund một item ngoài scope. |
| `INV-DOM-006` | Hàng return không tăng sellable cho tới inspection pass. | Receipt và inspection transition. | Quarantine là mandatory. |
| `INV-DOM-007` | Mọi transition bucket đều tạo immutable stock movement/audit fact. | Transaction mutation workflow. | Reconciliation và audit không phụ thuộc log ngắn hạn. |
| `INV-DOM-008` | Reserve chấp nhận toàn bộ order trong một operation; sau payment, line allocation có thể physical dispatch độc lập theo warehouse. | OrderReservation root và ReservationLine lifecycle. | Không có partial acceptance/backorder, nhưng không ép các kho dispatch đồng thời. |
| `INV-DOM-009` | `CommitPayment` chuyển mọi line của cùng OrderReservation từ `RESERVED` sang `COMMITTED` trong cùng operation. | OrderReservation state transition. | Không tồn tại payment-success với line committed một phần. |
| `INV-DOM-010` | Commit mới sau `expires_at` bị từ chối; expiry không tự release stock. | Expiry guard của `CommitPayment`; Order Saga gửi release. | Reservation timeout 15 phút không thành auto-cleanup ngầm. |
| `INV-DOM-011` | Mỗi order có nhiều nhất một RefundScope trong toàn bộ retained history; scope selected lines đã đăng ký là immutable và mỗi line full quantity. | Lifetime uniqueness theo `order_id`, Register/cancel/close transition. | Không có request thứ hai, kể cả sau terminal closure. |
| `INV-DOM-012` | Với mọi RefundScopeLine: `0 ≤ passed + failed ≤ received ≤ expected`; receipt và inspection không vượt quantity scope. | Return receipt và inspection command validation. | Không nhận trùng/quá số lượng hàng hoàn. |
| `INV-DOM-013` | `AllItemsInspectionCompleted` chỉ tồn tại khi mọi RefundScopeLine final inspected và chỉ được tạo một lần. | Terminal transition cùng transactional outbox record. | Order/Payment chỉ nhận fact completion đầy đủ, có thể retry an toàn. |
| `INV-DOM-014` | Chỉ Order Saga có thể chuyển scope `INSPECTION_COMPLETED → CLOSED`; terminal closure vẫn giữ lifetime uniqueness. | `CloseRefundScope` authorization, state guard và retained identity. | Inspection completion/closure không mở refund request thứ hai. |
| `INV-DOM-015` | Chỉ Warehouse Admin được ghi physical dispatch của ReservationLine `COMMITTED` tại đúng warehouse allocation; command tạo physical movement/audit fact idempotent. | `RecordPhysicalDispatch` authorization, state và warehouse guard. | Không giảm `on_hand` theo nhãn vận đơn hoặc trạng thái Order không phản ánh hàng đã rời kho. |
| `INV-DOM-016` | `ReleaseReservation(PAYMENT_FAILED/RESERVATION_EXPIRED)` chỉ áp dụng root `RESERVED`; `ReleaseReservation(STOCK_DISCREPANCY, order_exception_id)` cũng chỉ áp dụng root `RESERVED` và luôn release toàn root. `ReleaseCommittedAllocation(ORDER_CANCELLED/REFUND_BEFORE_DISPATCH)` chỉ áp dụng line `COMMITTED` chưa dispatch. Khi order đã partial dispatch, chỉ `POST_DISPATCH_EXCEPTION` kèm `order_exception_id` được phép release target line chưa dispatch. | Command reason và state guard. | Timer/retry hoặc correction không thể vô tình giải phóng committed stock hoặc đảo ngược physical dispatch; stock discrepancy không tạo partial release/fulfillment ngầm. |
| `INV-DOM-017` | Với cùng ReservationLine `COMMITTED`, chỉ transition atomic đầu tiên giữa release hợp lệ và physical dispatch được áp dụng. | State transition serialization/idempotency guard. | Không compensation ngầm làm mất dấu hàng đã rời kho hoặc giải phóng stock đã dispatch. |
| `INV-DOM-018` | `CloseIncompleteRefundScope` chỉ được Order Saga gửi khi scope `IN_PROGRESS`, `received < expected` và mọi received quantity final inspected; transition không tạo `AllItemsInspectionCompleted`. | RefundScope state/counter/authorization guard. | Scope dở dang không bị auto-close/refund nhưng vẫn có đường kết thúc audit được khi Order ra quyết định. |
| `INV-DOM-019` | Warehouse `inactive`/`outage` chỉ làm warehouse ineligible cho allocation mới. Status thay đổi sau allocation không tự đổi ReservationLine/OrderReservation state và không chặn Admin ghi physical dispatch fact hợp lệ. | Allocation eligibility và `RecordPhysicalDispatch` state guard tách biệt. | Cờ vận hành không thể xóa commitment/fact vật lý đã tồn tại hoặc tạo auto-reroute ngầm. |
| `INV-DOM-020` | Adjustment/reversal làm giảm sellable stock chỉ hợp lệ khi quantity không vượt `available_to_sell`; không được lấy từ `reserved`, `committed` hoặc `quarantine`. Nếu phát hiện physical discrepancy trong lúc có commitment, Inventory từ chối mutation; chỉ Order Saga có thể giải phóng **toàn bộ** root `RESERVED` bằng `STOCK_DISCREPANCY` kèm `order_exception_id`, rồi Admin mới correction. | Stock adjustment/reversal guard và `ReleaseReservation` root transition. | Không che giấu thiếu hụt bằng stock âm hoặc direct edit; không auto-reroute hay partial release. |
| `INV-DOM-021` | Return receipt chỉ nhận khi scope đang `REGISTERED`/`IN_PROGRESS`, actual SKU/line khớp scope và quantity không vượt phần chưa receipt. Wrong item, scope không receivable hoặc over-receipt từ chối toàn command. | `RecordReturnReceipt` validation trước bucket/counter mutation. | Không đưa hàng không rõ nguồn vào stock hoặc âm thầm accept một phần quantity dư. |
| `INV-DOM-022` | Mọi quantity return phải được final inspect đúng một lần tại warehouse đã receipt. Chỉ `PASS` chuyển quarantine thành sellable; `FAIL` final vẫn ở quarantine. | Receipt-line inspection guard và stock position tại warehouse thực tế. | Không inspection vượt receipt, không dùng re-inspection để bypass quarantine. |
| `INV-DOM-023` | Chỉ failed quantity chưa disposition được `HOLD`/`DISPOSE`/`RETURN_TO_SUPPLIER`; disposition physical giảm cả `on_hand` và `quarantine`. Reversal disposition chỉ khi item physical verified tại cùng warehouse và luôn trở lại quarantine. | Failed-inspection/disposition state và stock bucket transition. | Quality history không mất và hàng failed không tự trở lại sellable. |
| `INV-DOM-024` | Mọi warehouse command có `command_id` và operation identity; successful movement/audit và rejected admin command audit là immutable. | Command history, movement/audit record và authorization guard. | Retry/duplicate an toàn, audit chứng minh được cả thao tác bị từ chối mà không tạo stock movement. |

## 7. Quyết định Phase 2 đã chốt

1. **Đã chốt:** `on_hand` chỉ giảm khi physical dispatch; payment success chuyển
   `reserved → committed`.
2. **Đã chốt:** Khi Order Saga hủy/refund allocation chưa physical dispatch, Inventory
   chuyển `committed → available_to_sell`. Hàng đã dispatch phải đi qua return flow.
3. **Đã chốt:** Inspection fail chỉ giữ hàng ở `quarantine`; Admin mới quyết định
   disposition vật lý `DISPOSE`, `RETURN_TO_SUPPLIER` hoặc `HOLD`.
4. **Đã chốt:** Adjustment giảm chỉ lấy từ `available_to_sell`; thiếu hụt đụng tới
   `reserved`, `committed` hoặc `quarantine` phải đi qua exception workflow.
5. **Đã chốt:** Toàn bộ order phải reserve thành công ngay từ đầu. Mỗi line phải được
   đáp ứng trọn vẹn từ một warehouse; sau payment, các line có thể dispatch độc lập
   theo warehouse.
6. **Đã chốt:** Một OrderReservation chỉ được tạo sau ReserveOrder thành công và là
   root của toàn bộ ReservationLine trong order. ReserveOrder là atomic boundary bao
   gồm root, line và StockPosition liên quan.
7. **Đã chốt:** Mỗi ReservationLine có lifecycle `RESERVED → COMMITTED → DISPATCHED`
   hoặc `RESERVED/COMMITTED → RELEASED`. OrderReservation có state dẫn xuất
   `RESERVED`, `COMMITTED`, `PARTIALLY_DISPATCHED`, `DISPATCHED`, `RELEASED`.
8. **Đã chốt:** `expires_at` là deadline 15 phút để chấp nhận CommitPayment mới;
   Inventory không tự chuyển state khi hết hạn. Order Saga chủ động release; retry
   idempotent của commit thành công trước deadline vẫn nhận lại kết quả gốc.
9. **Đã chốt:** RefundScope là aggregate riêng, được Order Saga đăng ký immutable trước
   receipt, bao gồm selected order line/full quantity. Mỗi order có tối đa một scope
   trong toàn bộ retained history; terminal closure không mở quyền đăng ký scope khác.
10. **Đã chốt:** Receipt có thể qua nhiều kiện nhưng cumulative quantity không vượt scope.
    Hàng luôn vào quarantine; `PASS` mới tăng sellable, `FAIL` vẫn quarantine.
11. **Đã chốt:** Scope chỉ phát `AllItemsInspectionCompleted` một lần khi mọi item đã
    có kết quả final inspection. Cancel chỉ hợp lệ khi chưa receipt; scope dở dang không
    tự timeout/close/refund và phải chờ một exception command rõ ràng do Order quyết định.
12. **Đã chốt:** `INSPECTION_COMPLETED` không kết thúc refund request. Chỉ Order Saga
    gửi `CloseRefundScope` idempotent khi request đạt trạng thái cuối ở Order; closure
    không mở scope khác cho order.
13. **Đã chốt:** Scope `IN_PROGRESS` có một phần hàng không về chỉ được kết thúc bởi
    `CloseIncompleteRefundScope` từ Order Saga, sau khi mọi hàng đã nhận final inspected.
    Scope thành `CLOSED_INCOMPLETE`, không phát completion fact và giữ lifetime identity.

Không còn policy mở ở lifecycle chuẩn của stock, reservation và return inspection.
Decision kế tiếp là hình thức và guard của exception command khi scope bị dở dang hoặc
khi order có một phần line đã physical dispatch.

## 8. Definition of done cho Phase 2

- [x] Ubiquitous language không còn mơ hồ giữa physical stock, sellable stock, payment và shipment.
- [x] Stock bucket model và mọi phép tính đã được owner xác nhận.
- [x] Aggregate/transaction boundary đáp ứng `REJECT_ALL` và no-oversell.
- [x] State transition cho reservation, cancel/expiry và adjustment policy đã chốt.
- [x] Invariant catalogue được chấp nhận và có owner/enforcement strategy ở mức thiết kế.
- [x] RefundScope và inspection state machine hoàn chỉnh, gồm registration, receipt, pass/fail và completion event.
- [ ] Các term và state transition được đưa vào target `domain.md` sau review.
