---
doc_id: inventory-workflows
service: inventory
scope: current
status: proposed
owners:
  - Inventory Service team
evidence:
  - java/services/inventory-service
verified_against_commit: null
---

# Inventory Service — Workflows

> Mỗi workflow phải cho developer biết chính xác điều gì xảy ra khi một bước timeout
> hoặc cùng request được gửi hai lần. Các mục "Implementation evidence" chưa được đối
> chiếu với code, nên chúng ghi nơi cần tìm chứ không khẳng định nơi đã có.

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-INV-001` | Lập route plan | API | Checkout thay mặt user | proposed |
| `WF-INV-002` | Reserve inventory | Temporal Activity | Order Saga | proposed |
| `WF-INV-003` | Deduct inventory | Temporal Activity | Order Saga | proposed |
| `WF-INV-004` | Release reservation | Temporal Activity | Order Saga | proposed |
| `WF-INV-005` | Materialize mutation vào PostgreSQL | Kafka event | Ledger Worker | proposed |
| `WF-INV-006` | Admin restock | API | Admin | proposed |
| `WF-INV-007` | Đăng ký refund expectation | API nội bộ | Order Service | proposed |
| `WF-INV-008` | Nhận hàng hoàn về kho | API | Admin | proposed |
| `WF-INV-009` | Xác nhận xuất kho | API | Admin | proposed |
| `WF-INV-010` | Đóng băng và mở khóa SKU | Tự động + API | Lua script, Admin | proposed |

Bootstrap projection của Search hoặc Product Detail BFF không phải workflow của Inventory:
Inventory chỉ phát event; mỗi consumer tự điều phối reindex/rebuild. Thiết kế đích bổ
sung một export API cho bootstrap/recovery nhưng chưa được implement — xem
[Data §5](./data.md#5-khoảng-cách-giữa-thiết-kế-đích-và-schema-hiện-tại).

---

## WF-INV-001 — Lập route plan

### Mục tiêu

Cho checkout biết mỗi item sẽ được xuất từ kho nào, để bước reserve sau đó có địa chỉ
cụ thể thay vì phải tự đoán.

### Actor và participants

Checkout gọi thay mặt user đã đăng nhập. Inventory truy vấn `physical_inventory` và
`warehouses`, rồi lưu kết quả vào Redis.

### Trigger

`POST /v1/inventory/routing-options` từ Checkout Preview.

### Preconditions

Mọi SKU trong giỏ đã từng được restock ít nhất một lần. Kho phải `is_active = true`.

### Input

Danh sách `(sku_id, quantity)` và `customer_location` gồm mã tỉnh và mã quận. Schema
tại [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml).

### Happy path

1. API layer xác thực identity do Gateway inject.
2. Candidate generation: đọc `physical_inventory` cho toàn bộ `sku_id`, chỉ lấy dòng
   còn hàng khả dụng (dùng `idx_physical_inventory_sku_available`).
3. Với mỗi SKU, lọc các kho có `available_quantity >= quantity`.
4. Chọn kho theo `INV-BR-007`: ưu tiên kho cùng `city_code` với khách; nếu không
   có, lấy `warehouse_id` nhỏ nhất.
5. Sinh `route_plan_id`, `SET route_plan:{id}` với TTL bằng thời gian còn lại tới
   `expires_at`.
6. Trả plan kèm `product_id` denormalized để bước reserve không phải tra cứu lại.

### State/data changes

Không thay đổi dữ liệu durable. Chỉ ghi một key Redis có TTL. **Không giữ chỗ tồn kho.**

### Events emitted

Không có.

### Output

Route plan với `warehouse_id` cụ thể cho từng item và thời điểm hết hiệu lực.

### Failure paths

| Điểm hỏng | Trả về | State còn lại |
|---|---|---|
| SKU chưa từng restock | `404 SKU_NOT_FOUND` | Không có |
| Không kho nào đủ hàng cho một item | `409 INSUFFICIENT_STOCK` | Không có |
| Redis không ghi được plan | `503 INVENTORY_UNAVAILABLE` | Không có plan; client phải thử lại |
| PostgreSQL không khả dụng | `503 INVENTORY_UNAVAILABLE` | Không có |

### Timeout và retry

Client được phép retry vì workflow không mutate gì. Mỗi lần retry sinh plan mới với
`route_plan_id` khác; plan cũ vẫn tồn tại tới khi hết TTL — đây là rác chấp nhận được.

### Idempotency

Không áp dụng. Thuật toán deterministic với cùng input và cùng trạng thái tồn kho, nên
hai lần gọi liên tiếp thường cho cùng lựa chọn kho, nhưng `route_plan_id` luôn mới.

### Concurrency/race conditions

Hai user cùng lập plan cho SKU chỉ còn 1 đơn vị sẽ **cùng nhận được plan hợp lệ**. Đây
là hành vi có chủ đích: plan không giữ chỗ. Người reserve trước thắng, người sau nhận
`OUT_OF_STOCK` ở `WF-INV-002`.

### Compensation hoặc recovery

Không cần. Plan hết hạn tự biến mất.

### Metrics và traces

Thời gian sinh plan, tỉ lệ `INSUFFICIENT_STOCK`, tỉ lệ plan hết hạn trước khi được
dùng, số kho ứng viên trung bình mỗi SKU.

### Implementation evidence

Warehouse Selection Engine và route plan cache trong
[`java/services/inventory-service`](../../java/services/inventory-service) — chưa đối chiếu.

### Test scenarios

Một kho đủ hàng; nhiều kho đủ hàng có kho cùng tỉnh; nhiều kho đủ hàng không kho nào
cùng tỉnh (kiểm tra tie-break ổn định); một SKU thiếu hàng; SKU không tồn tại; giỏ có
nhiều SKU phải fulfil từ nhiều kho; Redis chết; hai lần gọi liên tiếp cho cùng input.

---

## WF-INV-002 — Reserve inventory

### Mục tiêu

Chuyển một lượng hàng từ khả dụng sang đã giữ chỗ tại đúng kho, atomic và không bán vượt.

### Actor và participants

Order Temporal Saga khởi tạo. Redis thực thi mutation. Relay Worker, Ledger Worker và
Debezium tham gia ở phần bất đồng bộ.

### Trigger

`ReserveInventoryActivity` ở bước trước thanh toán.

### Preconditions

Route plan còn hiệu lực và mỗi item đã có `warehouse_id` cụ thể.

### Input

`order_id`, `route_plan_id`, danh sách item kèm `sku_id`, `product_id`, `quantity`,
`warehouse_id`.

### Happy path

1. Contract guard: kho tồn tại và đang hoạt động; `route_plan_id` đúng định dạng.
2. Với mỗi item, chạy Lua script Reserve trên bốn key cùng hash slot: stock hash,
   idempotency key, stream outbox, cờ suspension.
3. Script lần lượt: kiểm tra cờ suspension → `SETNX` idempotency key → kiểm tra
   `available >= quantity` → `available -= qty`, `reserved += qty` → đặt TTL cho
   idempotency key → `XADD` event mutation vào stream.
4. Activity trả thành công cho Saga ngay sau khi mọi item được áp dụng.
5. Phần còn lại chạy bất đồng bộ theo [`WF-INV-005`](#wf-inv-005--materialize-mutation-vào-postgresql).

### State/data changes

Redis: `available` giảm, `reserved` tăng, ngay lập tức. PostgreSQL: chưa có gì tại
thời điểm Activity trả về — `physical_inventory`, `inventory_reservations` và
`inventory_ledger` được ghi sau, ở `WF-INV-005`.

### Events emitted

`INVENTORY_STOCK_MUTATION` với `change_type = ORDER_RESERVED` vào Redis Stream, sau đó
được forward sang Kafka. Đây là event nội bộ — xem
[Interfaces §1](./interfaces.md#1-contract-index).

### Output

Kết quả thành công cho Saga, hoặc một lỗi phân loại được.

### Failure paths

| Điểm hỏng | Kết quả | State còn lại |
|---|---|---|
| Cờ suspension tồn tại | `SKU_SUSPENDED` | Không item nào được áp dụng — dừng trước mọi mutation |
| Hết hàng ở item thứ *n* | `OUT_OF_STOCK` | Item 1..*n-1* **đã bị mutate** — phải release lại |
| Phát hiện drift | `INVARIANT_DRIFT` | Không mutate; SKU bị đóng băng |
| Redis không khả dụng | `INVENTORY_UNAVAILABLE` | Tùy điểm dừng — có thể có item đã áp dụng |
| Crash giữa các item | Activity fail | Item đã áp dụng vẫn giữ chỗ tới khi Saga compensate |

### Timeout và retry

Order Saga sở hữu deadline và retry policy. Retry cùng Activity là an toàn: script sẽ
trả idempotent hit cho item đã áp dụng và chỉ mutate item chưa áp dụng.
`INVARIANT_DRIFT` phải được cấu hình non-retryable.

### Idempotency

Khóa là `inventory:idemp:reserve:{sku_id:warehouse_id}:order:{order_id}`, TTL 30 ngày.
Replay trả thành công mà **không** mutate và **không** phát event mới. Ở tầng
PostgreSQL, `uq_inventory_reservations_active` là lớp chặn cuối.

### Concurrency/race conditions

Nhiều đơn cùng reserve một `(SKU, kho)` được Redis tuần tự hóa hoàn toàn — không có
race trong phạm vi một stock item. Race thật sự nằm ở chỗ khác: **request nhiều item
không phải transaction.** Giữa lúc item 1 được mutate và item 2 bị từ chối, một đơn
khác có thể đã reserve thành công phần hàng mà item 1 đang giữ. Rollback của
`INV-BR-004` vì thế phải release đúng phần mình đã lấy, không phải khôi phục số cũ.

### Compensation hoặc recovery

Khi một item thất bại, application layer gọi script Release cho các item đã áp dụng
trong chính request này, rồi trả lỗi tổng. Nếu bước rollback này cũng thất bại,
reservation mồ côi tồn tại trong Redis cho tới khi Saga compensate hoặc vận hành can
thiệp — đây là một khoảng trống đã biết, không có worker tự dọn.

### Metrics và traces

Tỉ lệ từng mã kết quả, độ trễ script, tỉ lệ idempotent hit, số lần rollback nội bộ
request, `correlation_id` = Temporal Workflow ID xuyên suốt tới ledger.

### Implementation evidence

Lua script Reserve, Activity adapter và bước rollback nội bộ request — chưa đối chiếu.

### Test scenarios

Reserve một item đủ hàng; đúng bằng số hàng còn lại; thiếu 1 đơn vị; nhiều item trong
đó item cuối thiếu hàng (kiểm tra rollback); replay cùng `order_id`; hai request đồng
thời trên cùng stock item; SKU đang bị đóng băng; Redis mất kết nối giữa chừng; script
gặp drift.

---

## WF-INV-003 — Deduct inventory

### Mục tiêu

Trừ hàng thật sau khi thanh toán thành công, biến lời hứa giữ chỗ thành giao dịch bán.

### Actor và participants

Order Temporal Saga; Redis; chuỗi worker bất đồng bộ phía sau.

### Trigger

`DeductInventoryActivity` sau khi Payment báo thành công.

### Preconditions

Đã có reservation cho đúng `(order, sku, kho)` và phần giữ chỗ vẫn còn.

### Input

`order_id` và danh sách item kèm `warehouse_id`.

### Happy path

1. Chạy Lua script Deduct với bốn key cùng slot.
2. Script: kiểm tra suspension → `SETNX` idempotency → **kiểm tra invariant
   `available == total - reserved`** → kiểm tra `reserved >= quantity` → tính
   `reserved -= qty`, `total -= qty`, `available = total - reserved` → **kiểm tra
   invariant lần hai** → ghi hash → `XADD` event.
3. Activity trả thành công.
4. Ledger Worker chuyển reservation sang `DEDUCTED` và trừ `actual_quantity` ở
   `WF-INV-005`.

### State/data changes

Redis: `total` và `reserved` cùng giảm, `available` không đổi. PostgreSQL sau đó:
`actual_quantity` và `reserved_quantity` giảm, reservation sang `DEDUCTED`, ledger ghi
`ORDER_DEDUCTED` với `quantity_change` âm.

### Events emitted

`INVENTORY_STOCK_MUTATION` với `change_type = ORDER_DEDUCTED`.

### Output

Thành công, hoặc lỗi phân loại được.

### Failure paths

| Điểm hỏng | Kết quả | Ghi chú |
|---|---|---|
| Cờ suspension | `SKU_SUSPENDED` | Không mutate |
| `reserved < quantity` | `INSUFFICIENT_RESERVED` | **Không rollback tự động** — tiền đã thu, cần điều tra |
| Invariant sai trước hoặc sau mutate | `INVARIANT_DRIFT` | Đóng băng SKU, alert |
| Redis chết | `INVENTORY_UNAVAILABLE` | Saga retry |

`INSUFFICIENT_RESERVED` ở bước này là tình huống nghiêm trọng: khách đã trả tiền nhưng
hàng không còn được giữ. Không có compensation tự động vì mọi lựa chọn tự động đều
tệ hơn việc dừng lại và báo người.

### Timeout và retry

Retry an toàn nhờ idempotency key riêng cho thao tác deduct. Không dùng chung khóa với
reserve — nếu dùng chung, retry deduct sẽ bị hiểu nhầm là replay reserve.

### Idempotency

`inventory:idemp:deduct:{sku_id:warehouse_id}:order:{order_id}`, TTL 30 ngày.

### Concurrency/race conditions

Deduct và Release của cùng một đơn không được chạy song song. Order Saga đảm bảo điều
này bằng thứ tự bước; Inventory không có khóa nào ngăn cản nếu Saga gọi sai — script
sẽ chỉ đơn giản áp dụng cả hai theo thứ tự Redis nhận được.

### Compensation hoặc recovery

Không có đường lùi tự động. Muốn trả hàng về kho phải đi qua luồng hàng hoàn
([`WF-INV-008`](#wf-inv-008--nhận-hàng-hoàn-về-kho)), không phải bằng cách gọi Release.

### Metrics và traces

Tỉ lệ `INSUFFICIENT_RESERVED` (phải gần 0 và có alert), tỉ lệ drift, độ trễ script.

### Implementation evidence

Lua script Deduct với hai lần kiểm tra invariant — chưa đối chiếu.

### Test scenarios

Deduct đúng phần đã giữ; deduct nhiều hơn phần giữ; deduct khi chưa reserve; replay;
hash bị sửa tay để tạo drift (kiểm tra script bắt được ở cả hai lần kiểm tra); deduct
song song với release.

---

## WF-INV-004 — Release reservation

### Mục tiêu

Trả phần đang giữ chỗ về khả dụng khi đơn không đi tới cùng.

### Actor và participants

Order Temporal Saga (compensation hoặc timer hết hạn).

### Trigger

`RollbackInventoryActivity` với `change_type` là `ORDER_CANCELLED` hoặc
`RESERVATION_EXPIRED`.

### Preconditions

Phần giữ chỗ tương ứng vẫn còn và chưa bị deduct.

### Input

`order_id`, danh sách item kèm `warehouse_id`, `change_type`.

### Happy path

1. Chạy Lua script Release.
2. Script: kiểm tra suspension → `SETNX` idempotency → kiểm tra `reserved >= quantity`
   → `reserved -= qty`, `available += qty` → `XADD` event mang `change_type` từ input.
3. Ledger Worker chuyển reservation sang `CANCELLED` và ghi ledger với
   `reference_type` tương ứng.

### State/data changes

Redis: `reserved` giảm, `available` tăng, `total` không đổi. PostgreSQL: reservation
sang `CANCELLED`, ledger ghi `quantity_change = 0` vì tồn kho vật lý không đổi.

### Events emitted

`INVENTORY_STOCK_MUTATION` với `change_type` từ input.

### Output

Thành công, hoặc lỗi phân loại được.

### Failure paths

`RELEASE_UNDERFLOW` khi `reserved < quantity` — dấu hiệu Saga gọi release hai lần với
hai idempotency key khác nhau, hoặc reservation đã bị deduct. Không tự sửa;
cần điều tra. `SKU_SUSPENDED` và `INVARIANT_DRIFT` xử lý như các luồng trên.

### Timeout và retry

Retry an toàn nhờ idempotency key riêng cho release.

### Idempotency

`inventory:idemp:release:{sku_id:warehouse_id}:order:{order_id}`, TTL 30 ngày.

**Điểm cần chú ý:** khóa idempotency không chứa `change_type`. Nếu Saga gọi release
với `ORDER_CANCELLED` rồi gọi lại với `RESERVATION_EXPIRED` cho cùng đơn và cùng item,
lần thứ hai là idempotent hit và ledger chỉ ghi lý do của lần đầu.

### Concurrency/race conditions

Release đồng thời với deduct: cái nào tới Redis trước thắng; cái sau nhận
`INSUFFICIENT_RESERVED` hoặc `RELEASE_UNDERFLOW`. Không có kịch bản cả hai cùng thành công.

### Compensation hoặc recovery

Release là chính nó đã là compensation. Không có compensation cho compensation.

### Metrics và traces

Tỉ lệ release theo `change_type`, tỉ lệ `RELEASE_UNDERFLOW`, thời gian từ reserve tới
release (cho biết bao nhiêu đơn chết vì hết hạn thanh toán).

### Implementation evidence

Lua script Release và Activity adapter — chưa đối chiếu.

### Test scenarios

Release đúng phần đã giữ; release nhiều hơn; release sau khi đã deduct; replay cùng
`change_type`; replay khác `change_type`; release song song với deduct.

---

## WF-INV-005 — Materialize mutation vào PostgreSQL

### Mục tiêu

Biến mutation đã commit ở Redis thành sổ cái durable, projection per-SKU và event công
bố — trong đúng một transaction.

### Actor và participants

Stream Relay Worker, Kafka, Kafka Ledger Worker, PostgreSQL, Debezium.

### Trigger

Một entry mới trong Redis Stream, sinh ra bởi `WF-INV-002`, `-003` hoặc `-004`.

### Preconditions

Mutation đã được áp dụng ở Redis. Bản thân sự tồn tại của entry là bằng chứng.

### Input

Event `INVENTORY_STOCK_MUTATION` gồm `sku_id`, `product_id`, `warehouse_id`,
`change_type`, `quantity`, trạng thái sau mutation và `order_id`/`order_item_id`.

### Happy path

1. Relay Worker (consumer group `inventory-stream-relay`) đọc stream, gắn CloudEvents
   header, produce Kafka `t.inventory.events` với key `sku_id`.
2. Relay `XACK` **chỉ sau khi** Kafka xác nhận.
3. Ledger Worker (consumer group `inventory-ledger-writer`) poll, **lọc theo
   `event_type`**, dedupe theo `event_id`, gom theo `sku_id`.
4. Mở một transaction: ghi ledger → cập nhật reservation theo `change_type` → cập nhật
   `physical_inventory` → tổng hợp tổng khả dụng theo SKU → insert outbox
   `INVENTORY_STOCK_CHANGED` → COMMIT.
5. Debezium đọc WAL, lấy `partition_key` làm Kafka key, publish.

### State/data changes

Toàn bộ thay đổi PostgreSQL của đường nóng xảy ra ở bước 4, tất cả trong một
transaction. Trước bước này, PostgreSQL không biết gì về mutation.

### Events emitted

`INVENTORY_STOCK_CHANGED` trên `t.inventory.events`, key `sku_id`. Xem
[Interfaces §4](./interfaces.md#4-events-phát-ra).

### Output

Không có caller đồng bộ. Kết quả là state durable và event đã publish.

### Failure paths

| Điểm hỏng | Hành vi | State còn lại |
|---|---|---|
| Kafka từ chối produce | Relay retry backoff, không `XACK` | Entry ở lại stream; Redis đã đúng, PostgreSQL tụt lại |
| Relay crash sau produce trước `XACK` | Entry được đọc lại | Event trùng — Ledger Worker dedupe bằng `event_id` |
| Ledger Worker crash giữa transaction | Rollback | Kafka offset chưa commit; batch được xử lý lại |
| PostgreSQL không khả dụng | Consumer lag tăng | Redis vẫn phục vụ đường nóng; khoảng phân kỳ nới rộng |
| Debezium chết | Outbox tích tụ, replication slot phình | Event chưa tới Search; dữ liệu không mất |
| Event của SKU đang bị đóng băng | Bỏ qua batch | Lớp an toàn; thực tế script đã chặn từ đầu |

### Timeout và retry

Relay retry với exponential backoff cho tới khi Kafka nhận. Ledger Worker retry theo
cơ chế consumer; batch lỗi vĩnh viễn phải vào DLQ, và **DLQ có message nghĩa là
PostgreSQL đang thiếu mutation mà Redis đã áp dụng** — đây là sự cố mức cao, không
phải cảnh báo thông thường.

### Idempotency

`inventory_ledger.event_id UNIQUE` là chốt chặn. Event trùng bị bỏ trước khi mở
transaction hoặc bị chặn bởi constraint.

### Concurrency/race conditions

Kafka key `sku_id` giữ thứ tự trong phạm vi một SKU, nên mutation của cùng một SKU
không bị đảo. Hai SKU khác nhau có thể được xử lý song song. Vì event tồn kho hiện
**không mang revision**, thứ tự Kafka là thứ duy nhất bảo vệ consumer khỏi áp dụng
state cũ — đây là lý do partition key phải là `sku_id`, không phải `order_id`, và là
lý do [ADR-007](./decisions/ADR-007-stock-snapshot-v1-for-search.md) tồn tại.

### Compensation hoặc recovery

Không có compensation: đây là đường ghi sổ, không phải giao dịch nghiệp vụ. Recovery
là để consumer bắt kịp. Nếu ledger bị mất vĩnh viễn một mutation, cách duy nhất là đối
soát Redis với `physical_inventory` và sửa tay.

### Metrics và traces

Độ sâu stream chưa ack, độ trễ relay, consumer lag của `inventory-ledger-writer`, số
row DLQ, độ trễ CDC, độ trễ replication slot, số lần dedupe theo `event_id`.

### Implementation evidence

Relay Worker, Ledger Worker và cấu hình Debezium connector — chưa đối chiếu.

### Test scenarios

Event đơn lẻ; batch nhiều event cùng SKU; event trùng; Kafka down rồi lên lại; PostgreSQL down giữa transaction;
event của SKU đang đóng băng; thứ tự event bị đảo giữa hai SKU khác nhau.

---

## WF-INV-006 — Admin restock

### Mục tiêu

Đưa hàng vào kho, và khởi tạo bản ghi tồn kho lần đầu cho một `(SKU, kho)`.

### Actor và participants

Admin qua Gateway; PostgreSQL; Redis; Debezium.

### Trigger

`POST /v1/inventory/restock` với `reference_type = ADMIN_RESTOCK`.

### Preconditions

Kho tồn tại và đang hoạt động; `quantity > 0`.

### Input

`warehouse_id`, `sku_id`, `product_id`, `quantity`. `product_id` được lưu denormalized
và dùng cho mọi event sau này.

### Happy path

1. Contract guard kiểm tra kho và số lượng.
2. Một transaction: upsert `physical_inventory` cộng dồn `actual_quantity` → ghi ledger
   `ADMIN_RESTOCK` với `quantity_change` dương → insert outbox
   `INVENTORY_STOCK_CHANGED` → COMMIT.
3. Sau commit, đồng bộ Redis hash cho `(sku, kho)`.
4. Debezium publish snapshot cho Search.

### State/data changes

`actual_quantity` tăng, `reserved_quantity` không đổi, `available_quantity` tự tăng
theo cột generated. Redis hash được đặt lại theo giá trị mới.

### Events emitted

`INVENTORY_STOCK_CHANGED` với `change_type = ADMIN_RESTOCK`.

### Output

Trạng thái tồn kho sau khi nhập, kèm `event_id` để đối soát.

### Failure paths

| Điểm hỏng | Kết quả | State còn lại |
|---|---|---|
| Kho không tồn tại/không hoạt động | `404 WAREHOUSE_NOT_FOUND` | Không có |
| `quantity <= 0` | `400 INVALID_REQUEST` | Không có |
| Transaction rollback | `503` | Không có gì được ghi, không event nào phát |
| **Đồng bộ Redis thất bại sau commit** | Trả 200 nhưng Redis stale | Đường nóng vẫn thấy số cũ — hàng vừa nhập chưa bán được |

Trường hợp cuối là điểm yếu thật của luồng này: PostgreSQL đúng, Redis sai, và không
có gì tự sửa. Cần alert riêng và quy trình đồng bộ lại thủ công.

### Timeout và retry

**Không được retry mù.** Operation không idempotent: mỗi lần gọi cộng thêm. Admin gặp
timeout phải tra `inventory_ledger` theo `correlation_id` để xác định request trước đã
commit hay chưa.

### Idempotency

Không có. Đây là quyết định đã biết và được ghi ở
[Interfaces §2](./interfaces.md#2-http-apis); thêm idempotency cần một bảng lưu kết quả
command mà schema hiện chưa có.

### Concurrency/race conditions

Hai lần restock đồng thời cùng `(sku, kho)` đều đúng nhờ phép cộng tương đối trong
`ON CONFLICT DO UPDATE`. Hai event được phát; vì event không mang revision, consumer
chỉ có thứ tự Kafka để biết cái nào mới hơn.

### Compensation hoặc recovery

Không có API hoàn tác. Nhập nhầm phải sửa bằng một lần restock âm — mà enum
`reference_type` hiện **không** hỗ trợ. Đây là khoảng trống nghiệp vụ: điều chỉnh tồn
kho do kiểm kê chưa có đường đi hợp lệ.

### Metrics và traces

Số lần restock theo kho, `correlation_id` từ header hoặc tự sinh, tỉ lệ đồng bộ Redis
thất bại sau commit.

### Implementation evidence

Restock handler và bước đồng bộ Redis — chưa đối chiếu.

### Test scenarios

Restock lần đầu cho `(SKU, kho)` mới; restock cộng dồn; kho không hoạt động; số lượng
âm hoặc 0; Redis chết sau commit; hai restock đồng thời; kiểm tra đúng một event được
ghi outbox mỗi lần gọi.

---

## WF-INV-007 — Đăng ký refund expectation

### Mục tiêu

Khóa trước tập item mà Order dự kiến sẽ nhận hoàn về kho, để Inventory biết khi nào
"đã nhận đủ".

### Actor và participants

Order Service gọi qua HTTP nội bộ.

### Trigger

`POST /v1/inventory/returns/refund-expectations`.

### Preconditions

Các `order_item_id` trong request phải có reservation ở trạng thái `DEDUCTED`.

### Input

`order_id`, `refund_request_id`, danh sách `order_item_ids`.

### Happy path

1. Xác thực service identity và header `X-Internal-Service`.
2. Đọc tập item đã đăng ký cho `(order_id, refund_request_id)`.
3. Nếu tập đã tồn tại và trùng khớp → trả thành công idempotent.
4. Nếu chưa có → cập nhật các reservation khớp, đặt `refund_expected = true` và
   `refund_request_id`.
5. Trả số dòng đã đánh dấu.

### State/data changes

`inventory_reservations.refund_expected` và `refund_request_id` được điền. Không đổi
`status`, không đụng tồn kho.

### Events emitted

Không có.

### Output

Số reservation được đánh dấu; replay trả 0.

### Failure paths

| Điểm hỏng | Kết quả |
|---|---|
| Không reservation `DEDUCTED` nào khớp | `404 RESERVATION_NOT_FOUND` |
| Cùng khóa refund nhưng khác tập item | `409 REFUND_SCOPE_MISMATCH` |
| Service identity sai | `401`/`403` |

### Timeout và retry

Retry an toàn: luồng idempotent theo bộ ba `(order_id, refund_request_id,
order_item_ids)`.

### Idempotency

Không dựa vào `Idempotency-Key` mà dựa vào chính dữ liệu: tập item đã đăng ký là bằng
chứng. Đây cũng là lý do đăng ký lại với tập khác bị từ chối thay vì ghi đè — ghi đè
sẽ làm điều kiện "đã nhận đủ" dịch chuyển sau khi hàng đã bắt đầu về.

### Concurrency/race conditions

Hai request đồng thời với cùng khóa và cùng tập item: cả hai đều thành công, một trong
hai cập nhật 0 dòng. Hai request đồng thời với tập khác nhau: kết quả phụ thuộc thứ tự
— một thành công, một nhận `REFUND_SCOPE_MISMATCH`.

### Compensation hoặc recovery

Không có API hủy đăng ký. Nếu Order đăng ký nhầm scope, phải sửa dữ liệu bằng tay
trước khi hàng bắt đầu về kho.

### Metrics và traces

Số scope được đăng ký, tỉ lệ `REFUND_SCOPE_MISMATCH` (bất thường nếu > 0).

### Implementation evidence

Handler refund-expectations — chưa đối chiếu.

### Test scenarios

Đăng ký lần đầu; replay đúng tập; replay khác tập; item không ở trạng thái `DEDUCTED`;
tập rỗng; hai request đồng thời.

---

## WF-INV-008 — Nhận hàng hoàn về kho

### Mục tiêu

Cộng hàng hoàn vào kho và, khi đã nhận đủ toàn bộ scope, báo cho Order rằng có thể
hoàn tiền.

### Actor và participants

Admin; PostgreSQL; Redis; Debezium; Order (consumer).

### Trigger

`POST /v1/inventory/restock` với `reference_type = ORDER_RETURNED_RESTOCK`.

### Preconditions

Order đã đăng ký refund expectation ở `WF-INV-007`. Reservation tương ứng ở trạng thái
`DEDUCTED` và chưa có `refund_returned_at`.

### Input

Như restock thường, cộng thêm `order_id`, `order_item_id`, `refund_request_id`.

### Happy path

1. Contract guard: kho hợp lệ; số lượng dương; reservation `DEDUCTED` tồn tại và có
   `refund_expected = true`.
2. Một transaction:
   - upsert `physical_inventory` cộng `actual_quantity`;
   - đặt `refund_returned_at` cho reservation (chỉ khi còn `NULL`);
   - ghi ledger `ORDER_RETURNED_RESTOCK`;
   - insert outbox `INVENTORY_STOCK_CHANGED`;
   - đếm số item còn thiếu của `(order_id, refund_request_id)`;
   - nếu bằng 0 → insert outbox `INVENTORY_RETURN_RECEIVED_ALL`;
   - COMMIT.
3. Đồng bộ Redis hash sau commit.
4. Debezium publish; Order nhận signal và mở bước hoàn tiền.

### State/data changes

Tồn kho tăng; reservation được đánh dấu đã nhận về nhưng **giữ nguyên `DEDUCTED`**.
Trạng thái reservation không quay lại vì hàng đã thực sự được bán rồi trả lại — đó là
hai sự kiện khác nhau trong sổ cái.

### Events emitted

`INVENTORY_STOCK_CHANGED` (luôn có) và `INVENTORY_RETURN_RECEIVED_ALL` (chỉ ở item cuối).

### Output

Trạng thái tồn kho mới, thông tin refund và cờ cho biết đã nhận đủ hay chưa.

### Failure paths

| Điểm hỏng | Kết quả |
|---|---|
| Không có reservation `DEDUCTED` khớp | `404 RESERVATION_NOT_FOUND` |
| Reservation có nhưng chưa được đăng ký scope | `409 REFUND_NOT_EXPECTED` |
| Kho không hợp lệ | `404 WAREHOUSE_NOT_FOUND` |
| Transaction rollback | `503`; không tồn kho nào tăng, không signal nào phát |
| Đồng bộ Redis thất bại sau commit | Redis stale như `WF-INV-006` |

### Timeout và retry

Không idempotent — giống restock thường, retry mù sẽ cộng tồn kho hai lần. Tuy nhiên
`refund_returned_at` chỉ được đặt khi còn `NULL`, nên lần gọi thứ hai **không** làm
đếm sai điều kiện hoàn tất; nó chỉ làm sai số lượng tồn kho.

### Idempotency

Ở mức signal: `uq_inventory_outbox_return_received` bảo đảm `INVENTORY_RETURN_RECEIVED_ALL`
phát đúng một lần cho mỗi `(order_id, refund_request_id)`. Ở mức tồn kho: không có.

### Concurrency/race conditions

Hai admin cùng nhận hai item cuối cùng của một scope có thể cùng thấy số item còn
thiếu bằng 0 và cùng thử insert signal. Câu lệnh điều kiện **không** ngăn được điều
này; partial unique index mới là thứ ngăn. Transaction thua sẽ rollback và cần retry ở
tầng ứng dụng.

### Compensation hoặc recovery

Không có. Signal đã phát không thu hồi được; Order phải tự bảo vệ bằng dedupe theo
`event_id` và `refund_request_id`.

### Metrics và traces

Thời gian từ đăng ký scope tới khi nhận đủ, số scope treo quá lâu, tỉ lệ
`REFUND_NOT_EXPECTED`.

### Implementation evidence

Nhánh `ORDER_RETURNED_RESTOCK` của restock handler — chưa đối chiếu.

### Test scenarios

Nhận item duy nhất của scope (phát signal ngay); nhận lần lượt nhiều item; nhận item
cuối; gọi lại cho item đã nhận; item chưa đăng ký scope; hai request đồng thời cho hai
item cuối; kiểm tra chỉ có đúng một signal trong outbox.

---

## WF-INV-009 — Xác nhận xuất kho

### Mục tiêu

Báo cho Order rằng kho đã giao hàng cho khách, để Order chuyển item sang đã giao.

### Actor và participants

Admin; PostgreSQL (chỉ outbox); Debezium; Order (consumer).

### Trigger

`POST /v1/inventory/ship-to-customer`.

### Preconditions

Tồn tại ít nhất một reservation `DEDUCTED` tại đúng `(order_id, warehouse_id)`.

### Input

`order_id`, `warehouse_id`, `shipped_at`, và `courier_note` tùy chọn.

### Happy path

1. Kiểm tra kho hợp lệ và có reservation `DEDUCTED` tại đúng kho đó.
2. Đếm số item bị ảnh hưởng, lọc theo `warehouse_id`.
3. Một transaction chỉ chứa một `INSERT inventory_outbox`.
4. Debezium publish với key `order_id`; Order chuyển các item của đúng kho sang đã giao.

### State/data changes

**Không có.** Không tồn kho, không reservation, không ledger. Xem
[ADR-009](./decisions/ADR-009-ship-signal-without-ledger.md).

### Events emitted

`INVENTORY_SHIP_TO_CUSTOMER`, key `order_id`.

### Output

Xác nhận kèm số item bị ảnh hưởng.

### Failure paths

| Điểm hỏng | Kết quả |
|---|---|
| Không có reservation nào cho đơn | `404 ORDER_NOT_FOUND` |
| Có đơn nhưng không có reservation `DEDUCTED` tại kho này | `404 RESERVATION_NOT_FOUND` |
| Kho không hợp lệ | `404 WAREHOUSE_NOT_FOUND` |

Lưu ý: Inventory không sở hữu bảng đơn hàng, nên "đơn tồn tại" chỉ có nghĩa là "có
reservation mang `order_id` này".

### Timeout và retry

An toàn khi gọi lại: Order dedupe theo `event_id`. Hai lần bấm của admin sinh hai
event nhưng chỉ một lần có tác dụng.

### Idempotency

Ở phía Inventory: không có — mỗi lần gọi sinh một outbox row. Ở phía consumer: dedupe
theo `event_id`. Đây là idempotency được đẩy sang consumer một cách có chủ đích.

### Concurrency/race conditions

Với đơn nhiều kho, admin phải gọi riêng từng kho. Bỏ lọc `warehouse_id` ở bất kỳ bước
nào — validate, đếm, hay ở consumer — sẽ đánh dấu đã giao cho item còn nằm trong kho khác.

### Compensation hoặc recovery

Không có. Gửi nhầm signal phải xử lý ở phía Order.

### Metrics và traces

Số lần xác nhận theo kho, số item mỗi lần, khoảng cách thời gian giữa deduct và xuất kho.

### Implementation evidence

Handler ship-to-customer — chưa đối chiếu.

### Test scenarios

Đơn một kho; đơn nhiều kho gọi từng kho; gọi cho kho không có item của đơn; gọi hai
lần; `order_id` không tồn tại; kiểm tra `courier_note` không lọt vào payload Kafka.

---

## WF-INV-010 — Đóng băng và mở khóa SKU

### Mục tiêu

Dừng mọi biến động trên một `(SKU, kho)` ngay khi số liệu được phát hiện là sai, và
chỉ cho chạy lại sau khi có người xác nhận đã đối soát.

### Actor và participants

Lua script phát hiện; application layer đặt cờ; vận hành đối soát; admin mở khóa.

### Trigger

Script Deduct trả mã drift ở lần kiểm tra invariant trước hoặc sau mutate.

### Preconditions

Không có — đây là luồng phòng vệ, kích hoạt bất cứ lúc nào.

### Input

`sku_id`, `warehouse_id` của stock item bị nghi ngờ.

### Happy path

1. Script trả mã drift và **không** mutate gì.
2. Application dừng request ngay, không retry, không rollback.
3. Đặt `inventory:suspended:{sku_id:warehouse_id}` với TTL 24 giờ, ghi lý do và thời điểm.
4. Phát alert vận hành.
5. Vận hành đối soát Redis hash với `physical_inventory` và ledger, sửa số liệu bằng tay.
6. Admin gọi `POST /v1/inventory/admin/unlock-sku` kèm lý do và mã phiên vận hành.
7. Mutation tiếp theo trên stock item đó đi qua bình thường.

### State/data changes

Chỉ một key Redis được tạo rồi xóa. Không dữ liệu nghiệp vụ nào bị đụng tới.

### Events emitted

Không có event Kafka. Việc mở khóa được ghi vào log ứng dụng — **không** vào ledger,
vì nó không phải biến động tồn kho. Đây là khoảng trống audit đã ghi ở
[Data §10](./data.md#10-security-và-privacy).

### Output

Xác nhận đã mở khóa và thời điểm mở.

### Failure paths

| Điểm hỏng | Kết quả |
|---|---|
| Mở khóa SKU không bị đóng băng | `404 SKU_NOT_SUSPENDED` |
| Đặt cờ suspension thất bại (Redis chết) | Stock item **không** được bảo vệ — mutation tiếp theo vẫn chạy trên dữ liệu sai |
| TTL hết trước khi có người xử lý | Cờ tự biến mất; hệ thống lại nhận mutation trên dữ liệu chưa được đối soát |

Hai dòng cuối là rủi ro thật của thiết kế: TTL 24 giờ vừa là cơ chế dọn rác vừa là
cách hệ thống âm thầm tự mở khóa. Đây là trade-off được chấp nhận trong
[ADR-005](./decisions/ADR-005-fail-safe-suspension.md), không phải điều mong muốn.

### Timeout và retry

Tuyệt đối không retry request gặp drift. Retry chỉ làm hỏng thêm dữ liệu đã sai.

### Idempotency

Đặt cờ nhiều lần là vô hại. Mở khóa lần thứ hai trả `SKU_NOT_SUSPENDED`.

### Concurrency/race conditions

Request đang chạy dở khi cờ được đặt vẫn hoàn tất — cờ chỉ chặn request **sau** đó.
Với những mutation đang bay, không có cách nào dừng lại.

### Compensation hoặc recovery

Recovery là quy trình vận hành, không phải code: so sánh Redis, `physical_inventory`
và tổng ledger; xác định bên nào đúng; sửa Redis theo PostgreSQL hoặc ngược lại; ghi
lại quyết định; rồi mới mở khóa. Runbook chi tiết ở [Operations §10](./operations.md).

### Metrics và traces

Số lần phát hiện drift (phải bằng 0; mọi giá trị khác đều là sự cố), số stock item
đang bị đóng băng, thời gian từ lúc đóng băng tới lúc mở, số lần cờ hết hạn mà không
có ai mở khóa.

### Implementation evidence

Nhánh xử lý mã drift trong Lua adapter và handler unlock — chưa đối chiếu.

### Test scenarios

Tạo drift nhân tạo rồi kiểm tra script bắt được ở cả hai lần kiểm tra; xác nhận mọi
loại mutation đều bị chặn khi đang đóng băng; mở khóa rồi mutate lại; mở khóa SKU
không bị khóa; Redis chết đúng lúc cần đặt cờ; cờ hết hạn mà chưa ai xử lý.

---

## Kịch bản test bắt buộc chung

Mọi workflow phải có: happy path, biên của validation, replay/duplicate, timeout,
retry, hai request đồng thời, dependency chết giữa chừng, và đường phục hồi. Riêng các
luồng đi qua Redis phải có thêm test cho trường hợp key không cùng hash slot và trường
hợp Redis trả lỗi giữa một chuỗi nhiều item.
