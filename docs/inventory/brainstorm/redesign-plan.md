---
doc_id: inventory-redesign-plan
service: inventory
scope: target
status: complete
owners:
  - Inventory Service team
evidence:
  - docs/inventory
verified_against_commit: null
---

# Inventory Service — Kế hoạch brainstorm và thiết kế lại

> Tài liệu này là trình tự để thiết kế lại Inventory Service từ đầu. Nó chủ động
> **không** lấy implementation hiện tại làm ràng buộc. Bộ tài liệu Inventory hiện
> hữu là evidence về nghiệp vụ, tích hợp và các trade-off trước đây; không phải thiết
> kế đích mặc định.

## 1. Mục tiêu và nguyên tắc làm việc

Mục tiêu là tạo một thiết kế có thể giải thích được câu trả lời cho các câu hỏi sau:

- Hệ thống còn có thể bán bao nhiêu đơn vị của một SKU, tại kho nào, và vì sao?
- Khi nhiều đơn cùng tranh hàng, điều gì bảo đảm không oversell?
- Khi thanh toán, xuất kho, hủy đơn hoặc hoàn hàng xảy ra, stock chuyển trạng thái ra sao?
- Service khác nhận thông tin nào, qua contract nào, và có thể phục hồi state sau sự cố thế nào?
- Khi dependency hoặc dữ liệu gặp sự cố, nguồn nào đáng tin và quy trình phục hồi là gì?

Nguyên tắc:

- Bắt đầu bằng luật nghiệp vụ và ownership, không bắt đầu từ database, Redis, Kafka hay endpoint.
- Mỗi quyết định lớn phải nêu alternatives, trade-off, owner và tiêu chí kiểm chứng trong ADR.
- Tách rõ sự thật nghiệp vụ khỏi chi tiết implementation. Ví dụ: “command idempotent” là yêu cầu;
  bảng lưu command result là một cách hiện thực sau này.
- Chỉ chấp nhận eventual consistency ở nơi business chấp nhận được; không dùng nó để che đi
  transaction boundary chưa rõ.
- Mọi mutation bên ngoài phải truy vết được bằng command/correlation ID và phải có chính sách retry.
- Một phần chỉ được đóng khi artifact của phần đó đã được review bởi các owner liên quan.

## 2. Cách dùng tài liệu hiện tại

Các tài liệu trong thư mục này được phân loại khi brainstorm như sau:

| Nhóm | Cách xử lý |
|---|---|
| Sự thật cần xác minh | Luồng Order/Payment, vai trò admin kho, nhu cầu Search, mô hình kho nhiều địa điểm, yêu cầu audit |
| Quyết định cần mở lại | Redis là commit source, Redis Stream relay, CDC cleanup, thuật toán routing, thời điểm deduct, refund registry, ship signal |
| Chi tiết cần loại khỏi thiết kế đích ban đầu | Lua script, Redis key format/TTL, consumer group, port, class/controller, migration và DTO hiện có |

Các vấn đề của thiết kế cũ phải được dùng làm input cho thiết kế mới, không được sao chép vô
điều kiện: hai nguồn commit khác nhau, phân kỳ Redis/PostgreSQL, reserve nhiều item bằng
compensation, restock/return không idempotent, thiếu stock adjustment, và recovery dựa nhiều vào
sửa dữ liệu thủ công.

### 2.1 Bắt buộc kiểm tra trước khi sang phase

Trước khi đề xuất hoặc chốt bất kỳ thiết kế nào ở phase mới, phải đọc toàn bộ quyết định đã
`Accepted` trong artifact của các phase trước và lập một decision trace ngắn:

1. Decision ID/semantic nào là input bắt buộc của đề xuất mới.
2. State, owner hoặc invariant nào bị ảnh hưởng.
3. Đề xuất mới có vô tình giải phóng lock, đổi owner hoặc tạo transition trái với decision
   trước không.
4. Nếu cần thêm transition liên service, phải chỉ rõ service owner phát command, precondition,
   transition terminal và điều kiện mở lock/cho phép retry.

Không coi một state nội bộ đã hoàn tất (ví dụ inspection completion) là business process đã
kết thúc nếu process owner khác vẫn có state đang xử lý. Khi phát hiện ambiguity, dừng ở decision
đó để chốt trước thay vì suy diễn sang phase tiếp theo.

## 3. Trình tự brainstorm và thiết kế

Mỗi phần dưới đây là một phiên hoặc một nhóm phiên làm việc. Không chuyển sang phần sau nếu các
câu hỏi chốt của phần hiện tại chưa có câu trả lời được ghi lại.

### Phase 0 — Design charter và các ràng buộc đo được

**Mục đích:** thống nhất service cần tối ưu cho điều gì trước khi chọn kiến trúc.

**Cần brainstorm:**

- Business objective: không oversell tuyệt đối, trải nghiệm checkout, độ chính xác báo cáo, audit,
  hay thông lượng cao — thứ tự ưu tiên là gì?
- Peak orders/minute, số SKU/kho, tỷ lệ SKU nóng, kích thước giỏ hàng, số kho cho một SKU.
- SLO cho reserve, mức downtime có thể chấp nhận, RPO/RTO và thời gian tồn kho Search được phép cũ.
- Có chấp nhận đơn bị từ chối dù tổng hàng toàn hệ thống đủ nhưng không kho đơn lẻ nào đủ không?
- Yêu cầu pháp lý/audit: ai điều chỉnh tồn kho, lý do, bằng chứng, retention và khả năng sửa ngược.

**Artifact phải chốt:** design charter một trang gồm business outcome, NFR có số đo, giả định,
out-of-scope và các câu hỏi cần owner khác xác nhận.

**Gate:** không chọn Redis, database lock, event broker hay topology khi chưa có NFR.

### Phase 1 — Bounded context và ownership liên service

**Mục đích:** xác định đúng trách nhiệm của Inventory, tránh biến nó thành bản sao của Order,
Product, Payment hoặc Search.

**Cần brainstorm:**

- Product là owner duy nhất của SKU/product lifecycle nào; Inventory giữ reference nào và xử lý
  SKU ngừng bán ra sao?
- Order sở hữu trạng thái đơn hàng; Inventory sở hữu allocation/reservation tới mức nào?
- Payment xác nhận điều gì để Inventory commit sale; việc refund phụ thuộc receipt ở kho ra sao?
- Ai sở hữu fulfillment và shipment sau khi Shipping Service không còn tồn tại?
- **Đã chốt:** Search nhận event/export boolean `is_in_stock`; Product Detail BFF gọi
  Inventory batch-read và chỉ nhận `IN_STOCK`/`OUT_OF_STOCK`/`UNKNOWN`, không nhận tổng
  sellable hay tồn kho theo vùng. PDP giữ `UNKNOWN` khi Inventory không có state tin cậy.
  Xem ADR-016 và ADR-017.
- Kho, warehouse staff và stock adjustment thuộc Inventory hay một bounded context vận hành kho riêng?

**Artifact phải chốt:** context map, ownership matrix, inbound/outbound interaction inventory và
trust boundary. Mỗi dữ liệu có đúng một system of record.

**Gate:** không tạo bảng denormalized hay public event trước khi owner của dữ liệu đã rõ.

### Phase 2 — Ubiquitous language, aggregate và invariants

**Mục đích:** định nghĩa đúng “tồn kho” trước khi thiết kế state transition.

**Cần brainstorm:**

- Phân biệt rõ `on_hand` (vật lý), `reserved`, `available-to-sell`, `allocated`, `picked`,
  `shipped`, `quarantined/damaged` nếu nghiệp vụ cần. Không gọi một số bằng cả “physical” và
  “đã bán” ở các thời điểm khác nhau.
- Aggregate nhất quán là `(sku, warehouse)`, `(sku, warehouse, lot)` hay một đơn vị khác?
- Một order line có thể có nhiều allocation không? Một SKU có thể tách qua nhiều kho không?
- Ledger ghi những movement nào; có cho phép adjustment âm/dương, reversal và stocktake không?
- Bất biến số lượng, bất biến reservation, invariant idempotency và invariant audit nào là tuyệt đối?

**Artifact phải chốt:** glossary, conceptual domain model, aggregate boundary, invariant catalogue
với ID ổn định và bảng thuật ngữ bị cấm dùng lẫn nhau.

**Gate:** mọi người phải đồng ý thời điểm nào thay đổi `on_hand` và thời điểm nào chỉ đổi
`available-to-sell`.

### Phase 3 — Vòng đời đơn hàng, reservation và fulfillment

**Mục đích:** xác định state machine liên service trước khi bàn về API hay saga.

**Cần brainstorm theo thứ tự:**

1. Preview/allocation: đây chỉ là gợi ý hay đã là lời hứa? Hết hạn có nghĩa gì?
2. Reserve: atomic scope là toàn giỏ, từng order line, hay từng allocation? Khi một phần thất bại
   thì caller nhận state nào?
3. Payment success/failure/timeout: ai ra quyết định release; có safety net nào cho reservation mồ côi?
4. Deduct/commit sale: thực hiện khi payment, khi pick hay khi ship? Lý do chọn phải nhất quán với
   định nghĩa stock ở Phase 2.
5. Fulfillment: pickup, shipment, partial shipment và shipment nhiều kho được biểu diễn ở đâu?
6. Cancellation sau payment, return và refund: được restock vào sellable stock ngay hay qua
   inspection/quarantine?

**Artifact phải chốt:** sequence diagram cho happy path và failure path, state machine của
reservation/allocation, responsibility matrix theo transition, timeout/retry/compensation policy.

**Gate:** không để hai service cùng tự timer hoặc cùng quyết định một transition.

### Phase 4 — Warehouse allocation và chính sách phục vụ

**Mục đích:** biến việc “chọn kho” thành một policy nghiệp vụ minh bạch thay vì chi tiết kỹ thuật.

**Cần brainstorm:**

- Có ưu tiên locality, cost, SLA, tồn kho cũ, cân bằng tải, warehouse capability hay split shipment?
- Có cho phép tách một SKU qua nhiều kho; nếu không, lý do và business impact là gì?
- Khi allocation dựa trên snapshot cũ, reserve phải phản hồi thế nào và Checkout xử lý retry/preview lại ra sao?
- Warehouse inactive, warehouse capacity và inventory transfer ảnh hưởng allocation thế nào?
- Cần giải thích được vì sao một kho được chọn cho support/admin không?

**Artifact phải chốt:** allocation policy có thứ tự ưu tiên, ví dụ minh họa, deterministic rule
nếu cần, và catalogue các outcome: fulfilled, partially fulfilled, backordered hoặc rejected.

**Gate:** không thiết kế cache route plan trước khi hiểu policy và thời điểm allocation trở thành commitment.

### Phase 5 — Nghiệp vụ kho và lifecycle của hàng vật lý

**Mục đích:** phủ đủ các nguồn làm thay đổi stock, không chỉ happy path checkout.

**Cần brainstorm:**

- Nhập hàng ban đầu/supplier receipt, inventory adjustment sau kiểm kê, hàng hỏng, mất hàng,
  transfer giữa kho, deactivate warehouse.
- Return receipt: kiểm đếm theo item/quantity, chất lượng hàng, partial return, wrong item và
  điều kiện refund.
- Quyền của warehouse admin, maker-checker cho adjustment lớn, chứng từ/lý do bắt buộc.
- Mọi command mutation phải idempotent thế nào; timeout client kiểm tra kết quả cũ ra sao?
- Movement nào thay đổi on-hand, movement nào chỉ thay đổi sellable/reserved, movement nào chỉ
  là signal workflow?

**Artifact phải chốt:** command catalogue, stock-movement taxonomy, authorization matrix,
idempotency policy và audit requirements.

**Gate:** phải có đường hợp lệ để correction/reversal; không dùng sửa database như quy trình nghiệp vụ.

### Phase 6 — Consistency, persistence và concurrency

**Mục đích:** chọn kiến trúc sau khi semantic đã cố định.

**Giả thuyết khởi đầu nên kiểm chứng:** dùng một durable source of truth duy nhất cho write path —
ví dụ relational stock state + reservation + immutable movement ledger + transactional outbox trong
một transaction. Redis, nếu cần, bắt đầu là cache/read optimization; chỉ nâng thành commit source
khi load test chứng minh một cách định lượng rằng mô hình một nguồn không đạt NFR.

**Cần brainstorm:**

- State + ledger hay event sourcing; projection nào là authoritative và projection nào có thể rebuild?
- Scope transaction, locking/versioning strategy, thứ tự lock và chiến lược tránh oversell.
- Atomicity cho multi-item/multi-warehouse: transaction thật, reservation protocol, hay business
  chấp nhận partial fulfillment?
- Command inbox/idempotency record, dedupe event, outbox publication và replay semantics.
- Redis/cache được phép stale ở đâu, có invalidation/rebuild nào, và nó không bao giờ được dùng
  để tạo một nguồn chân lý thứ hai ra sao?

**Artifact phải chốt:** logical data model, transaction boundary table, concurrency model,
idempotency model, event delivery guarantee và recovery-from-backup design.

**Gate:** mỗi mutation phải chỉ ra duy nhất nơi commit, invariant được enforce ở đâu và cách replay an toàn.

### Phase 7 — Contract-first integration và read model

**Mục đích:** công bố semantics ổn định cho caller/consumer sau khi domain đã chốt.

**Cần brainstorm:**

- Command/query nào là synchronous API, command nào thuộc workflow/Saga, event nào là public fact.
- Error taxonomy, retryability, idempotency key/command ID và correlation/causation ID.
- **Đã chốt:** Search nhận snapshot/event có revision và dùng export/replay không phụ
  thuộc Kafka retention. Product Detail BFF không nhận snapshot/delta, không bootstrap
  và không recovery từ broker; nó gọi Availability Read API. Xem ADR-016 và ADR-017.
- Schema versioning, compatibility window, consumer registry, contract test và quy trình deprecation.
- Có cần internal read API hay Search chỉ dùng event/materialized export?

**Artifact phải chốt:** OpenAPI/AsyncAPI ở trạng thái target, event catalogue, sequence diagrams
cho mỗi integration và compatibility/deprecation policy.

**Gate:** không phát event “theo implementation”; mọi payload public phải có owner, version,
ordering/dedup rule và consumer đã biết.

### Phase 8 — Resilience, security và vận hành

**Mục đích:** bảo đảm thiết kế phục hồi được thay vì phụ thuộc vào thao tác tay mơ hồ.

**Cần brainstorm:**

- Dependency outage nào fail closed, fail open hoặc degraded; ai có quyền stop sales?
- Reconciliation: dữ liệu nào đối chiếu với dữ liệu nào, có tự động được không, khi nào cần
  human approval, và hành động correction tạo audit trail gì?
- Backup/restore, RPO/RTO, rebuild projection/cache và event replay không làm version lùi.
- DLQ/replay/retention, backpressure, data retention và cách chứng minh event chưa mất trước cleanup.
- SLI/SLO, metrics cardinality, alert ownership, access control/audit cho adjustment và sensitive data.

**Artifact phải chốt:** failure-mode matrix, observability specification, SLO/alert catalog,
runbook có thể diễn tập và disaster-recovery procedure.

**Gate:** không có runbook nào yêu cầu “sửa tay database/Redis” như cách phục hồi mặc định.

**Kết quả đã chốt:** [Operations & Resilience Contract](operations-resilience.md) quy định failure
mode, degraded behavior, reconciliation không auto-correct, SLO/alert ownership, outbox/DLQ/replay,
least-privilege access, retention, DR gates và diễn tập định kỳ.

**Phase 8 complete — 2026-09-07.** Compliance/Legal validation cho retention/evidence và bằng
chứng diễn tập để xác nhận RPO=0/RTO<=60 phút là production-release gates; chúng không mở lại
domain semantics đã chốt.

### Phase 9 — Chốt quyết định và viết bộ tài liệu target

**Mục đích:** biến kết quả brainstorm thành nguồn sự thật duy nhất trước implementation.

**Thứ tự viết lại tài liệu:**

1. `README.md`: mục tiêu, boundary, ownership, capabilities, dependencies, invariant tóm tắt.
2. `domain.md`: glossary, aggregate, business rules, invariants, state machines, domain errors.
3. `workflows.md`: các luồng đã chốt, timeout/retry/failure/recovery.
4. `interfaces.md`: contract index và semantic của HTTP/workflow/event.
5. `data.md`: logical data model, transaction boundary, retention, projection/cache.
6. `architecture.md`: component, consistency/concurrency, deployment topology.
7. `operations.md`: SLO, failure mode, runbook, worker/job nếu kiến trúc thực sự cần.
8. `decisions/ADR-*.md`: mọi trade-off dài hạn; ADR cũ được supersede thay vì chỉnh lịch sử.
9. `development.md`: chỉ viết khi đã có hướng implementation và môi trường phát triển thực tế.

**Definition of ready for implementation:** business owner và các service liên quan review state
machine/contract; tất cả invariant có nơi enforcement được thiết kế; mọi mutation có idempotency và
audit policy; failure/recovery path đã được mô tả; load-test plan đủ để kiểm chứng các NFR ở Phase 0.

**Kết quả đã chốt:** bộ tài liệu target được viết tại thư mục cha gồm `README.md`, `domain.md`,
`workflows.md`, `interfaces.md`, `data.md`, `architecture.md`, `operations.md`, `development.md` và
`decisions/ADR-011` đến `ADR-015`. Các ADR 001–010 được giữ nguyên lịch sử và được supersede qua
decision index, không còn là implementation authority.

**Phase 9 complete — 2026-09-07.** Bộ tài liệu target là nguồn sự thật duy nhất trước implementation;
thư mục `brainstorm/` là evidence của quá trình ra quyết định.

## 4. Decision register ban đầu

| Chủ đề từ thiết kế cũ | Trạng thái khi thiết kế lại | Câu hỏi cần trả lời |
|---|---|---|
| Tồn kho per warehouse | Giữ để đánh giá lại | Có cần lot, zone, condition hoặc split allocation không? |
| Reservation và Order-owned timer | Giữ vấn đề, mở lại cơ chế | Ai là owner duy nhất của lifecycle; safety net cho orphan reservation là gì? |
| Redis là hot-path commit source | Mở lại hoàn toàn | NFR/load test nào bắt buộc phải dùng hai source of truth? |
| Redis Stream relay | Mở lại theo quyết định source of truth | Cần delivery guarantee nào và operational cost nào chấp nhận được? |
| Transactional outbox | Candidate mạnh | Outbox/replay/cleanup có thể chứng minh không mất event thế nào? |
| Snapshot có revision cho Search | Candidate mạnh | Search cần state gì, revision scope nào và bootstrap/replay ra sao? |
| Routing same-city, ID nhỏ nhất | Mở lại hoàn toàn | Policy phải tối ưu locality, cost, SLA, fairness hay split shipment? |
| Deduct khi payment thành công | Mở lại hoàn toàn | On-hand vật lý giảm lúc nào; payment/pick/ship ảnh hưởng các bucket nào? |
| Ship chỉ là signal | Mở lại theo domain language | Shipment là state thuộc Fulfillment/Order hay phải tác động Inventory bucket? |
| Refund expectation registry ở Inventory | Mở lại theo ownership | Ai giữ refund scope và ai có thể kết luận đã nhận đủ hàng? |
| Manual suspension/reconciliation | Không kế thừa nguyên trạng | Phát hiện drift, correction và audit cần tự động/human approval thế nào? |

## 5. Phiên đầu tiên nên bắt đầu ở đâu

Bắt đầu bằng **Phase 0 và Phase 2**, với câu hỏi nền tảng:

> Một đơn vị hàng được coi là giảm khỏi kho ở thời điểm thanh toán, lúc pick, hay lúc thực sự xuất kho?

Quyết định này làm nền cho các bucket tồn kho, reservation, shipment, cancellation, return/refund,
ledger và event. Chỉ sau khi chốt ngôn ngữ này mới chuyển sang chọn database, cache, queue hoặc cách
triển khai concurrency.
