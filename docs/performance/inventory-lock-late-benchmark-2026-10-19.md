# Inventory: khoá `stock_position` sau cùng (ADR-033), trước và sau — 19/10/2026

## Kết luận chính

Thay đổi được đo: [Inventory ADR-033](../inventory/decisions/ADR-033-stock-position-lock-taken-last.md). Năm lệnh
reservation không còn khoá hàng tồn kho ở đầu transaction nữa. Chúng ghi root, line, movement và audit trước, rồi mới trừ
hoặc cộng bucket bằng một câu UPDATE có điều kiện ở cuối. Phép đo dùng **kịch bản C** của
[baseline flash sale](./inventory-flash-sale-baseline-2026-10-19.md): một SKU nóng, số request đồng thời giới hạn như một
replica Order (bulkhead 64, timeout 8 s), mỗi bậc 120 giây. Baseline flash được chạy lại ngay trong cùng phiên đo.

| Chỉ số (một SKU nóng) | Trước | Sau | Thay đổi |
|---|---:|---:|---:|
| **Flash sale: mức cao nhất đạt SLO** | 100 đơn/s | **150 đơn/s** | **+50%** |
| Flash sale: trần thông lượng | 147–165 đơn/s | **192–204 đơn/s** | +24% |
| Flash 125 đơn/s: Reserve p95 / p99 | 336 / 535 ms (không đạt) | **9,8 / 20,4 ms** | |
| Flash 150 đơn/s: thời gian chờ khoá trung bình mỗi lệnh | 66,9 ms | **1,1 ms** | −98% |
| **Chỉ Reserve: mức cao nhất đạt SLO** | 250/s | **300/s** | +20% |
| Chỉ Reserve: trần thông lượng | 285–290/s | **≈ 317/s** | +9% |

- **Dưới trần, latency gần như phẳng.** Từ 50 tới 150 đơn/s, Reserve p99 chỉ còn 12–20 ms; baseline ở cùng các mức này
  là 15–535 ms.
- **Ở trần, mỗi lệnh flash giữ khoá khoảng 2,45 ms, giảm từ khoảng 3,0 ms.** Mức tăng thấp hơn ước lượng 2,2–2,5 lần
  trong ADR-033. Lý do: vùng khoá vẫn còn 3 câu lệnh (UPDATE stock, dirty mark, kết quả command) cộng với COMMIT, mà
  riêng COMMIT đã tốn khoảng 1,3 ms (mục 4).
- **Tính đúng không đổi.** Đối soát phía DB khớp ở mọi bậc. 60/60 test xanh, gồm 4 test chạy đồng thời mới. Hai đột biến
  có chủ đích (bỏ quay về savepoint, bỏ điều kiện tồn kho) đều bị test bắt.

Tải dự kiến của Release 1 là 10 Reserve/s, nên thay đổi này nhằm chuẩn bị cho flash sale, không sửa một vi phạm SLO hiện
có. Các con số là **so sánh tương đối trên một máy**, không phải năng lực production (mục 6).

## 1. Thay đổi được đo

| | Trước (`baseline`) | Sau (`lock-late`) |
|---|---|---|
| Jar | `4697d0c9…` (cùng jar của các báo cáo baseline) | `4d16b8df…`, build từ cùng cây cộng ADR-033 |
| Lấy khoá `stock_position` | `SELECT … FOR UPDATE` ở đầu, giữ qua mọi câu sau | `UPDATE … SET bucket = bucket + Δ WHERE …bucket + Δ ≥ 0` ở cuối |
| Vùng khoá của Reserve một dòng | khoảng 7 câu + COMMIT | 3 câu (stock, dirty mark, kết quả command) + COMMIT |
| Vùng khoá của Commit | khoảng 6 câu + COMMIT | 2 câu (stock, kết quả command) + COMMIT |

Cả hai jar đều được build từ working tree có 4 file observability staged ngày 18/10 (OTel bridge, JSON log, histogram
HTTP). Phần khác nhau giữa hai jar chỉ là ADR-033.

## 2. Phương pháp

Harness, dữ liệu, giới hạn CPU và tiêu chí SLO giống [baseline flash sale](./inventory-flash-sale-baseline-2026-10-19.md#1-kịch-bản)
(kịch bản C). Thứ tự chạy trong phiên 22:59–23:58:
1. lock-late flash;
2. lock-late chỉ Reserve;
3. baseline flash chạy lại.

Mỗi lượt seed lại từ đầu. Máy được ghi tải mỗi 15 s ([host-load.txt](./inventory-lock-late-evidence-2026-10-19/host-load.txt)):
234 mẫu, không có tiến trình game, CPU trung bình 22,8%.

Bảng chỉ Reserve so với baseline C2 đo lúc 21:50 cùng ngày, sau khi máy đã rảnh. Lượt đó **không** được chạy lại trong
phiên này. Baseline flash chạy lại thì khớp với lần đo 21:50: đạt SLO tới 100 đơn/s, trần khoảng 145–167 đơn/s.

## 3. Kết quả

### Flash sale (Reserve + Commit/Release, 32 + 32 đồng thời)

| Đơn/s | Trước: hoàn tất/s | Trước: Reserve p95 / p99 ms | SLO | Sau: hoàn tất/s | Sau: Reserve p95 / p99 ms | SLO | Chờ khoá TB (ms) trước → sau |
|---:|---:|---:|:---:|---:|---:|:---:|---:|
| 50 | 50,0 | 10,9 / 14,5 | đạt | 50,0 | 9,4 / 12,3 | đạt | 1,4 → 0,8 |
| 75 | 75,0 | 17,0 / 317 | đạt | 75,0 | 9,0 / 12,1 | đạt | 6,8 → 0,7 |
| 100 | 100,0 | 14,6 / 65,9 | đạt | 100,0 | 9,0 / 12,6 | đạt | 3,4 → 0,7 |
| 125 | 122,6 | 336 / 535 | không | **125,0** | **9,8 / 20,4** | **đạt** | 36,1 → 1,1 |
| 150 | 146,6 | 364 / 523 | không | **150,0** | **9,4 / 14,4** | **đạt** | 66,9 → 1,1 |
| 200 | 148,1 | 410 / 582 | không | 192,1 | 239 / 347 | không | 67,1 → 30,9 |
| 300 | 164,6 | 402 / 577 | không | **204,3** | 284 / 389 | không | 66,6 → 47,0 |

Commit có latency tương tự Reserve ở mọi bậc (xem [summary.md](./inventory-lock-late-evidence-2026-10-19/summary.md)). Ở mọi
bậc của cả hai biến thể: 0 `INVENTORY_BUSY`, 0 `INVENTORY_UNAVAILABLE`, 0 timeout, đối soát DB khớp.

Ở 200 đơn/s lock-late không đạt SLO vì có 951 Reserve (khoảng 4%) bị bỏ do bulkhead đầy. p99 347 ms vẫn trong ngưỡng.

### Chỉ Reserve (64 đồng thời)

| Reserve/s | Trước: RESERVED/s · p95 / p99 ms | SLO | Sau: RESERVED/s · p95 / p99 ms | SLO |
|---:|---:|:---:|---:|:---:|
| 150 | 150,0 · 8,1 / 15,8 | đạt | 150,0 · 8,2 / 11,3 | đạt |
| 200 | 200,0 · 9,4 / 24,4 | đạt | 200,0 · 8,1 / 10,9 | đạt |
| 250 | 250,0 · 102 / 312 | đạt | 250,0 · 9,3 / 70,8 | đạt |
| 300 | 285,3 · 456 / 681 | không | **298,8 · 137 / 276** | **đạt** (bỏ 146, 0,8%) |
| 400 | 289,7 · 479 / 723 | không | **316,6** · 375 / 738 | không |

## 4. Vì sao không tăng nhiều hơn

Ở trần flash, hàng nóng phục vụ khoảng 409 lệnh/s (204 đơn × 2), tức khoảng 2,45 ms giữ khoá mỗi lệnh. Mức sàn đo bằng
pgbench cho riêng `UPDATE` + `COMMIT` một hàng là 1,3–1,4 ms
([pgbench-commit-floor.txt](./inventory-reserve-hot-sku-evidence-2026-10-19/pgbench-commit-floor.txt)). Khoảng 1 ms còn
lại là hai round-trip trong vùng khoá: dirty mark (Reserve và Release) và câu ghi kết quả command của executor.

- **Thời gian thực thi các câu khác gần như không đổi.** Ví dụ `INSERT reservation_line` mất 0,147 ms so với 0,180 ms ở
  baseline. Toàn bộ phần chờ khoá giờ nằm trong câu UPDATE có điều kiện: trung bình 68,6 ms ở bậc chỉ-Reserve 400/s, vì
  khoảng 17 connection cùng chờ.
- **Hàng nóng không còn là giới hạn duy nhất.** Khi bão hoà, số connection chờ khoá giảm từ 19–22 xuống 14–17 trên 23.
  CPU PostgreSQL tăng từ khoảng 113% lên 175% (trên trần 300%), và CPU Inventory tăng từ khoảng 58% lên 75–79%.
- **Mức tăng của chỉ-Reserve nhỏ hơn của flash.** Trần chỉ-Reserve tăng 9%, trần flash tăng 24%. Một Reserve có thêm
  câu dirty mark trong vùng khoá, còn Commit thì không. Phép đo này chưa tách được phần đóng góp của từng yếu tố.

Hai bước có thể rút thêm, đều **chưa đo**:
1. Gộp dirty mark vào chung câu UPDATE stock bằng CTE.
2. Ghi kết quả command trước câu stock. Việc này cần thêm một hook "bước cuối có thể thất bại" vào executor; ADR-033 đã
   ghi đây là phương án hoãn lại.

Làm cả hai thì vùng khoá chỉ còn 1 câu + COMMIT, khi đó mới tiến gần trần khoảng 700/s của một hàng.

## 5. Tính đúng

- **Đối soát phía DB khớp ở mọi bậc của cả ba lượt:** dòng `reservation_line` theo trạng thái so với bucket, và
  `on_hand − available − quarantine` so với `reserved + committed`.
- **Test:** [test-results.txt](./inventory-lock-late-evidence-2026-10-19/test-results.txt).
  - Trước thay đổi: 56/56 xanh. Sau thay đổi: 60/60 xanh.
  - `LockLateConcurrencyIT` chạy với `inventory.command.internal-retries=0`. Vì vậy deadlock hay lock timeout nào cũng
    thành `503` và làm test fail, thay vì được retry và che đi. Bốn test của nó:
    - 60 Reserve tranh 20 đơn vị, rồi Commit/Release xen với 40 Reserve mới;
    - 60 đơn hai dòng theo thứ tự dòng ngược nhau, rồi Commit;
    - `REJECT_ALL` liệt kê đủ dòng thiếu và không để lại dấu vết;
    - replay của RESERVED và `REJECT_ALL`.
  - **Đột biến 1, bỏ quay về savepoint:** 4 test fail, trong đó có test cũ `ReservationWorkflowIT.reserveIsAllOrNothing…`.
  - **Đột biến 2, bỏ điều kiện `available_to_sell ≥ 0`:** 6 test fail, trong đó có test cũ
    `ConcurrencyIT.hotSkuNeverOversells`.

## 6. Giới hạn

- **Một máy, một replica Inventory, một replica Order mô phỏng, disk của Docker Desktop.** Chi phí COMMIT phụ thuộc
  storage, nên tỉ lệ cải thiện trên production có thể khác.
- **Mỗi bậc một lần chạy.** Baseline flash chạy lại trong cùng phiên; baseline chỉ-Reserve dùng lượt đo 21:50.
- **Đơn một dòng, một kho.** Đơn nhiều dòng giữ khoá dòng đầu trong lúc cập nhật các dòng sau. Phép đo hiệu năng chưa
  phủ trường hợp này; test chỉ kiểm tra tính đúng.
- **Đường `REJECT_ALL` chậm hơn trước** (ghi rồi quay về savepoint, rồi khoá và đọc lại). Benchmark không có trường hợp
  hết hàng, nên chi phí của đường này chưa được đo.

## 7. Chạy lại

```bash
cp java/services/inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar loadtest/inventory-bench/jars/inventory-lock-late.jar
MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 MAX_VUS=32 TIMEOUT=8s DURATION=120s RUN_ID=20261019-lock-late \
  RATES="50 75 100 125 150 200 300" bash scripts/ci/inventory-bench.sh lock-late loadtest/inventory-bench/jars/inventory-lock-late.jar flash 1
MAX_VUS=64 TIMEOUT=8s DURATION=120s RUN_ID=20261019-lock-late RATES="150 200 250 300 400" \
  bash scripts/ci/inventory-bench.sh lock-late loadtest/inventory-bench/jars/inventory-lock-late.jar hot 1
MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 MAX_VUS=32 TIMEOUT=8s DURATION=120s RUN_ID=20261019-lock-late \
  RATES="50 75 100 125 150 200 300" bash scripts/ci/inventory-bench.sh baseline-rerun loadtest/inventory-bench/jars/inventory-baseline.jar flash 1
python loadtest/inventory-bench/analyze.py loadtest/inventory-bench/results/20261019-lock-late
```

Evidence nằm ở [inventory-lock-late-evidence-2026-10-19/](./inventory-lock-late-evidence-2026-10-19/):

- `summary.json`, `summary.md`: số liệu từng bậc của ba lượt.
- `environment-*.txt`: SHA-256 của jar, git HEAD, cấu hình tải và PostgreSQL, giờ chạy.
- `host-load.txt`: tải CPU của máy và số tiến trình game mỗi 15 s.
- `*-sql-statements.txt`: `pg_stat_statements` ở bậc bão hoà của từng lượt.
- `test-results.txt`: test trước/sau và hai lần chạy đột biến.
- `raw-results.tar.gz`: dữ liệu thô của mọi bậc.
