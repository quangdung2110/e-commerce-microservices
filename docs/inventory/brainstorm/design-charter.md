---

## doc_id: inventory-design-charter
service: inventory
scope: target
status: proposed
owners:
  - Inventory Service team
reviewers:
  - Order Service team
  - Checkout Service team
  - Payment Service team
  - Search Service team
  - Warehouse operations
  - Platform team
evidence:
  - docs/inventory
  - docs/inventory/brainstorm/redesign-plan.md
verified_against_commit: null

# Inventory Service — Design Charter (Phase 0)

> Publication update — 2026-10-02: [ADR-020](../decisions/ADR-020-command-receipts-and-minimal-order-events.md)
> supersedes earlier per-mutation outbox wording in this historical working record.
> Reservation commands use durable receipts and query/replay; only dispatch and selected-scope
> inspection completion are public Order facts. Required public outbox and availability
> dirty work remain atomic with their originating transition. Parent target documents govern.

> Đây là working draft cho Phase 0 của kế hoạch thiết kế lại. Nó chốt mục tiêu và
> ràng buộc cần đáp ứng, **không** chọn implementation, database, cache, message
> broker, schema hay API. Mọi mục đánh dấu `TBD` cần một owner xác nhận trước khi
> Phase 0 có thể hoàn tất.



## 1. Mục đích

Inventory Service phải cho hệ thống một câu trả lời đáng tin cậy cho ba câu hỏi:

1. Một SKU hiện còn bao nhiêu đơn vị có thể bán?
2. Số đơn vị đó thuộc kho hoặc allocation nào?
3. Đơn vị đã bị reserve, bán, xuất kho, hủy, điều chỉnh hoặc hoàn về vì lý do gì?

Charter này xác định tiêu chí để đánh giá các thiết kế ở Phase 1–8. Nó không kế thừa
các lựa chọn kỹ thuật từ service cũ như Redis là commit source, Redis Stream relay,
Lua script, Debezium, TTL hay định dạng endpoint.

## 2. Scope của release đầu



### In scope — cần xác nhận


| Capability                                | Trạng thái | Owner xác nhận                         | Ghi chú                                                                                      |
| ----------------------------------------- | ---------- | -------------------------------------- | -------------------------------------------------------------------------------------------- |
| Quản lý tồn kho theo kho                  | Confirmed  | Warehouse operations                   | Allocation phải biết tồn kho và trạng thái hoạt động của từng kho. Lot/zone/condition vẫn out of scope tạm thời. |
| Allocate và reserve cho checkout/order    | Confirmed  | Checkout + Order                       | Mỗi line item dùng một kho; reserve toàn order là `REJECT_ALL`, không backorder/partial fulfillment. |
| Commit/release tồn kho theo lifecycle đơn | Confirmed  | Order + Payment                        | Order owns retry/timeout/release; thời điểm commit sale vẫn là open decision của Phase 2–3. |
| Nhập hàng và stock adjustment có audit    | Confirmed  | Warehouse operations                   | Release đầu hỗ trợ adjustment tăng/giảm; chỉ `ADMIN` được thực hiện, bắt buộc reason code và actor audit. Không cần maker-checker. |
| Nhận hàng hoàn và điều kiện hoàn tiền     | Confirmed  | Order + Payment + Warehouse operations | Receipt bắt buộc qua inspection/quarantine trước khi về sellable; partial-return semantics còn cần thiết kế. |
| Cấp state tồn kho cho Search              | Candidate  | Search                                 | Cần chốt dữ liệu và freshness cần thiết.                                                     |
| Audit/reconciliation/recovery             | Candidate  | Inventory + Platform                   | Không dùng sửa dữ liệu tay như workflow mặc định.                                            |




### Out of scope tạm thời — cần xác nhận

Các mục dưới đây không được đưa vào release đầu trừ khi business owner yêu cầu rõ:

- Tracking lot/batch, serial number, expiry date hoặc FIFO/FEFO.
- Multi-tenant hoặc stock ownership theo merchant.
- Tối ưu chi phí vận chuyển, carrier integration và ETA.
- Warehouse management đầy đủ: bin location, picking wave, packing, barcode scan.
- Auto-rebalancing hoặc transfer liên kho.
- Quarantine/quality workflow phức tạp cho hàng hoàn.

Inspection/quarantine **cơ bản** của hàng hoàn vẫn in scope theo `INV-CH-004`; mục out
of scope ở trên chỉ loại trừ các workflow WMS chuyên sâu như nhiều bước kiểm tra, phân
loại chất lượng chi tiết hoặc tích hợp thiết bị.

Mục nào được đưa trở lại scope phải cập nhật glossary, aggregate boundary và load profile
trước Phase 2.

## 3. Bằng chứng và vấn đề phải giải quyết

Các điểm sau được rút từ tài liệu thiết kế cũ. Đây là input để giải quyết, không phải
quyết định đã chấp nhận cho thiết kế mới.


| Evidence                                                                             | Hệ quả cho charter                                                                 |
| ------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| Redis commit trước ở đường nóng, PostgreSQL commit trước ở admin path                | Cần chốt số nguồn chân lý, consistency target và cách recovery.                    |
| Multi-item reserve dùng chuỗi mutation và compensation                               | Cần chốt atomic scope: toàn order, order line, allocation hay partial fulfillment. |
| `actual/physical` giảm lúc payment nhưng shipment không đổi tồn kho                  | Phải thống nhất ngôn ngữ và thời điểm thay đổi từng stock bucket.                  |
| Restock và return restock không idempotent                                           | Mọi command mutation cần command ID, replay và timeout policy.                     |
| Route plan dựa trên state có thể stale                                               | Cần chốt allocation là preview hay commitment, và UX khi reserve thất bại.         |
| Reservation expiry do Order timer sở hữu, không có safety net                        | Cần chốt owner lifecycle và policy cho orphan reservation.                         |
| Manual reconciliation/suspension và outbox cleanup có nguy cơ mất event              | Cần định nghĩa recovery, audit, RPO/RTO và operational safety trước kiến trúc.     |
| Search cần stock snapshot có ordering/bootstrap nhưng contract đích chưa có provider | Cần chốt state public, freshness, versioning và bootstrap/replay semantics.        |




## 4. Priorities cần xếp hạng

Business owner xếp từ `1` (cao nhất) đến `7` (thấp nhất). Không được để hai mục cùng
hạng trừ khi chấp nhận trade-off và chi phí để đạt cả hai.


| Priority                      | Hạng | Ý nghĩa nếu được ưu tiên                                                        |
| ----------------------------- | ---- | ------------------------------------------------------------------------------- |
| Không oversell                | 1    | Mutation và concurrency model phải từ chối bán quá stock theo policy.           |
| Checkout latency              | 5    | Đặt budget cho allocation/reserve và ảnh hưởng trực tiếp cách chọn commit path. |
| Availability của checkout     | 4    | Quyết định fail-closed/degraded khi dependency mất.                             |
| Độ tươi của stock trên Search | 7    | Đặt freshness target cho projection/event/read model.                           |
| Audit và tính giải trình      | 2    | Quyết định ledger, command history, approval và retention.                      |
| Đơn giản, dễ vận hành         | 3    | Hạn chế số data store, worker và recovery procedure.                            |
| Throughput/scalability        | 6    | Đặt tiêu chí cần đạt trước khi thêm kiến trúc phân tán phức tạp.                |




### Candidate hard invariants


| ID            | Candidate invariant                                                                               | Owner phê duyệt                  | Status |
| ------------- | ------------------------------------------------------------------------------------------------- | -------------------------------- | --- |
| `INV-TGT-001` | Không order nào được commit vượt quá stock sellable đã được policy cho phép.                      | Checkout + Order + Inventory     | **Accepted** |
| `INV-TGT-002` | Mỗi stock mutation được áp dụng tối đa một lần về nghiệp vụ, kể cả khi caller timeout hoặc retry. | Order + Inventory + Platform     | **Accepted** |
| `INV-TGT-003` | Mọi thay đổi stock hoặc adjustment đều truy vết được bằng actor/system actor, reason, command ID và timestamp. | Warehouse operations + Inventory | **Accepted** |
| `INV-TGT-004` | Không service nào ghi trực tiếp vào persistent store của Inventory.                               | Architecture owners              | **Accepted** |
| `INV-TGT-005` | State public của tồn kho có ordering/deduplication và đường bootstrap/recovery xác định được.     | Search + Inventory               | **Accepted** |
| `INV-TGT-006` | Mọi stock adjustment có direction, reason code, actor `ADMIN`, command ID và timestamp.           | Warehouse operations + Inventory | **Accepted** |

### Quyết định đã chốt trong Phase 0

| ID | Quyết định | Hệ quả bắt buộc cho các phase sau |
| --- | --- | --- |
| `INV-CH-001` | **Không oversell là quy luật tuyệt đối.** | Phase 2 phải định nghĩa chính xác `sellable`; Phase 3 và 6 phải có một cơ chế đồng thời có thể chứng minh rằng commit sale không vượt stock sellable. Không dùng eventual consistency hay retry để hợp thức hóa oversell. |
| `INV-CH-002` | **Release đầu fulfil trọn vẹn mỗi line item từ đúng một kho.** Không split quantity của cùng line item sang nhiều kho. | Phase 3–4 phải chọn duy nhất một warehouse allocation cho mỗi line item trước reserve. Khi không kho đơn lẻ nào đủ toàn bộ quantity, line item không được fulfil ở release đầu. |
| `INV-CH-003` | **Reserve hết hàng phải fail-fast và từ chối đơn ở release đầu.** Hệ thống không tự âm thầm đổi kho. | Allocation/preview không phải bảo đảm thành công. Khi reserve thất bại, Checkout nhận outcome rõ ràng để kết thúc hoặc bắt đầu một interaction mới; auto-reroute là capability release sau và cần customer confirmation. |
| `INV-CH-004` | **Mọi hàng hoàn phải qua `INSPECTION`/`QUARANTINE`; tuyệt đối không trở lại sellable stock ngay khi nhận.** | Phase 2 và 5 phải có stock bucket/lifecycle riêng cho hàng hoàn; chỉ command/approval kiểm tra đạt mới được chuyển hàng về sellable stock. Receipt hàng hoàn và refund signal không được ngầm đồng nghĩa “có thể bán lại”. |
| `INV-CH-005` | **Khi nguồn tồn kho authoritative không khả dụng, fail-closed mọi mutation ảnh hưởng sellable stock.** Không tạo pending order để xử lý sau. | Reserve, commit sale, release và stock adjustment phải trả kết quả hạ tầng rõ ràng, có command ID để Order/Admin kiểm tra hoặc retry an toàn sau recovery. |
| `INV-CH-006` | **Search không là dependency đồng bộ của Checkout.** Search unavailable hoặc stale không được chặn checkout/reserve. | Search chỉ là read projection eventual-consistent; Phase 7–8 phải có contract và monitoring riêng để theo dõi freshness/recovery. |
| `INV-CH-007` | **Transactional outbox là bắt buộc.** Khi event backbone lỗi, command được tiếp tục chỉ khi mutation nghiệp vụ và outbox record cùng commit durable trong một transaction. | Không publish event trực tiếp sau commit. Nếu không thể ghi atomically cả mutation lẫn outbox, command phải fail thay vì để audit/event mất hoặc không thể replay. |
| `INV-CH-008` | **Order Saga/Orchestrator sở hữu retry và timeout của reservation.** Reservation timeout là **15 phút**; Order phát release khi payment fail hoặc hết hạn. | Inventory xử lý idempotent và lưu `expires_at` để quan sát. Reservation `RESERVED` quá **60 phút** chỉ tạo orphan alert và manual escalation, không tự release. |
| `INV-CH-009` | **Release đầu hỗ trợ stock adjustment tăng (`+`) và giảm (`-`), chỉ role `ADMIN` được thực hiện.** | Adjustment bắt buộc có reason code và actor audit. Không yêu cầu maker-checker ở release đầu; Phase 5 phải thiết kế command, reason taxonomy và ledger/audit record. |
| `INV-CH-010` | **Reserve toàn order là All-or-Nothing.** Nếu một line item hết hàng, kết quả là `REJECT_ALL`; release đầu không có backorder hoặc partial fulfillment. | Inventory phải release ngay mọi item đã reserve thành công trong cùng request trước khi trả kết quả từ chối cuối cùng. Phase 3 và 6 phải thiết kế atomic transaction hoặc recovery protocol đủ mạnh để không để reservation còn sót sau một `REJECT_ALL`. |
| `INV-CH-011` | **Allocation preview trả `503 INVENTORY_UNAVAILABLE` khi primary inventory store không khả dụng.** | Không phát route/allocation plan từ state không authoritative hoặc cache stale. Checkout phải hiển thị lỗi hạ tầng có thể retry, không được xem đây là `OUT_OF_STOCK`. |
| `INV-CH-012` | **Cache chỉ là read optimization, không có thẩm quyền quyết định correctness.** | Cache miss/outage phải fallback về authoritative source khi source còn sẵn sàng. Cache không được dùng để accept/reject reserve, commit sale hoặc định nghĩa stock sellable. |
| `INV-CH-013` | **Allocation ban đầu bắt buộc loại warehouse `inactive` hoặc `outage`, rồi chọn kho khả dụng tiếp theo theo policy.** | Đây là lựa chọn kho chủ động trước reserve, không phải auto-reroute sau reserve failure. Kho thay thế vẫn phải đủ toàn bộ quantity của line item và `REJECT_ALL` vẫn áp dụng nếu không tìm được kho hợp lệ. |
| `INV-CH-014` | **Mọi stock mutation phải có audit trail.** | Audit trail ghi actor hoặc system actor, reason, command ID và timestamp. Phase 2/5/6 phải bảo đảm cả command hệ thống (reserve, release, commit sale) lẫn command admin đều có causation rõ. |
| `INV-CH-015` | **Persistent store của Inventory chỉ được ghi qua Inventory Service.** | Order, Checkout, admin tool, worker hay service khác chỉ tương tác qua contract đã công bố; không có shared-table/shared-database integration. |
| `INV-CH-016` | **State tồn kho public cho Search phải có ordering, deduplication và bootstrap/recovery.** | Phase 7 phải định nghĩa version/revision và export/replay semantics; không dựa duy nhất vào thứ tự broker hoặc retention hữu hạn. |

### Hệ quả về hướng thiết kế — chưa phải lựa chọn kiến trúc

Ưu tiên đã xếp hạng đặt **không oversell**, **audit** và **đơn giản vận hành** cao hơn
checkout latency và throughput. Vì vậy, Phase 6 phải bắt đầu từ mô hình một nguồn
chân lý durable, transaction boundary rõ và audit trail đầy đủ. Bất kỳ đề xuất nào có
nhiều commit source hoặc consistency bất đồng bộ trên đường quyết định bán hàng phải
chứng minh được lợi ích đo được và vẫn đáp ứng `INV-CH-001`.




## 5. Load profile và SLO cần thu thập

Không suy đoán số liệu. Mỗi giá trị phải đánh dấu `measured`, `estimated` hoặc `unknown`,
kèm nguồn và ngày thu thập.


| Metric / target                        | Giá trị | Đơn vị           | Confidence | Owner / source          |
| -------------------------------------- | ------- | ---------------- | ---------- | ----------------------- |
| Peak checkout requests                 | 1,200   | requests/minute  | estimated  | Baseline load envelope v0 — 2026-09-06 |
| Peak reserve attempts                  | 600     | commands/minute  | estimated  | Baseline load envelope v0 — 2026-09-06 |
| Peak stock mutations mọi loại          | 4,800   | stock-item mutations/minute | estimated | Conservative envelope: 600 reserve requests × 8 items p95; xác nhận bằng load test. |
| Số item trung bình / p95 trong đơn     | 3 / 8   | items/order      | estimated  | Baseline load envelope v0 — 2026-09-06 |
| Số kho trung bình / tối đa cho một SKU | 3 / 8   | warehouses/SKU   | estimated  | Baseline load envelope v0 — 2026-09-06 |
| Tỷ lệ traffic của SKU nóng nhất        | 10%     | % stock-item mutations | estimated | Baseline load envelope v0 — 2026-09-06; tương đương khoảng 8 mutations/giây tại peak envelope. |
| SKU đang hoạt động                     | 100,000 | count            | estimated  | Baseline load envelope v0 — 2026-09-06 |
| Warehouse đang hoạt động               | 20      | count            | estimated  | Baseline load envelope v0 — 2026-09-06 |
| Reservation đồng thời                  | 9,000   | count            | estimated  | Upper bound: 600 reserve/minute × 15-minute timeout; xác nhận bằng load test. |
| Nhập/adjustment/return mỗi ngày        | 500     | commands/day     | estimated  | Baseline load envelope v0 — 2026-09-06; xác nhận với Warehouse operations. |
| Ledger/audit retention                 | 7       | years            | estimated  | Baseline audit-retention estimate; không phải kết luận pháp lý, cần Compliance/operations xác nhận. |




### SLO, freshness và recovery target


| Target                                | Giá trị | Owner                     | Ghi chú                                             |
| ------------------------------------- | ------- | ------------------------- | --------------------------------------------------- |
| Reserve p95 / p99                     | ≤ 200 ms / ≤ 500 ms | Checkout + Inventory | **Design estimate** cho thời gian xử lý trong Inventory, từ lúc command tới service đến lúc trả kết quả; không tính latency của Order/Temporal. Phải xác nhận bằng load test. |
| Allocation/preview p95 / p99          | ≤ 300 ms / ≤ 750 ms | Checkout + Inventory | **Design estimate** cho Inventory sau khi nhận request; phải xác nhận sau khi Phase 4 chốt allocation policy và load test. |
| Tỉ lệ lỗi hạ tầng của reserve         | ≤ 0.1% / 30 ngày | Platform + Inventory | **Design estimate**; không tính `out of stock` là lỗi hạ tầng. |
| Search stock freshness tối đa         | ≤ 60 giây | Search + Product | **Design estimate** tính từ durable inventory commit tới khi projection Search áp dụng state. |
| RPO                                   | 0 committed mutation | Platform + business owner | **Design estimate**: một command đã xác nhận thành công không được mất khi xảy ra failure thông thường; cần hạ tầng và DR design chứng minh. |
| RTO                                   | ≤ 60 phút | Platform + business owner | **Design estimate** cho khả năng phục vụ mutation an toàn trở lại sau outage. |
| Tối đa reservation orphan trước alert | > 60 phút | Order + Inventory | Đã chốt; alert/manual escalation, không phải tự động release. |




## 6. Degraded-mode policy cần chốt

Mỗi dòng cần có một lựa chọn rõ, không để “tùy tình huống” mà không có điều kiện kích hoạt.


| Sự cố                                  | Reserve / commit sale | Allocation preview | Admin stock command | Search projection | Quyết định cần chốt                                       |
| -------------------------------------- | --------------------- | ------------------ | ------------------- | ----------------- | --------------------------------------------------------- |
| Primary inventory store unavailable    | **Fail closed**       | **`503 INVENTORY_UNAVAILABLE`** | **Fail closed** | TBD | Không phát allocation plan từ state không authoritative; không tạo pending order; retry/lookup theo command ID sau recovery. |
| Event publication/backbone unavailable | Continue nếu outbox commit atomic | TBD | Continue nếu outbox commit atomic | Delayed / replay từ outbox | Transactional outbox là điều kiện bắt buộc để tiếp tục mutation. |
| Search unavailable                     | Continue              | Continue           | Continue            | N/A               | Checkout độc lập với Search; freshness được theo dõi riêng. |
| Order/Payment callback unavailable     | Giữ reservation; Order retry/release theo workflow | TBD | N/A | N/A | Không auto-release. Alert/manual escalation khi reservation orphan quá ngưỡng. |
| Cache/read model unavailable           | Continue nếu authoritative source còn sẵn sàng | Fallback authoritative source | Continue nếu authoritative source còn sẵn sàng | Projection có thể stale/rebuild | Cache không quyết định correctness; source không sẵn sàng thì áp dụng policy primary-store outage. |
| Regional/warehouse-local outage        | TBD                   | Loại warehouse outage/inactive; chọn kho khả dụng tiếp theo nếu đủ hàng | TBD | TBD | Chỉ áp dụng ở allocation ban đầu; không auto-reroute sau reserve failure. |




## 7. Câu hỏi phải được trả lời trong Phase 0



### 7.1 Business và customer experience

1. Hãy xếp hạng bảy ưu tiên ở §4.
2. **Đã chốt:** release đầu fulfil trọn vẹn từng line item từ một kho duy nhất; không split quantity
   của một line item qua nhiều kho. Allocation ban đầu bắt buộc loại warehouse `inactive`/`outage` và
   có thể chọn kho khả dụng tiếp theo theo policy; customer không được tự động nhận phương án đổi kho
   sau khi reserve thất bại.
3. **Đã chốt:** khi reserve thất bại vì hết hàng, fail-fast từ chối đơn ở release đầu. Release sau có
   thể đề nghị đổi kho sau khi có customer confirmation; không auto-reroute âm thầm.
4. **Đã chốt:** reserve áp dụng `REJECT_ALL`. Một line item hết hàng làm toàn bộ order bị từ chối;
   release đầu không có backorder và không có partial fulfillment. Item đã reserve trong cùng request
   phải được release trước khi kết quả từ chối cuối cùng được trả.
5. **Đã chốt:** Order Saga/Orchestrator sở hữu retry, payment timeout **15 phút** và lệnh release.
   Inventory chỉ ghi `expires_at` để quan sát; reservation orphan quá **60 phút** phát alert và cần can
   thiệp thủ công, tuyệt đối không tự xóa/release ngầm.



### 7.2 Stock và fulfillment

1. `on_hand` giảm tại payment, pick hay shipment? “Sold” và “physically removed” có cần là hai bucket khác nhau không?
2. **Đã chốt:** hàng hoàn bắt buộc qua `INSPECTION`/`QUARANTINE`; không được bán lại ngay khi receipt.
3. **Đã chốt:** release đầu hỗ trợ adjustment tăng/giảm; chỉ `ADMIN` được thực hiện và bắt buộc có
   reason code, actor audit. Không cần maker-checker trong release đầu.
4. Có cần transfer liên kho, split allocation hoặc fulfillment nhiều kho ngay release đầu không?
5. Warehouse inactive còn được giữ reservation/ship/return không?



### 7.3 NFR và operational decision

1. **Chưa có số đo thực tế.** Baseline load envelope v0 nằm ở §5 và chỉ dùng để lập load-test plan;
   nó không phải bằng chứng chọn hot-path store riêng.
2. **Đã có freshness estimate:** Search được phép cũ tối đa 60 giây. Nội dung state public
   (boolean, quantity hay theo warehouse/region) vẫn cần Search xác nhận ở Phase 1/7.
3. **Đã chốt:** được tiếp tục khi event backbone lỗi nếu mutation và transactional outbox record cùng
   commit durable; projection có thể chậm và replay từ outbox. Nếu outbox không thể commit atomically,
   command phải fail.
4. **Design estimate, chờ Platform/business owner xác nhận:** RPO = 0 cho committed mutation và
   RTO ≤ 60 phút. Các mục tiêu này phải được chứng minh bằng HA/backup/DR và load/failure test.
5. **Đã chốt một phần:** mọi mutation có actor/system actor, reason, command ID và timestamp; không
   cần maker-checker cho adjustment ở release đầu. Baseline retention là 7 năm và cần Compliance/
   operations xác nhận vì đây không phải kết luận pháp lý.



## 8. Assumption log

Không assumption nào trở thành thiết kế đã chốt nếu chưa có owner xác nhận.


| ID        | Assumption hiện tại                                                     | Impact nếu sai                                   | Owner                | Deadline | Status |
| --------- | ----------------------------------------------------------------------- | ------------------------------------------------ | -------------------- | -------- | ------ |
| `ASM-001` | Không oversell là invariant cứng.                                       | Thay đổi toàn bộ concurrency và checkout policy. | Checkout + Order     | —        | **Confirmed** |
| `ASM-002` | Inventory theo dõi stock theo warehouse.                                | Thay đổi aggregate, routing và fulfillment.      | Warehouse operations | —        | **Confirmed** |
| `ASM-003` | Search tiêu thụ public stock state eventual-consistent, freshness tối đa 60 giây, có ordering/dedupe/bootstrap. | Thay đổi contract/read model và SLO. | Search | — | **Confirmed** |
| `ASM-004` | Order Saga/Orchestrator là owner của order lifecycle và payment timeout. | Thay đổi reservation authority.                 | Order + Payment      | —        | **Confirmed** |
| `ASM-005` | Không có load-test evidence buộc Redis trở thành write source.          | Ảnh hưởng lựa chọn kiến trúc Phase 6.            | Inventory + Platform | Phase 6 architecture gate | Deferred |
| `ASM-006` | Stock adjustment tăng/giảm là capability bắt buộc, không dùng sửa data trực tiếp. | Thay đổi command/ledger/audit model. | Warehouse operations | — | **Confirmed** |




## 9. Definition of done cho Phase 0

Phase 0 hoàn tất khi tất cả điều kiện sau được đáp ứng:

- [x] Priorities ở §4 đã được xếp hạng và owner phê duyệt.
- [x] Candidate hard invariants được chấp nhận, sửa hoặc loại bỏ có ghi lý do.
- [x] Load profile, SLO, freshness, RPO/RTO được điền; mỗi giá trị có confidence và source.
- [x] Degraded-mode policy đã chốt cho primary store, event backbone, Search, cache và Order/Payment callback. Chi tiết lifecycle khi một warehouse outage được chuyển sang Phase 3–4.
- [x] Scope release đầu và non-goals được business/operations xác nhận.
- [x] Assumption log không còn mục blocking; `ASM-005` được defer có chủ đích tới Phase 6 architecture gate.
- [x] Không có câu trả lời Phase 0 nào mặc định một chi tiết implementation.
- [x] Design owner đã review và chấp nhận charter Phase 0. Formal cross-service review được theo dõi tại [context map Phase 1](./context-map.md#9-definition-of-done-cho-phase-1).

**Phase 0 completion:** hoàn tất về mặt design charter vào ngày 2026-09-06. Các giá trị
ở §5 là baseline estimate, phải được kiểm chứng bằng load/failure test trước khi chốt
kiến trúc ở Phase 6.

Sau khi hoàn tất, Phase 1 bắt đầu bằng context map và ownership matrix dựa trên charter này.

## 10. Related documents

- [Kế hoạch redesign](./redesign-plan.md)
- [Context map và ownership matrix](./context-map.md)
- [Thiết kế hiện hữu — overview](../README.md)
- [Thiết kế hiện hữu — domain](../domain.md)
- [Thiết kế hiện hữu — workflows](../workflows.md)
- [Thiết kế hiện hữu — decisions](../decisions/README.md)
