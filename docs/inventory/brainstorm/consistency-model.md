---
doc_id: inventory-consistency-model
service: inventory
scope: target
status: proposed
owners:
  - Inventory Service team
reviewers:
  - Platform team
  - Order Service team
  - Warehouse operations
evidence:
  - docs/inventory/brainstorm/design-charter.md
  - docs/inventory/brainstorm/domain-foundation.md
  - docs/inventory/brainstorm/lifecycle-workflows.md
  - docs/inventory/brainstorm/warehouse-operations.md
verified_against_commit: null
---

# Inventory Service — Consistency Model (Phase 6)

> Working document cho consistency, persistence và concurrency. Document này chọn nơi
> authoritative commit trước khi thiết kế schema vật lý, endpoint, broker topic hay code.

## 1. Decision trace bắt buộc

| Input đã chốt | Hệ quả kiến trúc |
|---|---|
| `INV-CH-001`, `INV-DOM-001` | Reserve/commit/adjustment phải đọc và mutate một authoritative state có thể enforce bucket không âm; cache không thể quyết định no-oversell. |
| `INV-CH-007`, `INV-WF-004` | Business mutation, immutable audit/movement và transactional outbox record phải durable cùng một atomic commit; broker outage sau đó chỉ tạo relay retry. |
| `INV-DOM-003`, `INV-DOM-008`, `INV-DOM-009` | Reserve toàn order và commit payment toàn root đòi hỏi một transaction boundary bao phủ nhiều stock position/reservation line; không được partial protocol. |
| `INV-DOM-004`, `INV-DOM-024`, `INV-OPS-004` | Command outcome và operation identity phải durable cùng business result để retry/duplicate không tạo movement lần hai. |
| `INV-DOM-007`, `INV-OPS-007`, `INV-OPS-011`, `INV-OPS-013` | Audit/movement append-only, correction/reversal và retention tối thiểu 7 năm loại trừ việc dùng overwrite/direct database edit như recovery workflow. |
| `INV-CH-012`, `INV-ALLOC-003` | Redis/cache chỉ tối ưu read; allocation/reserve phải revalidate bằng primary authoritative state. |
| Priority `#1` no-oversell, `#2` audit, `#3` operational simplicity | Ưu tiên một write source durable và transaction rõ ràng hơn event sourcing hoặc nhiều commit source ở Release 1. |

## 2. Quyết định Phase 6 đã chốt

### `INV-CON-001` — Một relational primary database là write source duy nhất

Release 1 dùng **một relational primary database** làm source of truth duy nhất cho toàn bộ
Inventory write path. Chọn engine cụ thể (ví dụ PostgreSQL-compatible) và HA topology là
deployment decision cần được Platform xác nhận; semantic bắt buộc là database phải cung cấp
transaction atomic và concurrency control cho các record được mutate cùng nhau.

Một state-changing command chỉ thành công sau khi **cùng một database transaction** durable
commit tất cả phần sau, khi applicable:

```text
authoritative business state
+ command outcome / operation identity
+ immutable stock movement và audit
+ transactional outbox record
```

Không có external broker publish, cache write, search projection update hoặc service call nào là
một phần của transaction thành công. Chúng chỉ chạy sau commit từ outbox hoặc read model đã
được rebuild.

### `INV-CON-002` — State + immutable ledger, không event sourcing ở Release 1

`StockPosition` cùng các aggregate lifecycle là **authoritative decision state**: Inventory đọc
chúng để chấp nhận/từ chối allocation revalidation, reserve, commit, release, adjustment,
inspection và disposition.

Immutable stock movement/audit ledger là lịch sử giải trình và reconciliation bắt buộc, được
ghi cùng transaction với state transition. Ledger không là commit source cạnh tranh và không
được cập nhật/sửa để “đồng bộ” state. Correction luôn là compensating movement đã chốt ở
Phase 5.

Release 1 **không dùng event sourcing** làm write model. Lý do là ledger đã đáp ứng audit,
trong khi event sourcing sẽ thêm projection/replay/versioning complexity trước khi có bằng
chứng rằng relational state transaction không đáp ứng NFR. Đánh giá lại chỉ được thực hiện sau
load/failure measurement và decision thay thế được owner review.

### `INV-CON-003` — Projection và cache không có write authority

| Thành phần | Authority | Có thể rebuild? | Quy tắc |
|---|---|---|---|
| Primary relational state | Authoritative cho mọi mutation và decision sellable. | Khôi phục bằng database backup/recovery procedure; không sửa tay. | Nguồn commit duy nhất. |
| Immutable movement/audit và command/operation history | Authoritative evidence nằm cùng primary database. | Có thể kiểm tra/reconcile với state; không dùng direct edit để “sửa khớp”. | Append-only, retention tối thiểu 7 năm. |
| Transactional outbox | Authoritative danh sách fact đã durable commit chờ/pending publish. | Relay có thể replay an toàn theo event identity. | Không broker-publish trước database commit. |
| Redis/cache | Không authoritative. | Có, từ primary state/read model. | Không accept/reject reserve, commit, adjustment, inspection hay disposition. |
| Search/reporting/read projection | Không authoritative. | Có, từ snapshot/export + event replay theo contract Phase 7. | Không gọi ngược để quyết định stock. |

## 3. Hệ quả bắt buộc của decision này

1. Response `SUCCESS` cho mutation đồng nghĩa state, command result, audit/movement và outbox
   đã durable; broker delivery chưa cần hoàn tất.
2. Business reject cũng phải có deterministic command outcome theo idempotency policy; hạ tầng
   failure trước durable commit không tạo outcome thành công và caller retry cùng `command_id`.
3. Không dùng Redis lock, distributed lock, broker ordering hay eventual projection làm cơ chế
   correctness cho no-oversell.
4. Restore primary database phải đưa state, command/operation identity, ledger/audit và outbox
   về cùng recovery point; sau đó relay replay outbox có thể tạo duplicate fact và consumer phải
   dedupe. Chi tiết backup/RPO/RTO và runbook thuộc Phase 8.
5. Schema vật lý, transaction boundary theo command, lock order, isolation level, event identity
   và outbox relay algorithm vẫn phải được chốt trong các phần còn lại của Phase 6.

## 4. Logical data model đã chốt

### `INV-CON-004` — Record authoritative và identity nghiệp vụ

Tên dưới đây là logical record, không bắt buộc là một bảng hay một schema vật lý. Mọi record
authoritative nằm trong relational primary database đã chốt tại `INV-CON-001`.

| Logical record | Identity | Authority / dữ liệu chính | Classification |
|---|---|---|---|
| `Warehouse` | `warehouse_id` | Address, `is_active`, operational state, `fulfillment_priority`, revision. | Authoritative master state; không phải stock movement. |
| `StockPosition` | `(warehouse_id, sku_id)` | `on_hand`, `available_to_sell`, `reserved`, `committed`, `quarantine`, state revision. | Authoritative sellable decision state. Đây là record duy nhất quyết định bucket của một SKU tại một kho. |
| `OrderReservation` | `order_id` | Root state, `expires_at`, allocation-plan reference/revision, trace IDs. | Authoritative reservation lifecycle root. |
| `ReservationLine` | `(order_id, order_line_id)` | `sku_id`, quantity bất biến, allocated `warehouse_id`, line state, dispatch trace. | Authoritative one-warehouse-per-line commitment. |
| `RefundScope` | `refund_scope_id` | `order_id`, scope state, terminal reason, refund-lock lifecycle. | Authoritative return/refund boundary; một order chỉ có một active scope. |
| `RefundScopeLine` | `(refund_scope_id, order_line_id)` | Immutable `sku_id`/expected quantity; received/passed/failed counters. | Authoritative aggregate counter của return scope. |
| `ReturnReceiptLine` | `(refund_scope_id, return_receipt_id, return_receipt_line_id)` | Actual `sku_id`, scope line, receiving warehouse, quantity, package/tracking reference, final-inspected quantity. | Authoritative physical return intake at one warehouse. |
| `InspectionResult` | `inspection_id` | Referenced receipt line, warehouse, final `PASS`/`FAIL`, quantity, fail reason, checklist/evidence. | Authoritative final quality fact; immutable after acceptance. |
| `QuarantineDisposition` | `(warehouse_id, disposition_id, disposition_line_id)` | Failed-inspection reference, quantity, `HOLD`/`DISPOSE`/`RETURN_TO_SUPPLIER`, reason/evidence; original/reversal link when applicable. | Authoritative disposition history; physical outflow/reversal changes stock only under Phase 5 guard. |
| `CommandExecution` | `command_id` in caller namespace | Command type, immutable payload fingerprint, terminal outcome/result or business reject, trace/actor metadata. | Authoritative idempotency outcome for a specific command retry. |
| `OperationIdentity` | `(operation_type, operation_key)` | Fingerprint of a successful physical/business operation and linked result/movement. | Authoritative cross-command duplicate guard; see `INV-CON-005`. |
| `StockMovement` | `movement_id` | One stock-position delta, before/after or delta buckets, reason, actor, source aggregate/command, correction link. | Immutable authoritative evidence/ledger; not hot decision source. |
| `AuditEntry` | `audit_id` | Actor/authorization, command attempt/outcome, reason/evidence references, trace and timestamp. | Immutable audit record; includes rejected Admin commands and non-stock warehouse changes. |
| `OutboxEvent` | `event_id` | Fact identity, aggregate/state revision reference, schema marker, payload, publication state. | Durable pending/published fact for relay; not business state authority. |

`ReturnReceiptLine`, `InspectionResult` và `QuarantineDisposition` phải giữ warehouse identity
của fact vật lý. Nhờ vậy inspection/disposition luôn mutate đúng `StockPosition`, kể cả khi
hàng hoàn về một kho khác kho đã dispatch order ban đầu.

### Quan hệ và ownership

```text
Warehouse ──< StockPosition ──< StockMovement

OrderReservation ──< ReservationLine ──> StockPosition

RefundScope ──< RefundScopeLine ──< ReturnReceiptLine ──< InspectionResult ──< QuarantineDisposition
                                      │                         │
                                      └──────────> StockPosition <┘

CommandExecution ──< AuditEntry / StockMovement / OutboxEvent
OperationIdentity ──> CommandExecution (successful operation only)
```

Các link trên là logical traceability requirement: một mutation có thể có nhiều movement (ví dụ
ReserveOrder nhiều warehouse), nhưng tất cả phải cùng `command_id`, correlation/causation và
cùng transaction boundary. Không entity nào ở đây được service khác ghi trực tiếp.

### `INV-CON-005` — Business reject không chiếm operation identity

`CommandExecution` lưu terminal outcome cho **cả** success và business reject. Vì vậy retry
cùng `command_id` luôn nhận lại cùng outcome, kể cả `REJECT_ALL`, `RETURN_ITEM_MISMATCH` hoặc
`RETURN_QUANTITY_EXCEEDS_SCOPE`.

`OperationIdentity` chỉ được tạo khi operation đã **successfully accepted**. Một rejected return
receipt/adjustment không khóa receipt line hay adjustment case forever: Admin có thể sửa quantity,
SKU hoặc chứng từ rồi gửi command mới với `command_id` mới để được validation lại. Khi một
operation đã thành công, re-submit cùng operation key/payload trả outcome cũ; payload khác bị
`OPERATION_IDENTITY_CONFLICT`.

Điều này vừa giữ idempotency retry, vừa tránh hai lỗi đối nghịch: không ghi trùng receipt đã
accepted, nhưng cũng không khóa vĩnh viễn một thao tác đã bị từ chối do nhập sai.

### Authoritative state so với rebuildable data

| Data | Quyết định / rebuild policy |
|---|---|
| `Warehouse`, `StockPosition`, reservation/refund/return lifecycle, `CommandExecution`, `OperationIdentity`, movement/audit và outbox | Authoritative primary data. Restore cùng recovery point; không rebuild độc lập bằng cache/broker hoặc sửa tay. |
| Read cache, reporting aggregate, dashboard, Search/Product read projection | Derived. Có thể xóa và rebuild từ authoritative snapshot/export + event/replay contract. Không là dependency correctness. |
| Relay in-memory cursor, cache invalidation marker, worker lease | Ephemeral. Có thể mất và dựng lại bằng outbox/primary state; không lưu business truth. |

### Retention classification ban đầu

- Immutable `StockMovement`, `AuditEntry`, `CommandExecution`, `OperationIdentity` và evidence
  references: tối thiểu 7 năm theo `INV-OPS-013`.
- Current authoritative state: giữ trong primary database khi còn active; archival/disposal phải
  giữ traceability tới ledger và chờ retention/recovery policy Phase 8.
- `OutboxEvent` thuộc export/public replay feed: giữ tối thiểu 30 ngày từ durable creation theo
  `INV-API-010`; không cleanup event chưa broker-delivered. Consumer chậm hơn replay window phải
  bootstrap snapshot mới, không dùng broker retention làm nguồn recovery.

## 5. Transaction boundary và concurrency model đã chốt

### `INV-CON-006` — Atomic command outcome

Mỗi mutation chạy trong **một short-lived primary-database transaction** ở isolation level
`READ COMMITTED`, dùng explicit row lock và conditional state guard. Không có external service
call, broker publish, cache invalidation hay human wait bên trong transaction.

Database check constraint và conditional write guard bảo vệ bucket không âm / state transition;
application không được dựa vào cache hoặc in-memory state để chứng minh invariant. `READ COMMITTED`
đủ cho Release 1 vì mọi record quyết định của command phải được lock và revalidate sau lock;
`SERIALIZABLE` không là correctness prerequisite mặc định.

| Command / command group | Record phải lock/revalidate | Cùng transaction commit khi success | Business reject durable |
|---|---|---|---|
| `AllocationPreview` | Read authoritative warehouse/stock state, không giữ lock qua response. | Không có mutation, movement hay outbox. | `503 INVENTORY_UNAVAILABLE` nếu không đọc authoritative store; preview stale là expected. |
| `ReserveOrder` | Selected `Warehouse` rows và grouped `StockPosition` của **exact plan**; group quantity theo `(warehouse_id, sku_id)`, lock mỗi position một lần rồi revalidate active/outage/available. | `CommandExecution` + accepted `OperationIdentity` + reservation root/lines + bucket mutation + movement/audit + outbox. | `REJECT_ALL` chỉ sau khi đã lock và chứng minh ít nhất một exact allocation không eligible; commit result/audit, không reservation/stock/outbox. |
| `CommitPayment` / `ReleaseReservation` | Reservation root, tất cả lines, rồi mọi linked `StockPosition`. Revalidate root state, expiry/reason guard và line state. | Command/operation result + root/line transition + bucket movement/audit + outbox. | State/deadline/reason không hợp lệ commit deterministic result, không bucket/outbox. |
| `ReleaseCommittedAllocation` / `RecordPhysicalDispatch` | Reservation root, target line, rồi target `StockPosition`. | Command/operation result + line/root-derived state + bucket movement/audit + outbox. | First transition khác đã thắng, wrong state/warehouse/reason commit deterministic reject; không compensation. |
| Warehouse master update | Target `Warehouse`. | Command/operation result + warehouse revision + audit + applicable lifecycle/outbox fact. | Invalid state/config commit reject/audit; không stock mutation. |
| Sellable receipt, adjustment, sellable reversal | Accepted `OperationIdentity` guard rồi target `StockPosition`. | Command/operation result + stock bucket + movement/audit + outbox. | Invalid evidence/reason, duplicate conflict hoặc insufficient sellable commit reject/audit; không movement/outbox. |
| Register/cancel/close Refund Scope | Active RefundScope lock by `order_id`; target scope/lines when one exists. | Command/operation result + scope/line state + audit + lifecycle outbox fact. | Refund lock/state guard reject deterministic; không stock mutation. |
| `RecordReturnReceipt` | RefundScope, target scope line, receipt operation identity, rồi target receiving `StockPosition`. | Command/operation result + receipt line + scope counters/state + stock movement/audit + outbox. | Wrong/extra item, closed scope hoặc over-receipt commit reject/admin audit; không receipt/counter/stock/outbox. |
| `RecordInspectionPass` / `RecordInspectionFail` / internal `CompleteInspection` | RefundScope, scope line, receipt line, inspection identity, rồi receiving `StockPosition`. | Command/operation result + final inspection + counters + bucket movement when pass + audit + inspection fact; final inspection checks and emits one completion fact in the same transaction. | Already-final/wrong warehouse/quantity reject deterministic; no mutation/outbox. |
| Quarantine disposition / disposition reversal | Failed inspection/disposition record, then target `StockPosition`. | Command/operation result + disposition/reversal state + physical bucket movement if applicable + audit + outbox fact. | Invalid failed quantity/evidence/physical verification reject deterministic; no bucket movement/outbox. |

`CommandExecution` và Admin audit của business reject được commit deliberately, nên retry cùng
`command_id` không bị ảnh hưởng bởi state thay đổi sau đó. Nếu database transaction không thể
commit vì lỗi hạ tầng/concurrency, toàn bộ transaction rollback — không có accepted outcome,
movement, audit success hay outbox record để caller có thể retry cùng command ID an toàn.

### `INV-CON-007` — Lock hierarchy và exact revalidation

Mọi mutation tuân thủ hierarchy sau; record cùng nhóm được lock theo ascending stable identity:

```text
1. CommandExecution / OperationIdentity uniqueness guard
2. Warehouse (chỉ khi command cần revalidate allocation/configuration)
3. OrderReservation root → ReservationLine(s)
4. RefundScope root → RefundScopeLine(s) → ReturnReceiptLine / Inspection / Disposition
5. StockPosition theo (warehouse_id ASC, sku_id ASC)
```

Không command nào được lock một `StockPosition` rồi quay lại lock reservation/refund aggregate.
Command chỉ thao tác warehouse physical fact (receipt, adjustment, inspection, disposition) không
cần lock Warehouse status vì `inactive`/`outage` không chặn physical fact; ReserveOrder thì bắt
buộc lock/revalidate Warehouse status trước stock position.

Hệ quả quan trọng của thứ tự này:

- Nếu Admin đổi warehouse sang `OUTAGE` thắng lock trước ReserveOrder, reserve revalidate và
  `REJECT_ALL`. Nếu ReserveOrder thắng trước, reservation thành công và status update sau đó
  không tự release/reroute commitment — đúng `INV-ALLOC-004`.
- Hai ReserveOrder cạnh tranh cùng stock position được serialize. Command tới sau revalidate lại
  `available_to_sell` sau lock: hoặc reserve toàn bộ, hoặc `REJECT_ALL`; không có partial hold.
- `ReleaseCommittedAllocation` và `RecordPhysicalDispatch` lock cùng reservation line/position.
  Atomic transition đến trước thắng; command còn lại chỉ đọc state sau cùng và reject hợp lệ.
- Inspection lock RefundScope root để concurrent inspection ở nhiều receipt line vẫn chỉ có một
  transaction phát `AllItemsInspectionCompleted` khi counter toàn scope vừa đủ.

Nếu một `StockPosition` chưa tồn tại (ví dụ receipt SKU lần đầu), transaction phải atomically
create-or-lock record bằng unique identity `(warehouse_id, sku_id)` trước khi mutate. Insert race
không được tạo hai position; contender retry theo cùng lock hierarchy.

### `INV-CON-008` — Concurrency failure không phải business outcome

Deadlock, serialization failure, lock-timeout hoặc primary-database transient failure buộc rollback
toàn bộ transaction. Inventory có thể retry **toàn bộ transaction tối đa hai lần** trong deadline
command bằng bounded jitter; retry nội bộ luôn dùng cùng command identity và không publish fact
ngoài transaction.

Sau khi hết retry/deadline, caller nhận technical outcome retryable (public error taxonomy chốt ở
Phase 7). Outcome này **không** là `REJECT_ALL`, `OUT_OF_STOCK` hay payment/refund decision, vì
Inventory chưa hoàn tất authoritative validation. Order Saga/ADMIN retry cùng `command_id`; không
tạo pending write, không đổi route/allocation plan và không chạy compensation tự động.

## 6. Idempotency, outbox và replay semantic đã chốt

### `INV-CON-009` — Command được áp dụng tối đa một lần về nghiệp vụ

`CommandExecution` dùng identity `(caller_namespace, command_id)`; command payload được
canonicalize thành immutable fingerprint trước khi validation business. Một command mới claim
identity này trong chính database transaction của command. Concurrent request cùng command ID
chờ transaction đang xử lý; sau commit nó đọc terminal result, còn nếu transaction đầu rollback
thì request còn lại có thể thực hiện command như lần đầu.

| Tình huống incoming command | Kết quả bắt buộc | Stock / outbox |
|---|---|---|
| Cùng command identity, cùng fingerprint, đã có terminal result | Trả đúng stored outcome/result ban đầu. | Không mutation, không movement/audit/outbox mới. |
| Cùng command identity, fingerprint khác | `IDEMPOTENCY_KEY_REUSED`; ghi security audit khi là Admin command. | Không mutation/outbox. |
| Command ID mới, operation key đã **accepted**, fingerprint khớp | Tạo `CommandExecution` replay-link tới result gốc và trả original success. | Không mutation/movement/outbox lần hai. |
| Command ID mới, operation key đã accepted nhưng fingerprint khác | `OPERATION_IDENTITY_CONFLICT`; command result/audit reject được durable. | Không mutation/outbox. |
| Command ID và operation key mới, business validation reject | Durable terminal business reject; operation identity không được tạo. | Không mutation/outbox; Admin reject có audit. |
| Command ID và operation key mới, validation accept | Atomic success path ở `INV-CON-006`. | Tạo state/movement/audit/outbox cùng command result và operation identity. |

`CommandExecution` không có state “pending” durable sau transaction: hoặc command đã commit
terminal outcome, hoặc toàn bộ attempt rollback. Inventory không tạo hàng đợi pending write cho
command timeout; caller luôn retry exact `command_id`.

### `INV-CON-010` — Transactional outbox: at-least-once fact delivery

Mỗi fact cần công bố có một `OutboxEvent` immutable với `event_id` stable, event kind/schema
marker, owning aggregate/state revision khi có, causation/correlation, payload đã commit và
`created_at`. Record được insert trong cùng transaction với business state; command chỉ trả
success sau durable commit, không chờ broker acknowledgement.

Relay lấy outbox event đã commit bằng lease/claim trong primary database, publish với chính
`event_id`, và chỉ đánh dấu delivered sau broker acknowledgement. Crash trước/during/sau ack,
lease hết hạn hoặc broker outage đều có thể làm cùng event publish lại. Đây là expected:

```text
Business mutation: exactly-once per accepted command/operation
Outbox publication: at-least-once per event_id
Consumer side effect: effectively-once qua inbox/dedupe transaction
```

Outbox không hứa global ordering. Event phải mang aggregate/state revision để consumer có read
model không áp dụng state cũ đè state mới. Relay cố gắng publish theo thứ tự stable trong cùng
aggregate, nhưng consumer vẫn phải chịu duplicate/reorder sau crash/retry; ordering key và public
event contract chính thức thuộc Phase 7.

Consumer public phải persist `(consumer_name, event_id)` trong local inbox/dedupe store **cùng
transaction** với side effect của nó. Duplicate event ID không thực hiện side effect lần hai;
projection có revision chỉ tiến về state revision mới hơn, không lùi vì event đến trễ. Inventory
không chờ hay retry business mutation theo outcome của consumer.

### `INV-CON-011` — Cache/read consistency

Release 1 không đặt Redis/cache trên allocation hay write-correctness path:

- `AllocationPreview` đọc primary authoritative warehouse/stock state; advisory preview được
  `ReserveOrder` revalidate dưới lock.
- Reserve, payment commit/release, dispatch, receipt, inspection, disposition, adjustment,
  command dedupe và operation identity luôn đọc/write primary database.
- Cache chỉ được dùng cho explicitly non-authoritative dashboard/reporting hoặc read response
  chấp nhận stale. Cache miss, outage, eviction hay invalidation failure không đổi business
  outcome; cache có thể bị clear và rebuild.
- Search/Product read projection chỉ dùng snapshot/export + outbox event theo Phase 7; nó không
  cung cấp stock truth ngược cho Inventory.

Không cần cache invalidation để giữ write correctness, vì cache không có quyền write/decision.
Nếu primary database unavailable, Inventory fail closed theo `INV-CH-005`; không fallback sang
cache để accept/reject sellable mutation hoặc allocation preview.

## 7. Backup, restore và recovery design đã chốt

### `INV-CON-012` — Một recovery point cho mọi authoritative record

Baseline đã chốt là **RPO = 0 cho mutation đã acknowledged** và **RTO ≤ 60 phút**. Primary
database HA/backup topology phải chứng minh được target này trước production; nếu không thể
phục hồi một recovery point nhất quán, Inventory không được mở write path.

Recovery/failover bắt buộc đi theo thứ tự sau:

1. Dừng/fail-closed sellable mutation và allocation preview; caller nhận technical retryable
   outcome, không có pending order hay cache fallback.
2. Fail over hoặc restore **cùng một recovery point** cho state, command/operation identity,
   movement/audit và outbox. Không restore riêng lẻ từng nhóm record.
3. Verify database integrity và domain invariant: bucket equation/non-negative, reservation/refund
   state guards, command/operation uniqueness và movement/outbox traceability.
4. Clear/rebuild cache, relay ephemeral lease/cursor và read projections theo đúng classification
   tại `INV-CON-004`; không sửa tay state để “cho hệ thống chạy lại”.
5. Relay replay outbox retained. Event đã từng delivered có thể publish lại với cùng `event_id`;
   consumer inbox/revision rule hấp thụ duplicate/reorder.
6. Re-enable write path chỉ sau validation/reconciliation; chênh lệch tồn thật được xử lý bằng
   adjustment/correction/Order exception command đã chốt, không direct database edit.

Backup không được bỏ qua `CommandExecution` hoặc `OperationIdentity`: mất hai record này có thể
biến retry muộn thành mutation mới. Outbox export/public replay retention là 30 ngày theo
`INV-API-010`; audit/ledger vẫn theo retention 7 năm. DR drill, reconciliation schedule và
alert/runbook chi tiết được chốt trong [Operations & Resilience Contract](operations-resilience.md).

## 8. Definition of done cho Phase 6

- [x] Một write source và relational state + immutable ledger + transactional outbox model đã chốt.
- [x] Event sourcing, Redis cache và distributed lock bị loại khỏi correctness write path Release 1.
- [x] Logical data model, identity/relationship và ownership/rebuild classification đã chốt.
- [x] Transaction boundary và concurrency model chứng minh được mọi mutation chỉ có một
  commit path, lock hierarchy và outcome khi cạnh tranh.
- [x] Idempotency, operation identity, outbox delivery/replay và consumer dedupe semantic đã chốt.
- [x] Cache/recovery/backup design và recovery-from-backup path đã chốt.

**Phase 6 complete — 2026-09-07.** Phase 7 đã dùng các identity, at-least-once delivery và
rebuild boundary ở đây để thiết kế contract; Phase 8 đã cụ thể hóa recovery semantic thành backup,
reconciliation và incident runbook có thể diễn tập.
