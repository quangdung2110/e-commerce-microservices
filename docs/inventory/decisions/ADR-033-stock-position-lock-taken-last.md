---
doc_id: inventory-adr-033
service: inventory
status: accepted
date: 2026-10-19
decision_owners:
  - Inventory Service team
supersedes: null
evidence:
  - docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md
  - docs/performance/inventory-flash-sale-baseline-2026-10-19.md
  - java/services/inventory-service/src/main/java/com/ecommerce/inventory/service/ReservationService.java
  - java/services/inventory-service/src/main/java/com/ecommerce/inventory/store/StockStore.java
  - java/services/inventory-service/src/test/java/com/ecommerce/inventory/integration/LockLateConcurrencyIT.java
  - docs/performance/inventory-lock-late-benchmark-2026-10-19.md
---

# ADR-033 — Khoá `stock_position` sau cùng trong các lệnh reservation

## Status

`Accepted`. Thay đổi thứ tự câu lệnh bên trong transaction của Reserve, CommitPayment, ReleaseReservation,
RecoverTimedOutPayment và ReleaseCommittedOrderBeforeDispatch. Không đổi contract, mã lỗi, kết quả lưu bền, isolation,
`lock_timeout` hay số lần retry nội bộ. Cập nhật [data.md §4](../data.md#4-transaction-boundary),
[architecture.md §4](../architecture.md#4-concurrency-model) và [workflows.md](../workflows.md#allocation-and-reserve).

## Context

`StockPosition` là đơn vị tranh chấp tồn kho ([architecture.md §4](../architecture.md#4-concurrency-model)). Trước ADR
này, mọi lệnh reservation lấy `SELECT … FOR UPDATE` trên các hàng `stock_position` ngay đầu transaction, rồi vẫn giữ
khoá trong lúc ghi root, line, movement, audit, dirty mark, kết quả command và COMMIT.

Benchmark ([baseline Reserve](../../performance/inventory-reserve-hot-sku-baseline-2026-10-19.md),
[baseline flash sale](../../performance/inventory-flash-sale-baseline-2026-10-19.md)), đo trên một SKU nóng với số request
đồng thời bằng bulkhead 64 của Order:

- Mỗi lệnh giữ khoá hàng khoảng **3,0–3,5 ms**. Trong đó PostgreSQL chỉ thực thi khoảng 0,7 ms SQL; phần còn lại là
  round-trip JDBC, việc của Java giữa các câu, và COMMIT.
- Một SKU chịu được Reserve trong SLO tới **250/s**, trần khoảng 290/s.
- Flash sale (Reserve rồi Commit/Release) đạt SLO tới **100 đơn/s**, trần khoảng 150–167 đơn/s, vì mỗi đơn đi qua hàng
  hai lần.
- Riêng `UPDATE` + `COMMIT` trên một hàng tốn khoảng 1,3–1,4 ms (trần khoảng 700–750/s). Đó là phần không thể bỏ nếu
  vẫn giữ khoá qua COMMIT.

Tải dự kiến của Release 1 là 10 Reserve/s ([development.md](../development.md#preconditions-before-production)), nên
quyết định này nhằm chuẩn bị cho flash sale vượt mức đó. Nó không sửa một vi phạm SLO hiện hữu.

## Decision

1. **Ghi bucket bằng một câu UPDATE có điều kiện, chạy sau cùng.** `StockStore.applyGuarded` cộng delta tương đối vào
   từng bucket và chỉ cập nhật khi mọi bucket sau khi cộng vẫn ≥ 0. Câu UPDATE này chính là nơi lấy khoá hàng
   (`FOR NO KEY UPDATE`). Ở `READ COMMITTED`, PostgreSQL đánh giá lại điều kiện trên phiên bản mới nhất sau khi chờ
   khoá, nên kiểm tra tồn kho và trừ tồn kho là một bước nguyên tử. Ràng buộc `CHECK` của bảng vẫn là lớp bảo vệ thứ hai.
2. **Thứ tự trong transaction.**
   - **Trước:** khoá root/line hoặc advisory key, khoá chia sẻ warehouse, ghi root/line/movement/audit. `stock_movement`
     không có foreign key tới `stock_position`, nên ghi movement không chạm hàng nóng.
   - **Sau cùng:** các câu `applyGuarded`, theo thứ tự `(warehouse_id, sku_id)` của Java như trước; tiếp theo là dirty
     mark của SKU (nếu lệnh đổi `available_to_sell`); cuối cùng executor ghi kết quả command rồi COMMIT.
   - Thứ tự khoá giữa các bảng vẫn đúng [data.md](../data.md#required-lock-order): `StockPosition` là bước cuối, dirty
     mark đi sau stock trong mọi lệnh, và projector không bao giờ khoá stock.
3. **Reserve thiếu hàng (`REJECT_ALL`).** Reserve đặt savepoint trước khi ghi. Nếu một câu `applyGuarded` không cập
   nhật được hàng nào, Reserve quay về savepoint (huỷ root, line, movement, audit và các bucket đã trừ), rồi khoá các
   hàng theo cách cũ (`FOR UPDATE`) để tính đúng danh sách dòng thiếu như trước. Nếu dưới khoá không còn dòng nào thiếu
   (tồn kho vừa được giải phóng đồng thời), Reserve ghi lại một lần nữa trong khi vẫn giữ các khoá đó, và lần này chắc
   chắn thành công. Kiểm tra warehouse inactive/OUTAGE và hàng không tồn tại vẫn chạy trước mọi lần ghi, như cũ.
4. **Các lệnh còn lại.**
   - Commit, Release, ReleaseCommittedOrderBeforeDispatch: điều kiện không thoả nghĩa là bất biến bucket đã hỏng, nên
     ném `InvariantViolation`. Kết quả giống trước (transaction huỷ, không lưu kết quả).
   - RecoverTimedOutPayment: điều kiện không thoả là `409 RESERVATION_NOT_RECOVERABLE` lưu bền; executor huỷ mọi thứ đã
     ghi trong handler, đúng như lần kiểm tra trước khi ghi ở phiên bản cũ.
5. **Không đổi.**
   - Các lệnh ADMIN, nhập hàng, xuất kho và trả hàng vẫn dùng `lock` + `apply`. Các lệnh này hiếm và không nằm trên
     đường mua hàng.
   - Hai lệnh cùng một đơn vẫn tuần tự qua khoá root hoặc advisory key.
   - Nội dung movement, audit và kết quả command giữ nguyên.

## Alternatives considered

- **Ghi kết quả command trước câu stock**, để vùng khoá chỉ còn một câu và COMMIT. Không làm lúc này. Cách đó cần
  executor hỗ trợ "bước cuối có thể thất bại" và đổi kết quả đã ghi khi Reserve thiếu hàng, làm phức tạp đường replay.
  Mỗi lệnh chỉ tốn thêm một câu lệnh trong vùng khoá. Có thể làm sau nếu số đo cho thấy đáng.
- **Gộp mọi dòng vào một câu `UPDATE … FROM (VALUES …)`.** Không chọn. PostgreSQL không bảo đảm thứ tự khoá các hàng
  trong một câu như vậy, mà thứ tự khoá theo Java là điều kiện chống deadlock của data.md.
- **Tách tồn kho của SKU nóng thành nhiều hàng (sharding bucket).** Đây là cách duy nhất vượt được trần COMMIT của một
  hàng (khoảng 700/s), nhưng nó đổi mô hình dữ liệu, availability và đối soát. Cần ADR riêng nếu nhu cầu vượt mức mà
  quyết định này đạt được.
- **Hạ `lock_timeout` hoặc tăng pool.** Không chọn. Cách này không làm hàng nóng nhanh hơn, chỉ chuyển thời gian chờ
  thành lỗi hoặc thêm transaction đứng chờ (xem [ADR-032](ADR-032-reserve-saturation-alerts.md)).

## Consequences

- Vùng giữ khoá của một Reserve một dòng giảm từ khoảng 7 câu lệnh + COMMIT xuống còn 3 câu (UPDATE stock, dirty mark,
  kết quả command) + COMMIT. Commit giảm xuống 2 câu + COMMIT. Flash sale đạt SLO tới 150 đơn/s thay vì 100 (mục
  "Verification").
- Reserve thiếu hàng tốn thêm công: phải ghi rồi quay về savepoint, rồi khoá và đọc lại. Đây là đường hiếm và vẫn trả
  đúng `REJECT_ALL` như trước.
- Đơn nhiều dòng giữ khoá dòng đầu tiên trong lúc cập nhật các dòng sau. Đó vẫn là các câu UPDATE ngắn, thay vì cả
  transaction như trước.

## Tests

[`LockLateConcurrencyIT`](../../../java/services/inventory-service/src/test/java/com/ecommerce/inventory/integration/LockLateConcurrencyIT.java)
cùng các IT hiện có (`ConcurrencyIT`, `ReservationWorkflowIT`, `AuditCorrectionsIT`, `RefundReturnIT`,
`AdminOperationsIT`, `AvailabilityExportIT`) kiểm tra:

- không bán vượt khi nhiều Reserve, Commit và Release cùng tranh một SKU tồn kho ít;
- hai đơn nhiều dòng khoá cùng các SKU nhưng theo thứ tự dòng ngược nhau không gây deadlock;
- `REJECT_ALL` liệt kê đúng mọi dòng thiếu, và không để lại root, line, movement, audit hay bucket nào bị trừ;
- replay theo cùng idempotency key trả đúng kết quả đã lưu;
- tổng movement của mỗi SKU khớp với bucket.

## Migration impact

Không có thay đổi schema, contract hay cấu hình. Khi deploy chỉ thay jar.

## Verification

[Benchmark trước/sau](../../performance/inventory-lock-late-benchmark-2026-10-19.md), kịch bản C (bulkhead 64, timeout
8 s, 120 s mỗi bậc), baseline flash chạy lại trong cùng phiên:

| | Trước | Sau |
|---|---:|---:|
| Flash sale đạt SLO tới | 100 đơn/s | **150 đơn/s** |
| Trần flash sale | 147–165 đơn/s | **192–204 đơn/s** |
| Chỉ Reserve đạt SLO tới | 250/s | **300/s** |
| Trần chỉ Reserve | 285–290/s | **≈ 317/s** |

Mức tăng thấp hơn ước lượng ban đầu (2,2–2,5 lần), vì vùng khoá vẫn còn dirty mark, kết quả command và COMMIT (khoảng
2,45 ms mỗi lệnh khi bão hoà, so với sàn COMMIT khoảng 1,3 ms). Hai phương án ở "Alternatives considered" (ghi kết quả
command trước câu stock, gộp dirty mark vào câu stock) là các bước tiếp theo nếu cần thêm. Tính đúng: 60/60 test xanh,
đối soát DB khớp ở mọi bậc, hai đột biến có chủ đích đều bị test bắt.
