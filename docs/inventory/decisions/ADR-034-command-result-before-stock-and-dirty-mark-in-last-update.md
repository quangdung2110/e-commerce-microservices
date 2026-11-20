---
doc_id: inventory-adr-034
service: inventory
status: accepted
date: 2026-10-20
decision_owners:
  - Inventory Service team
supersedes: null
refines: inventory-adr-033
evidence:
  - docs/performance/inventory-lock-late-benchmark-2026-10-19.md
  - java/services/inventory-service/src/main/java/com/ecommerce/inventory/command/Accepted.java
  - java/services/inventory-service/src/main/java/com/ecommerce/inventory/command/CommandExecutor.java
  - java/services/inventory-service/src/main/java/com/ecommerce/inventory/store/StockStore.java
  - java/services/inventory-service/src/test/java/com/ecommerce/inventory/integration/LockLateConcurrencyIT.java
---

# ADR-034 — Ghi kết quả command trước câu stock, gộp dirty mark vào câu stock cuối

## Status

`Accepted`. Làm tiếp [ADR-033](ADR-033-stock-position-lock-taken-last.md), không thay thế nó. ADR-033 vẫn quyết định
chuyện khoá `stock_position` sau cùng bằng UPDATE có điều kiện; ADR này rút ngắn tiếp vùng khoá. Cập nhật
[data.md §4](../data.md#4-transaction-boundary). Không đổi contract, mã lỗi, nội dung kết quả lưu bền, isolation,
`lock_timeout` hay số lần retry.

## Context

[Benchmark ADR-033](../../performance/inventory-lock-late-benchmark-2026-10-19.md) cho thấy khi bão hoà, mỗi lệnh vẫn
giữ khoá hàng khoảng 2,45 ms. Vùng khoá còn ba câu lệnh và COMMIT:
1. UPDATE stock;
2. upsert dirty mark (Reserve, Release, recovery, pre-dispatch release);
3. UPDATE `command_execution` ghi kết quả, chạy sau handler;
4. COMMIT.

Riêng COMMIT tốn khoảng 1,3 ms. ADR-033 đã ghi hai cách rút thêm và hoãn lại; ADR này thực hiện cả hai.

## Decision

1. **Bước cuối của handler.** `Accepted` có thêm `lastStep`, một bước chạy sau cùng do handler cung cấp. Executor:
   - ghi kết quả (`ACCEPTED`, outcome, body) **trước**;
   - rồi mới chạy `lastStep`, chính là các câu stock lấy khoá hàng nóng;
   - rồi COMMIT.

   Các trường hợp của `lastStep`:
   - Trả `null`: giữ kết quả đã ghi.
   - Trả một outcome thay thế (sau khi tự quay về savepoint, tức là huỷ luôn bản ghi kết quả): executor ghi outcome đó.
     Đây là trường hợp Reserve thiếu hàng, outcome thay thế là `REJECT_ALL`.
   - Ném `DomainRejection` (recovery không còn đủ hàng): executor quay về savepoint của nó và ghi rejection như cũ.
   - Ném lỗi khác: huỷ transaction, như cũ.

   Nội dung kết quả không phụ thuộc gì vào câu stock (`recorded_at`, revision, các dòng đều đã biết), nên ghi trước
   không làm đổi dữ liệu lưu bền.
2. **Dirty mark nằm trong câu stock cuối cùng.** `StockStore.applyGuardedMarkingDirty` dùng một CTE:
   `UPDATE stock_position … RETURNING`, rồi `INSERT … ON CONFLICT` vào `sku_availability_dirty`, coalescing giống hệt
   `AvailabilityStore.markDirty`. Phần INSERT chỉ chạy khi UPDATE đã cập nhật được hàng. Chỉ position **cuối cùng** theo
   thứ tự `(warehouse_id, sku_id)` dùng câu này, và nó đánh dấu mọi SKU của lệnh theo đúng thứ tự Java (`unnest … WITH
   ORDINALITY`, không dùng `ORDER BY` theo collation). Vì vậy dirty row luôn bị khoá sau **mọi** position row, đúng thứ
   tự khoá trong data.md.
   - **Không gộp vào từng câu stock.** Đơn có cùng SKU ở hai kho sẽ khoá xen kẽ "stock A, dirty, stock B", trong khi một
     đơn một kho khoá "stock B, dirty"; hai đơn như vậy có thể deadlock. Test đột biến đã xác nhận điều này.
3. **Kết quả.** Vùng khoá của Reserve, Release và Commit một dòng chỉ còn **một câu lệnh và COMMIT**. Reserve và Release
   dùng câu stock có dirty mark; Commit dùng câu stock thường vì không đổi `available_to_sell`.

## Alternatives considered

- **Ghi kết quả bằng một câu riêng ngay trước stock, do handler tự làm.** Không chọn. Executor đang là nơi duy nhất ghi
  kết quả, gồm cả đường rejection và replay; đưa việc này vào handler sẽ nhân đôi logic đó.
- **Gộp dirty mark vào từng câu stock.** Không chọn, vì gây deadlock như mục 2.
- **Gộp cả kết quả command vào CTE của câu stock.** Không chọn. Câu stock khi đó phải biết outcome thay thế cho trường hợp
  thất bại; vừa phức tạp, vừa không bỏ thêm được round-trip nào so với cách đã chọn.

## Consequences

- Kết quả command được ghi khi chưa có khoá hàng nóng. Khi thiếu hàng, bản ghi `RESERVED` bị huỷ cùng savepoint và được
  thay bằng `REJECT_ALL`. Không có trạng thái trung gian nào commit được.
- Kết quả đo nằm ở mục "Verification".

## Tests

`LockLateConcurrencyIT` (retry nội bộ tắt) thêm hai test:
- `sameSkuInTwoWarehousesDoesNotDeadlockAndMarksDirtyOncePerCommand`: đơn hai kho chạy cùng đơn một kho. Mỗi lệnh
  đánh dấu dirty đúng một lần; Commit không đánh dấu, Release có.
- `shortfallFoundInTheLastStepStoresRejectAll`: kết quả lưu bền và command query đều là `REJECT_ALL`.

Kết quả: 62/62 test xanh. Đột biến "dirty mark ở mọi câu stock" làm đơn hai kho nhận `503` (deadlock hoặc lock
timeout). Đột biến "executor bỏ qua outcome thay thế" làm 6 test fail.

## Migration impact

Không có thay đổi schema, contract hay cấu hình. Khi deploy chỉ thay jar.

## Verification

[Benchmark](../../performance/inventory-lock-late-benchmark-2026-10-19.md#7-bước-2-adr-034--20102026), kịch bản C, v1
(ADR-033) và v2 (ADR-034) chạy trong cùng phiên:

| | v1 (ADR-033) | v2 (ADR-034) |
|---|---:|---:|
| Trần flash sale | ≈ 208 đơn/s | **≈ 241 đơn/s** (+16%) |
| Flash 200 đơn/s: Reserve p99 | 209 ms | **38 ms** |
| Chỉ Reserve đạt SLO tới | 300/s (đo ngày 19/10) | **400/s** |
| Trần chỉ Reserve | ≈ 317/s (đo ngày 19/10) | **≈ 435/s** |

Mức SLO của flash sale là 200 đơn/s ở cả hai biến thể, vì các bậc đo cách nhau 50 đơn/s. Số chỉ-Reserve so hai phiên khác
nhau, nên gồm cả biến động giữa phiên. Ở trần, PostgreSQL dùng khoảng 230% trên trần 300% và Inventory 100% trên trần
200%: từ đây CPU, chứ không còn là hàng khoá, quyết định giới hạn.
