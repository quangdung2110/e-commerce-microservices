---
doc_id: inventory-warehouse-operations
service: inventory
scope: target
status: proposed
owners:
  - Inventory Service team
reviewers:
  - Warehouse operations
  - Order Service team
evidence:
  - docs/inventory/brainstorm/design-charter.md
  - docs/inventory/brainstorm/context-map.md
  - docs/inventory/brainstorm/domain-foundation.md
  - docs/inventory/brainstorm/lifecycle-workflows.md
  - docs/inventory/brainstorm/allocation-policy.md
verified_against_commit: null
---

# Inventory Service — Warehouse Operations (Phase 5)

> Working document cho nghiệp vụ kho và lifecycle hàng vật lý. Document này không thiết kế
> API, database schema, ledger implementation hay transaction/locking; các phần đó thuộc
> Phase 6–7. Phase 5 cũng không được nới lỏng reservation, allocation hay refund lifecycle
> đã chốt ở các phase trước.

## 1. Decision trace bắt buộc

| Input đã chốt | Hệ quả đối với vận hành kho |
|---|---|
| `INV-CH-001`, `INV-DOM-001` | Không oversell; mọi movement phải giữ invariant `available_to_sell + reserved + committed + quarantine = on_hand` và không tạo bucket âm. |
| `INV-CH-006`, `INV-CH-009`, `INV-TGT-006` | Release 1 bắt buộc hỗ trợ stock adjustment tăng/giảm. Chỉ `ADMIN` thực hiện, với reason code, actor audit và không cần maker-checker. |
| `INV-TGT-003`, `INV-WF-001` | Mọi mutation phải có command ID, actor/system actor, thời điểm và trace; business state, movement/audit và outbox fact cùng durable commit. Không dùng direct database edit làm quy trình nghiệp vụ. |
| `INV-CTX-001`, `INV-CTX-009` | Inventory sở hữu warehouse master và trạng thái active/inactive/outage; Warehouse Admin là nguồn ghi nhận dispatch và return/inspection fact. |
| `INV-DOM-005` đến `INV-DOM-007`, `INV-DOM-012` đến `INV-DOM-014` | Return receipt chỉ nhận trong Refund Scope đã đăng ký. Hàng phải qua quarantine/inspection; một scope có thể nhận nhiều kiện, nhưng không có partial refund scope. |
| `INV-WF-001`, `INV-WF-004` | Dispatch chỉ áp dụng cho committed allocation; outbox phát fact sau durable business mutation, relay không chạy lại movement khi broker lỗi. |
| `INV-ALLOC-004`, `INV-ALLOC-005` | Active/inactive/outage và fulfillment priority là cấu hình warehouse cho allocation tương lai, không phải stock movement và không tự tác động reservation/commitment có sẵn. |

## 2. Quyết định Phase 5 đã chốt — phạm vi Release 1

### `INV-OPS-001` — Capability vận hành được hỗ trợ

Release 1 hỗ trợ các capability vận hành kho sau:

- Nhập tồn ban đầu hoặc supplier receipt vào sellable stock.
- Stock adjustment tăng (`+`) và giảm (`-`) sau kiểm kê/correction, do `ADMIN` thực hiện.
- Physical dispatch cho committed allocation, theo authority đã chốt ở Phase 3.
- Return receipt theo từng kiện/lần, quarantine, inspection `PASS`/`FAIL` và disposition
  hàng inspection fail.
- Quản lý warehouse master: địa chỉ, `is_active`, `operational_state` và
  `fulfillment_priority`.

Các capability này xử lý stock theo `sku_id` và warehouse; Inventory không đồng bộ gọi
Product Service để xác nhận SKU.

### `INV-OPS-002` — Explicitly out of scope cho Release 1

Release 1 **không** hỗ trợ:

- Transfer giữa các warehouse.
- Bin/location nội bộ kho, lot/batch, serial number hoặc expiry-date tracking.
- WMS pick/pack, barcode scanning, wave planning hoặc tự động cân bằng/replenish stock.
- Tích hợp purchase order hoặc Supplier Service.
- Tự động chuyển stock do warehouse bị `inactive`/`outage`.

Supplier/initial receipt ở release đầu là thao tác Inventory/Warehouse Admin có audit,
không phải confirmation từ một Purchase Order workflow. Khi hỗ trợ transfer trong tương lai,
nó phải là workflow có identity, trace và movement hai đầu rõ ràng; tuyệt đối không phải
trừ tay một kho rồi cộng tay kho khác hoặc sửa database.

### `INV-OPS-003` — Taxonomy của stock operation

Mỗi operation được xếp chính xác vào một trong năm loại dưới đây. Phân loại này tránh nhầm
lẫn giữa hàng vật lý, stock đã cam kết và workflow/configuration signal.

| Loại | Operation | Ảnh hưởng stock | Ý nghĩa |
|---|---|---|---|
| **Physical inbound** | Initial/supplier receipt; return receipt | Tăng `on_hand` tại kho nhận. | Hàng vật lý đã vào kho. Return receipt bắt buộc vào `quarantine`. |
| **Commercial allocation** | Reserve, release, commit payment | Chỉ chuyển giữa `available_to_sell`, `reserved`, `committed`; không đổi `on_hand`. | Hàng vẫn ở kho nhưng được giữ/cam kết cho Order. |
| **Physical outbound / disposition** | Physical dispatch; dispose; return-to-supplier | Giảm `on_hand` khi hàng thực sự rời kho. | Chỉ ghi nhận fact tại thời điểm vật lý. |
| **Stock quality / correction** | Inspection pass/fail; adjustment `+/-`; hold | Chuyển bucket hoặc sửa chênh lệch tồn theo rule tương ứng. | Không được dùng để né reserve/commit/refund lifecycle. |
| **Workflow / configuration only** | Refund-scope completion/close; warehouse status/priority | Không đổi stock bucket. | Có audit/lifecycle fact nhưng không phải stock movement. |

## 3. Bản đồ movement Release 1

Notation: `+` tăng, `-` giảm, `0` không đổi. Các delta dưới đây là business semantic;
cách ledger hóa và atomic transaction sẽ được thiết kế ở Phase 6.

| Operation | `on_hand` | `available_to_sell` | `reserved` | `committed` | `quarantine` | Ghi chú đã chốt |
|---|---:|---:|---:|---:|---:|---|
| Initial/supplier receipt `q` | `+q` | `+q` | 0 | 0 | 0 | Hàng được nhập trực tiếp vào sellable stock. Command/authority/reason chi tiết sẽ chốt tiếp trong Phase 5. |
| `ReserveOrder` | 0 | `-q` | `+q` | 0 | 0 | Toàn order atomic; thiếu một line là `REJECT_ALL`. |
| `ReleaseReservation` | 0 | `+q` | `-q` | 0 | 0 | Chỉ Order Saga, root còn `RESERVED`. |
| `CommitPayment` | 0 | 0 | `-q` | `+q` | 0 | Toàn bộ line atomic; payment không gọi Inventory trực tiếp. |
| `ReleaseCommittedAllocation` | 0 | `+q` | 0 | `-q` | 0 | Chỉ Order Saga, line chưa dispatch; post-dispatch exception cần guard đã chốt. |
| `RecordPhysicalDispatch` | `-q` | 0 | 0 | `-q` | 0 | Chỉ Warehouse Admin, committed allocation đúng warehouse và hàng đã thực sự rời kho. |
| `RecordReturnReceipt` | `+q` | 0 | 0 | 0 | `+q` | Chỉ line thuộc Refund Scope; receipt nhiều kiện được phép, không vượt expected quantity. |
| `RecordInspectionPass` | 0 | `+q` | 0 | 0 | `-q` | Chỉ quantity đã receipt, chưa final inspected. |
| `RecordInspectionFail` | 0 | 0 | 0 | 0 | 0 | Ghi kết quả fail; hàng vẫn quarantine. |
| `HOLD` failed item | 0 | 0 | 0 | 0 | 0 | Giữ hàng failed ở quarantine; không được bán lại. |
| `DISPOSE` / `RETURN_TO_SUPPLIER` failed item | `-q` | 0 | 0 | 0 | `-q` | Chỉ khi hàng failed thực sự rời kho; reason bắt buộc. |
| Adjustment `+q` | `+q` | `+q` | 0 | 0 | 0 | Chỉ `ADMIN`; không được dùng thay supplier receipt nếu nguồn hàng đã biết. |
| Adjustment `-q` | `-q` | `-q` | 0 | 0 | 0 | Chỉ `ADMIN`, chỉ trừ từ quantity hiện sellable; không được lấy hàng đang reserved/committed/quarantine. |
| Warehouse active/status/priority update | 0 | 0 | 0 | 0 | 0 | Ảnh hưởng allocation mới theo Phase 4; không tự release, reroute hay transfer stock đã có. |

## 4. Guardrail đã chốt

- Partial **physical return receipt** được phép qua nhiều kiện/lần; partial **refund scope**
  không được phép trong Release 1.
- `INSPECTION_COMPLETED` chỉ cho biết toàn bộ item của scope đã có kết quả inspection;
  Order Saga vẫn là bên duy nhất đóng scope sau quyết định refund terminal tại Order.
- Hàng inspection fail không bao giờ tự quay lại sellable stock. `HOLD`, `DISPOSE` và
  `RETURN_TO_SUPPLIER` chỉ áp dụng cho quantity đang quarantine.
- Warehouse `inactive` hoặc `outage` chỉ loại nó khỏi allocation **mới**. Không mutation tự
  động lên reservation/commitment hoặc physical stock hiện hữu; Admin vẫn có thể ghi nhận
  physical dispatch thực tế.
- Mọi correction/reversal phải là command/movement mới, liên kết tới movement sai và có audit;
  không sửa/xóa history hay direct-edit stock state.

## 5. Command catalogue, reason code và correction đã chốt

### `INV-OPS-004` — Common command identity và retry semantic

Mọi warehouse mutation, ngoài các field trace đã chốt ở Phase 3, phải mang cả hai lớp
identity sau:

| Identity | Mục đích | Quy tắc |
|---|---|---|
| `command_id` | Idempotency cho network retry của một caller. | Retry cùng `command_id` và payload bất biến trả lại outcome thành công/từ chối ban đầu; reuse cùng ID với payload khác bị từ chối `IDEMPOTENCY_KEY_REUSED`. |
| `operation_key` | Ngăn một thao tác vật lý/chứng từ bị ghi hai lần bởi hai command khác nhau. | Re-submit cùng key và cùng payload trả outcome cũ; cùng key nhưng payload khác bị từ chối `OPERATION_IDENTITY_CONFLICT`. |

`operation_key` có format nghiệp vụ khác nhau theo command: sellable receipt dùng
`warehouse_id + receipt_id + receipt_line_id`; return receipt dùng
`refund_scope_id + return_receipt_id + return_receipt_line_id`; adjustment dùng
`warehouse_id + adjustment_case_id + adjustment_line_id`; quarantine disposition dùng
`warehouse_id + disposition_id + disposition_line_id`; reversal dùng
`original_movement_id + reversal_sequence`.

Caller gặp timeout **không** tạo adjustment/receipt mới. Caller retry exact command với
`command_id` cũ hoặc đọc outcome cũ theo command ID; tên query/API cụ thể thuộc Phase 7.
Warehouse status `inactive`/`outage` không chặn Admin ghi nhận fact vật lý hoặc correction:
nó chỉ chặn allocation mới theo `INV-ALLOC-004`.

Business reject được lưu theo `command_id` để retry nhận đúng outcome cũ, nhưng không chiếm
`operation_key`. Vì vậy một receipt/adjustment bị từ chối do nhập sai có thể được Admin sửa và
gửi lại bằng command ID mới; chỉ operation đã accepted mới giữ operation identity chống duplicate.

### `INV-OPS-005` — Command catalogue cho sellable receipt và adjustment

Catalogue này chỉ chốt semantic business. Event name/payload public, protocol và persistence
model vẫn thuộc Phase 6–7.

| Command | Caller duy nhất | Required business input | Preconditions / guard | Bucket delta | Audit và fact/outbox semantic |
|---|---|---|---|---|---|
| `RecordSellableStockReceipt` | Warehouse Admin (`ADMIN`) | `warehouse_id`, opaque `sku_id`, quantity `q > 0`, `receipt_kind`, `receipt_id`, `receipt_line_id`, source document reference, `command_id`. Với `INITIAL_STOCK_LOAD`: bắt buộc có `baseline_batch_id`; với `SUPPLIER_RECEIPT`: bắt buộc có delivery/GRN reference. | Warehouse tồn tại; `(warehouse_id, receipt_id, receipt_line_id)` là operation key mới hoặc retry khớp payload. Inventory không synchronous-validate SKU với Product. `INITIAL_STOCK_LOAD` chỉ dùng trong cutover/baseline batch đã được phê duyệt, không dùng như receipt hằng ngày. | `on_hand +q`, `available_to_sell +q`. | Lưu actor, receipt/source reference, kind, quantity, thời điểm, command/operation identity. Cùng durable commit tạo audit movement và stock-receipt/availability fact qua outbox. |
| `AdjustSellableStock` | Warehouse Admin (`ADMIN`) | `warehouse_id`, opaque `sku_id`, direction, `q > 0`, allowed `reason_code`, `adjustment_case_id`, `adjustment_line_id`, evidence reference, `command_id`. | Adjustment case/line tạo operation key. Với `-q`, bắt buộc `q ≤ available_to_sell`; không được giảm `reserved`, `committed` hoặc `quarantine`. Với `+q`, chỉ dùng cho count gain hoặc correction không có original movement để reverse. | `+`: `on_hand +q`, `available_to_sell +q`; `-`: `on_hand -q`, `available_to_sell -q`. | Lưu actor, direction, reason/evidence, quantity trước/sau, thời điểm, command/operation identity. Cùng durable commit tạo audit movement và stock-adjustment/availability fact qua outbox. |
| `ReverseStockMovement` | Warehouse Admin (`ADMIN`) | `original_movement_id`, `reversal_sequence`, quantity `q > 0`, reversal reason, correction case/reference, `command_id`. | Chỉ target sellable receipt hoặc adjustment đã thành công; phải thỏa toàn bộ guard ở §6. Không phải command để đảo reserve, commit, dispatch, return receipt, inspection hoặc refund lifecycle. | Inverse delta của phần original movement được reverse, nhưng chỉ nếu invariant vẫn đúng. | Giữ nguyên original movement; tạo correction movement mới liên kết hai chiều với original, cùng audit và reversal/availability fact qua outbox. |

Một receipt document có nhiều SKU được ghi bằng nhiều command theo từng `receipt_line_id`; điều
này không làm mỗi line mất identity nghiệp vụ riêng. Một command không bao giờ silently
merge vào receipt/adjustment khác.

### `INV-OPS-006` — Reason-code policy

Không có code `OTHER` hoặc reason trống. Code xác định mục đích nghiệp vụ; evidence reference
cho biết chứng từ/case cụ thể. `reason_note` bắt buộc cho `DATA_CORRECTION` và mọi reversal;
với các code khác nó có thể bổ sung diễn giải nhưng không thay evidence reference.

| Command family | Allowed code / kind | Direction được phép | Evidence/reference tối thiểu |
|---|---|---|---|
| Sellable receipt | `INITIAL_STOCK_LOAD` | Chỉ `+` | `baseline_batch_id` và chứng từ baseline/cutover. |
| Sellable receipt | `SUPPLIER_RECEIPT` | Chỉ `+` | Delivery note/GRN hoặc receipt record do kho phát hành. |
| Adjustment | `COUNT_GAIN` | Chỉ `+` | Stocktake/count sheet và line tương ứng. |
| Adjustment | `COUNT_LOSS` | Chỉ `-` | Stocktake/count sheet và line tương ứng. |
| Adjustment | `DAMAGE_REMOVAL` | Chỉ `-` | Incident/disposal evidence cho hàng đã thực sự không còn sellable/ở kho. |
| Adjustment | `EXPIRY_REMOVAL` | Chỉ `-` | Expiry/disposal evidence cho hàng đã thực sự rời sellable stock. |
| Adjustment | `DATA_CORRECTION` | `+` hoặc `-` | Correction/incident case. Chỉ dùng khi không có một original movement xác định để reverse. |
| Reversal | `DUPLICATE_ENTRY`, `WRONG_QUANTITY`, `WRONG_SKU`, `WRONG_WAREHOUSE`, `SOURCE_DOCUMENT_VOIDED` | Delta được suy ra từ movement gốc. | `original_movement_id`, correction case và reason note bắt buộc. |

Nếu nguồn hàng đã biết là supplier delivery, Admin phải dùng `SUPPLIER_RECEIPT`, không dùng
adjustment `+`. Nếu phát hiện một receipt/adjustment cụ thể ghi sai, Admin phải dùng reversal,
không dùng `DATA_CORRECTION` để che lịch sử. `DAMAGE_REMOVAL`/`EXPIRY_REMOVAL` chỉ hợp lệ sau
khi quantity không còn là hàng sellable vật lý; Release 1 không có bucket hàng hỏng riêng.

## 6. Correction và reversal an toàn

### `INV-OPS-007` — Append-only compensation, không overwrite history

Mọi movement đã accepted là immutable. `ReverseStockMovement` tạo một movement bù trừ mới,
không sửa quantity/reason/actor/timestamp của movement gốc. Partial reversal được phép khi
`q` không vượt phần quantity chưa được reverse của original; một reversal không thể bị dùng
hai lần do operation key `(original_movement_id, reversal_sequence)`.

Guard bắt buộc của reversal:

1. Original movement tồn tại, đã successful và thuộc đúng `warehouse_id`/`sku_id` được
   correction case mô tả.
2. Original chỉ có thể là `RecordSellableStockReceipt` hoặc `AdjustSellableStock`. Các
   transition gắn với Order/RefundScope phải dùng workflow owner đã chốt, không có generic
   reversal từ Admin.
3. Inverse delta phải giữ tất cả bucket không âm. Vì vậy reverse một movement từng làm tăng
   sellable stock chỉ hợp lệ khi `q ≤ available_to_sell` hiện tại.
4. Reverse một movement từng làm giảm stock chỉ hợp lệ khi physical verification chứng minh
   quantity hiện có và sellable tại warehouse; correction không được tạo “stock trên giấy”.
5. Sau reversal, nếu cần ghi đúng receipt/adjustment thay thế, Admin tạo **một command mới**
   với identity và evidence mới; original vẫn truy vết được qua correction case.

### `INV-OPS-008` — Thiếu hụt phát hiện khi đã có commitment

Nếu correction hoặc adjustment giảm bị chặn vì quantity đang `reserved`, `committed` hoặc
`quarantine`, Inventory trả `INSUFFICIENT_SELLABLE_STOCK_FOR_CORRECTION` và **không mutation**.
Admin không được rút stock từ bucket đã hứa/đang quarantine, không được direct-edit database,
không tự reroute hoặc tự release một line.

Quy trình hợp lệ là:

1. Admin lập discrepancy/correction case; nếu physical availability của warehouse không còn
   đáng tin, Admin chuyển warehouse sang `OUTAGE` để chặn allocation mới.
2. Order quyết định outcome với khách. Nếu root còn `RESERVED`, Order Saga có thể gửi
   `ReleaseReservation(reason = STOCK_DISCREPANCY, order_exception_id)`: toàn bộ root được
   release atomically, không partial release. Nếu đã `COMMITTED`, Order dùng cancellation hoặc
   `POST_DISPATCH_EXCEPTION` đã chốt ở Phase 3 theo trạng thái dispatch thực tế.
3. Chỉ sau khi quantity cần correction đã là `available_to_sell`, Admin mới resubmit adjustment
   hoặc reversal mang discrepancy case. Không có movement tự động giữa các bước.

`STOCK_DISCREPANCY` là bổ sung có chủ đích cho `ReleaseReservation`: chỉ Order Saga gọi được,
chỉ ở root `RESERVED`, yêu cầu `order_exception_id`, và luôn release toàn order. Nó không thay
đổi luật `REJECT_ALL`, không tạo backorder/partial fulfillment và không cho Inventory tự quyết
định outcome khách hàng.

## 7. Return receipt, inspection và quarantine disposition

### `INV-OPS-009` — Return receipt chỉ nhận exact scoped item

`RecordReturnReceipt` vẫn chỉ do Warehouse Admin (`ADMIN`) gọi. Command phải mang
`refund_scope_id`, `order_line_id`, **actual** `sku_id`, warehouse nhận thực tế, quantity
`q > 0`, `return_receipt_id`, `return_receipt_line_id`, package/tracking reference và
`command_id`. Admin không được suy đoán line từ package label; Inventory kiểm tra actual SKU
và line với immutable Refund Scope.

| Tình huống | Authoritative outcome | Stock / scope mutation |
|---|---|---|
| Scope `REGISTERED` hoặc `IN_PROGRESS`; line và actual SKU khớp; `q ≤ expected_quantity - received_quantity` | Accept. Receipt đầu tiên chuyển scope thành `IN_PROGRESS`. | Tại warehouse nhận: `on_hand +q`, `quarantine +q`, `received_quantity +q`. |
| Cùng return receipt operation key và payload khớp | Trả outcome cũ. | Không mutation lần hai. |
| Cùng operation key nhưng payload khác | `OPERATION_IDENTITY_CONFLICT`. | Không mutation. |
| Scope không tồn tại/đã `CANCELLED`, `CLOSED` hoặc `CLOSED_INCOMPLETE` | `RETURN_SCOPE_NOT_RECEIVABLE`. | Không mutation. Order phải đăng ký scope mới trước một receipt hợp lệ khác. |
| `order_line_id` không thuộc scope hoặc actual `sku_id` không khớp scope line | `RETURN_ITEM_MISMATCH`. | Không nhập vào bất kỳ stock bucket nào, không tăng scope counter và không tự gắn sang order khác. Lưu rejected-admin-command audit kèm package reference để kho xử lý vật lý ngoài stock model. |
| `q` lớn hơn quantity còn lại trong scope | `RETURN_QUANTITY_EXCEEDS_SCOPE`. | Từ chối **toàn bộ receipt line**, không accept một phần ngầm. Admin phải kiểm đếm/tách receipt đúng quantity; phần dư là intake exception ngoài stock model. |

Hàng wrong/extra đang được kho giữ vật lý không trở thành Inventory stock chỉ vì đã đến cổng
kho. Chúng không được allocation, adjustment hay bán lại cho đến khi operational process ngoài
Inventory xác định được source hợp lệ. Inventory không gọi Order/Product synchronously để tìm
một scope thay thế.

### `INV-OPS-010` — Inspection final và disposition của hàng fail

Mỗi inspection phải tham chiếu một return receipt line chưa final-inspected tại **đúng warehouse
đã nhận**. Nhờ vậy `quarantine` được trừ tại nơi hàng thực tế nằm, không chỉ theo cumulative
counter của RefundScope.

| Command | Caller / required input | Guard và kết quả | Bucket delta |
|---|---|---|---|
| `RecordInspectionPass` | `ADMIN`; return receipt line, `inspection_id`, `q > 0`, checklist/evidence reference, command ID. | `q` không vượt quantity receipt chưa final-inspected tại warehouse đó. Final result `PASS`; không có re-inspection từ `FAIL → PASS` ở Release 1. | `quarantine -q`, `available_to_sell +q`. |
| `RecordInspectionFail` | `ADMIN`; return receipt line, `inspection_id`, `q > 0`, fail reason, checklist/evidence reference, command ID. | Guard quantity như pass. Final result `FAIL`; quantity vẫn quarantine và có thể chờ disposition. | Không đổi bucket. |
| `RecordQuarantineDisposition` | `ADMIN`; failed-inspection reference, `disposition_id`, `disposition_line_id`, `q > 0`, disposition/reason, evidence reference, command ID. | Chỉ áp dụng q chưa disposition của inspection `FAIL` tại đúng warehouse. `HOLD` là trạng thái không terminal; `DISPOSE`/`RETURN_TO_SUPPLIER` có thể từ pending hoặc held quantity. | `HOLD`: 0. `DISPOSE`/`RETURN_TO_SUPPLIER`: `on_hand -q`, `quarantine -q`, chỉ khi hàng thực sự đã rời kho. |

Allowed inspection-fail reason codes là `DAMAGED`, `USED_OR_ALTERED`, `MISSING_COMPONENTS`,
`SAFETY_OR_HYGIENE`, `COUNTERFEIT_OR_UNVERIFIABLE` và `NOT_RESELLABLE_BY_POLICY`. Không có
`OTHER`; checklist/evidence reference là bắt buộc cho mọi final inspection.

Allowed disposition và evidence:

| Disposition | Allowed reason | Evidence bắt buộc | Nghĩa vụ stock |
|---|---|---|---|
| `HOLD` | `AWAITING_DISPOSITION`, `SUPPLIER_CLAIM`, `SAFETY_REVIEW`, `EVIDENCE_PRESERVATION` | Hold/case reference. | Không đổi bucket; quantity vẫn quarantine và không sellable. Có thể được disposition sau này. |
| `DISPOSE` | `UNSAFE`, `UNREPAIRABLE`, `EXPIRED`, `POLICY_REQUIRED` | Disposal/destruction record. | Chỉ ghi khi hàng không còn ở kho; giảm `on_hand` và `quarantine`. |
| `RETURN_TO_SUPPLIER` | `SUPPLIER_RETURN_AUTHORIZED` | Supplier RMA và outbound tracking/handover proof. | Chỉ ghi khi hàng đã rời kho; giảm `on_hand` và `quarantine`. |

Inspection `FAIL`, `HOLD` hay physical disposition không thay đổi kết quả completion đã phát:
`AllItemsInspectionCompleted` vẫn chỉ phụ thuộc việc toàn bộ expected quantity có final result
`PASS` hoặc `FAIL`. Chúng cũng không quyết định refund payout hoặc đóng Refund Scope — đó vẫn là
authority của Order Saga.

### `INV-OPS-011` — Correction riêng cho quarantine disposition

`ReverseStockMovement` không target disposition. Thay vào đó, `ReverseQuarantineDisposition`
chỉ do `ADMIN` gọi và phải tham chiếu original `DISPOSE` hoặc `RETURN_TO_SUPPLIER` movement,
`reversal_sequence`, quantity `q > 0`, correction reason, physical-verification evidence và
`command_id`.

- `q` không vượt quantity original chưa reverse; same operation key retry trả outcome cũ.
- Chỉ hợp lệ khi item đã được xác minh vật lý đang trở lại/còn ở **cùng warehouse**. Reason
  chỉ có thể là `WRONG_RECORD`, `DISPOSAL_ABORTED` hoặc `SUPPLIER_RETURNED`.
- Tạo immutable compensating movement: `on_hand +q`, `quarantine +q`. Item luôn quay về
  quarantine, **không** quay thẳng vào `available_to_sell`; final inspection fail history giữ
  nguyên và Admin phải disposition lại nếu cần.
- Nếu hàng đã rời kho và không quay lại, reversal bị từ chối. Không correction bằng adjustment
  `+` vì sẽ làm mất quarantine/quality trace.

## 8. Authorization, audit và retention

### `INV-OPS-012` — Authority matrix Release 1

`ADMIN` là role duy nhất cho mọi warehouse operation; Release 1 không có maker-checker, value
threshold hay quyền direct-database. `ADMIN` không vì thế có quyền can thiệp Order/RefundScope
lifecycle.

| Capability / command | Authority duy nhất | Không được phép |
|---|---|---|
| Warehouse master, active/inactive/outage, `fulfillment_priority` | `ADMIN` | Checkout, Order, Payment tự sửa warehouse state. |
| Sellable receipt, adjustment, sellable reversal | `ADMIN` | Order Saga, Payment, direct database job. |
| Physical dispatch, return receipt, inspection, quarantine disposition/reversal | `ADMIN` | Order/Payment tự ghi physical fact hoặc tự đổi stock. |
| Reserve, commit, release reservation/allocation, register/cancel/close Refund Scope | Order Temporal Saga | Admin, Payment, Checkout gọi trực tiếp. |
| `AllocationPreview` | Checkout | Không tạo stock mutation. |
| `CompleteInspection` | Inventory internal transition | Admin/Order/Payment gọi command riêng. |

### `INV-OPS-013` — Audit record tối thiểu và retention

Mỗi successful stock mutation tạo immutable movement/audit record. Mọi admin command bị từ
chối cũng tạo immutable command-security audit record, nhưng không tạo stock movement hoặc
public outbox fact. Retry idempotent không tạo movement thứ hai.

| Nhóm field audit bắt buộc | Nội dung |
|---|---|
| Identity | `movement_id` khi thành công, `command_id`, `operation_key`, command name, outcome và schema/version về sau. |
| Actor và thời gian | Actor/service principal, role, authentication subject, `recorded_at` do Inventory ghi; `occurred_at` vật lý nếu được khai báo, kèm timezone/source. |
| Subject và delta | Warehouse, SKU, quantity, bucket trước/sau hoặc delta; Order/reservation/refund-scope/receipt/inspection/disposition identity khi liên quan. |
| Lý do và chứng từ | Reason/kind, reason note khi bắt buộc, evidence/document/case reference, original/reversal movement link nếu có. |
| Trace và delivery | Correlation ID, causation ID, outbox fact identity khi command thành công; quyết định authorization/rejection code khi không thành công. |

Baseline retention đã chốt là **tối thiểu 7 năm** kể từ `recorded_at` cho immutable movement,
admin command audit, command/operation identity mapping và evidence references. Inventory chỉ
lưu reference/metadata tối thiểu, không trở thành owner của toàn bộ file chứng từ; hệ thống giữ
evidence phải có retention không ngắn hơn reference còn cần audit. Sau 7 năm không được tự mặc
định xóa: Compliance/Operations phải phê duyệt retention/disposal policy trước Phase 8. Đây là
baseline vận hành, không phải kết luận pháp lý.

## 9. Definition of done cho Phase 5

- [x] Phạm vi capability vận hành Release 1 và các exclusion đã được chốt.
- [x] Stock-movement taxonomy và bucket map nền tảng đã được chốt.
- [x] Command catalogue cho sellable receipt, adjustment, return/inspection và quarantine
  disposition đã có caller, guard, idempotency và outbox/audit semantic.
- [x] Reason taxonomy và correction/reversal path cho sellable receipt/adjustment không cần
  direct database edit; disposition-specific correction sẽ chốt cùng return lifecycle.
- [x] Return/quarantine/disposition policy bao phủ receipt sai/thừa, final inspection,
  `HOLD`/`DISPOSE`/`RETURN_TO_SUPPLIER` và disposition correction.
- [x] Authorization matrix, audit requirement và retention baseline đã chốt.

**Phase 5 complete — 2026-09-07.** Các command và invariant ở đây là input bắt buộc cho
Phase 6–8; phase sau không được nới lỏng authority, quarantine hoặc audit/reversal semantic
trừ khi có decision thay thế được review bởi owner phù hợp.
