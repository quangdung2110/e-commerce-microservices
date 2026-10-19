# Inventory flash sale trên một SKU: baseline Reserve + Commit — 19/10/2026

## Kết luận chính

Đây là phép đo **trước khi sửa code**, nối tiếp [baseline Reserve trên một SKU nóng](./inventory-reserve-hot-sku-baseline-2026-10-19.md).
Mỗi đơn đi qua hàng `stock_position` nóng hai lần: một lần Reserve, rồi 15 giây sau một lần CommitPayment (90%) hoặc
ReleaseReservation (10%). Cả ba lệnh đều khoá cùng một hàng. Jar và cấu hình giống hệt lượt baseline.

Lượt có ý nghĩa nhất là kịch bản C. Ở đó số request đồng thời bị giới hạn **giống như một replica Order gửi thật**:
bulkhead 64 lệnh đang chạy tới Inventory và timeout 8 s (Order ADR-024).

| Kịch bản C (64 đồng thời, 120 s mỗi bậc) | Đạt SLO Reserve tới | Trần thông lượng | Khi vượt trần |
|---|---:|---:|---|
| **Flash sale: Reserve + Commit/Release** | **100 đơn/s** | ≈ **150–167 đơn/s** | p99 ≈ 0,6 s; phần dư không được gửi (tương ứng `BULKHEAD_FULL`) |
| Chỉ Reserve (đối chiếu baseline) | 250/s | ≈ 285–290/s | p99 ≈ 0,7 s |

- **Một đơn flash sale tốn hai lượt giữ khoá.** Khi bão hoà, hàng nóng phục vụ khoảng 300–335 lệnh/s trong cả hai
  kịch bản, tức khoảng 3,0–3,5 ms giữ khoá cho mỗi lệnh, dù đó là Reserve hay Commit. Vì vậy trần tính theo đơn chỉ
  bằng một nửa trần chỉ có Reserve. Mức ước lượng 150–170 đơn/s đưa ra trước khi đo là đúng cho trần, nhưng mức còn đạt
  SLO thì thấp hơn: 100 đơn/s.
- **Không giới hạn số request đồng thời thì Inventory sụp.** Cùng kịch bản nhưng để bộ sinh tải gửi tới 2 000
  request/luồng (kịch bản mở, mục 3): từ 100 đơn/s latency lên tới hàng chục giây. Tải đưa vào càng cao thì số đơn hoàn
  tất càng **giảm** (105 → 72 → 52 → 16 đơn/s). Inventory không có cơ chế tự giới hạn số lệnh nhận vào; việc giữ ổn định
  hoàn toàn dựa vào bulkhead phía caller.
- **Đính chính baseline.** "Đạt SLO tới 250/s" trong báo cáo baseline vẫn đúng khi số request đồng thời bị giới hạn như
  Order: 250/s chạy đủ 120 s vẫn đạt (C2). Nhưng khi không giới hạn và chạy 120 s thay vì 60 s, chính mức 250/s đó sụp
  (đối chứng A: p50 9,3 s, chỉ phục vụ 203/s). Các con số giây ở bậc 300–600/s của baseline là hệ quả của hàng đợi
  không giới hạn, không phải của Inventory dưới tải thật.
- **Tính đúng giữ nguyên.** Ở mọi bậc của mọi lượt, đối soát phía DB khớp: số dòng hàng RESERVED/COMMITTED bằng đúng
  các bucket, và tổng movement khớp với tồn kho. Không có bán vượt, deadlock hay rollback.

**Hệ quả.** Tải dự kiến của Release 1 là 10 Reserve/s ([development.md](../inventory/development.md#preconditions-before-production)),
tức khoảng 1 đơn/s vào SKU nóng. Như vậy dự phòng vẫn còn khoảng 100 lần. Nếu muốn chịu flash sale trên 100 đơn/s cho
một SKU, khoá muộn phải áp dụng cho **cả Reserve, Commit và Release** (mục 8).

Các con số là **so sánh tương đối trên một máy**, không phải năng lực production (mục 9).

## 1. Kịch bản

[`loadtest/k6/inventory-reserve.js`](../../loadtest/k6/inventory-reserve.js) với `MODE=reserve-commit` chạy hai luồng
cùng tốc độ:

- **Luồng Reserve**: mỗi lượt là một đơn mới, một dòng, số lượng 1, vào `BENCH-HOT`. Header giống hệt Order gửi.
- **Luồng hoàn tất**: bắt đầu sau `COMMIT_DELAY = 15 s`. Lượt thứ n kết thúc đơn thứ n của luồng Reserve bằng
  CommitPayment, hoặc bằng ReleaseReservation (`PAYMENT_FAILED`) cho `RELEASE_SHARE = 10%` số đơn. Mã đơn và lựa chọn
  commit/release được tính từ số thứ tự lượt chạy, nên hai luồng khớp nhau mà không cần chia sẻ trạng thái.
- **Không retry phía client.** Response `409 RESERVATION_NOT_RESERVED` ("Not reserved" trong bảng) nghĩa là Reserve của
  đơn đó thất bại, bị bỏ, hoặc chưa xong khi Commit tới. Nó được đếm riêng, không tính là lỗi của Inventory.

Ba cấu hình tải:

| Lượt | Request đồng thời mỗi luồng | Timeout client | Bậc |
|---|---:|---:|---|
| Mở (`20261019-flash`) | tối đa 2 000 | 30 s | 50–300 đơn/s, 120 s |
| Đối chứng A / B (`20261019-flash-controls`) | 2 000 | 30 s | A: chỉ Reserve 250/s, 120 s; B: flash 50 và 100 đơn/s, 60 s |
| **C (`20261019-bulkhead`)** | **32** (64 cho cả hai luồng; chỉ Reserve: 64) | **8 s** | flash 50–300 đơn/s; chỉ Reserve 150–400/s; 120 s |

Ở kịch bản C, lượt đến không còn VU rảnh sẽ bị bỏ. Đó đúng là điều Order làm khi bulkhead đầy: trả `UNKNOWN BULKHEAD_FULL`
và để saga thử lại sau. Vì vậy cột "Dropped" ở C là số lệnh bị hoãn sang lần replay sau, không phải lỗi.

Tiêu chí SLO cho một bậc giống baseline: Reserve p95 ≤ 200 ms và p99 ≤ 500 ms, không quá 1% lỗi (Commit/Release cũng
tính), không quá 1% lượt đến bị bỏ. Commit chưa có SLO riêng.

## 2. Môi trường

Giống [baseline](./inventory-reserve-hot-sku-baseline-2026-10-19.md#2-môi-trường-và-phương-pháp): stack `inventory-bench`
tách biệt, Inventory 2 CPU, PostgreSQL 3 CPU, jar `4697d0c9…`, worker bật. Mỗi lượt bắt đầu bằng seed lại và warm-up.

**Máy đo phải rảnh.** Lần chạy C đầu tiên (21:18–21:42) trùng lúc League of Legends đang chạy trên cùng máy: client từ
20:39, trận đấu từ 21:30, dùng khoảng 3,7 core. Lần đó CPU cho cùng lượng việc gấp đôi và kết quả xấu hơn hẳn (ví dụ
50 đơn/s có p99 2 s), nên bị loại. Thư mục của nó được giữ trong raw archive dưới tên `20261019-bulkhead-INVALID-host-busy`.
Lượt C dùng trong báo cáo chạy 21:50–22:28 sau khi game đã tắt. Tải của máy được ghi mỗi 15 s
([host-load-bulkhead.txt](./inventory-flash-sale-evidence-2026-10-19/host-load-bulkhead.txt)): 148 mẫu, không có tiến
trình game, CPU trung bình 19%. Lượt mở và lượt đối chứng chạy 19:58–20:36, trước khi client game được mở.

## 3. Kết quả: không giới hạn số request đồng thời

| Đơn/s | RESERVED/s | Hoàn tất/s | SLO | Reserve p50 / p95 / p99 ms | Commit p50 / p99 ms | Unavail. | Timeout | Bỏ (res / fin) | Câu `FOR UPDATE` TB ms | CPU Inv / PG % |
|---:|---:|---:|:---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 50,0 | 50,0 | không | 8,0 / 38,8 / 618 | 7,0 / 612 | 0 | 0 | 0 / 0 | 11,1 | 39 / 33 |
| 75 | 75,0 | 75,0 | đạt | 7,6 / 26,7 / 292 | 7,4 / 258 | 0 | 0 | 0 / 0 | 7,6 | 35 / 47 |
| 100 | 86,4 | 84,1 | không | 9 162 / 22 122 / 22 815 | 12 609 / 22 882 | 86 | 0 | 1 595 / 1 819 | 147 | 62 / 108 |
| 125 | 110,1 | 104,9 | không | 8 610 / 22 372 / 23 463 | 9 853 / 23 975 | 25 | 0 | 1 787 / 2 394 | 135 | 59 / 116 |
| 150 | 79,1 | 72,1 | không | 13 200 / 29 169 / 29 792 | 19 000 / 29 772 | 170 | 3 514 | 6 678 / 7 457 | 155 | 62 / 122 |
| 200 | 76,1 | 52,4 | không | 16 134 / 29 642 / 29 935 | 26 079 / 29 957 | 125 | 6 309 | 12 100 / 14 012 | 172 | 60 / 119 |
| 300 | 44,2 | 15,6 | không | 7 121 / 26 834 / 29 313 | 22 312 / 29 880 | 116 | 13 258 | 24 455 / 26 964 | 215 | 63 / 119 |

"Unavail." là `INVENTORY_UNAVAILABLE`, sinh ra khi request chờ connection Hikari quá 3 s. "Timeout" là request k6 bỏ
cuộc sau 30 s. Ở mọi bậc có 0 `INVENTORY_BUSY`.

Đọc kết quả:

- Bậc 50 đơn/s có p99 618 ms, nhưng bậc 75 lại đạt. Ở kịch bản C và đối chứng B, 50 đơn/s cho p99 khoảng 15 ms. Vì vậy
  đây là nhiễu ở bậc đầu tiên, không phải giới hạn.
- **Sụp theo kiểu congestion collapse.** Từ 100 đơn/s, hàng đợi dồn lên Hikari (trung bình 74–126 request chờ, tối đa
  176) và Tomcat. Tải đưa vào càng tăng thì tổng số lệnh được phục vụ càng giảm, từ khoảng 215 xuống 60 lệnh/s. Thời
  gian chờ khoá trung bình tăng từ 135 lên 215 ms, dù số connection đang chờ khoá vẫn giữ quanh 22/23. Cơ chế làm thông
  lượng giảm theo độ sâu hàng đợi **chưa được xác định** (mục 7).

## 4. Đối chứng: thời gian chạy hay Commit?

| Lượt | Tải | Kết quả |
|---|---|---|
| A: chỉ Reserve, **120 s** | 250/s | RESERVED 202,7/s, p50 / p95 / p99 = 9 309 / 11 867 / 12 314 ms, 5 675 lượt bị bỏ. Ở baseline, 250/s chạy **60 s** đạt SLO (p99 376 ms) |
| B: flash, **60 s** | 50 đơn/s | Đạt SLO (Reserve p99 15,4 ms, Commit p99 16,2 ms) |
| B: flash, **60 s** | 100 đơn/s | Không đạt: hoàn tất 93,2 đơn/s, Reserve p50 / p99 = 2 150 / 5 829 ms, 308 / 406 lượt bị bỏ |

Hai yếu tố cùng tác động:
- **Thời gian chạy:** khi không giới hạn đồng thời, một bậc 60 giây che mất hàng đợi đang tích dần (A).
- **Gấp đôi số lệnh trên hàng:** flash 100 đơn/s gãy ngay cả ở 60 giây (B).

## 5. Kết quả: giới hạn đồng thời như Order (kịch bản C)

### Flash sale (32 + 32 đồng thời, timeout 8 s)

| Đơn/s | Hoàn tất/s | SLO | Reserve p50 / p95 / p99 ms | Commit p50 / p95 / p99 ms | Not reserved | Bỏ (res / fin) | Câu `FOR UPDATE` TB ms | Chờ khoá TB / max | Hikari chờ TB / max | CPU Inv / PG % |
|---:|---:|:---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 50,0 | đạt | 6,0 / 11,1 / 14,8 | 6,7 / 11,4 / 15,8 | 0 | 0 / 0 | 1,4 | 0,4 / 1 | 0 / 0 | 25 / 24 |
| 75 | 75,0 | đạt | 7,2 / 14,7 / 143 | 6,7 / 15,2 / 151 | 1 | 0 / 0 | 4,0 | 1,4 / 21 | 0,2 / 21 | 32 / 43 |
| **100** | **100,0** | **đạt** | 6,4 / 64,9 / 252 | 7,1 / 60,9 / 231 | 0 | 0 / 0 | 9,5 | 2,5 / 23 | 0,2 / 20 | 40 / 57 |
| 125 | 119,1 | không | 6,9 / 373 / 629 | 6,9 / 356 / 608 | 131 | 712 / 581 | 31,1 | 9,6 / 23 | 8,9 / 40 | 46 / 74 |
| 150 | 149,3 | không | 18,5 / 278 / 420 | 16,2 / 262 / 412 | 54 | 85 / 31 | 37,7 | 11,8 / 23 | 7,9 / 39 | 47 / 84 |
| 200 | 145,3 | không | 173 / 429 / 586 | 169 / 414 / 597 | 327 | 6 561 / 6 234 | 68,7 | 18,3 / 23 | 29,9 / 41 | 52 / 103 |
| 300 | **167,1** | không | 157 / 399 / 568 | 155 / 389 / 556 | 108 | 15 949 / 15 841 | 63,5 | 18,3 / 23 | 30,7 / 41 | 55 / 111 |

Ở mọi bậc: 0 `INVENTORY_BUSY`, 0 `INVENTORY_UNAVAILABLE`, 0 timeout.

### Chỉ Reserve (64 đồng thời, timeout 8 s)

| Reserve/s | RESERVED/s | SLO | p50 / p95 / p99 ms | Bỏ | Câu `FOR UPDATE` TB ms | Chờ khoá TB / max | Hikari chờ TB / max | CPU Inv / PG % |
|---:|---:|:---:|---:|---:|---:|---:|---:|---:|
| 150 | 150,0 | đạt | 4,9 / 8,1 / 15,8 | 0 | 0,4 | 0,4 / 16 | 0 / 0 | 38 / 37 |
| 200 | 200,0 | đạt | 4,7 / 9,4 / 24,4 | 0 | 0,7 | 0,3 / 5 | 0 / 0 | 39 / 50 |
| **250** | **250,0** | **đạt** | 4,9 / 102 / 312 | 6 | 9,2 | 3,6 / 23 | 1,1 / 29 | 49 / 68 |
| 300 | 285,3 | không | 147 / 456 / 681 | 1 763 | 78,7 | 21,9 / 23 | 28,6 / 41 | 62 / 116 |
| 400 | **289,7** | không | 159 / 479 / 723 | 13 238 | 78,0 | 22,2 / 23 | 39,2 / 41 | 63 / 116 |

Đọc kết quả:

- **Không sụp.** Vượt trần thì thông lượng đứng yên ở trần, latency dừng dưới 1 s, và phần dư bị bỏ ngay từ phía
  caller. Hikari có tối đa khoảng 41 request chờ, không phải 176 như kịch bản mở, nên không có timeout 3 s.
- **Mỗi lệnh tốn khoảng 3 ms khoá, dù là Reserve hay Commit.** Trần chỉ Reserve khoảng 290 lệnh/s; trần flash khoảng
  167 đơn/s, tức khoảng 334 lệnh/s. Cả hai cùng cỡ với baseline (khoảng 3,0 ms mỗi lệnh). Mức đạt SLO tính theo đơn vì
  vậy chỉ còn khoảng 40% mức của Reserve (100 so với 250).
- **Bậc 150 đơn/s có p99 thấp hơn bậc 125** nhưng p95 vượt 200 ms. Mỗi bậc chỉ chạy một lần, nên không suy diễn từ thứ
  tự hai bậc sát ngưỡng này.
- **"Not reserved" xuất hiện từ 125 đơn/s.** Đây là các đơn có Reserve bị bỏ vì bulkhead đầy, nên Commit 15 s sau không
  tìm thấy reservation. Order thật không gửi Commit cho đơn chưa reserve thành công.

## 6. Tính đúng

Từ lượt đối chứng trở đi, driver đối soát phía DB sau mỗi bậc: đếm dòng `reservation_line` theo trạng thái của
`BENCH-HOT`, so với bucket `reserved`/`committed` và `on_hand − available − quarantine`. **Mọi bậc đều khớp.**

Cuối lượt mở có bảng đối soát đầy đủ, gồm cả movement
([reconciliation-flash.txt](./inventory-flash-sale-evidence-2026-10-19/reconciliation-flash.txt)):
- 73 278 Reserve − 60 044 Commit − 6 683 Release = 6 551 đang giữ, đúng bằng bucket.
- `available_to_sell` = 10¹² − 73 278 + 6 683, đúng từng đơn vị.

Phép đếm phía client (số RESERVED k6 thấy so với số `reserved` tăng thêm) chỉ có ý nghĩa khi không có timeout. Ở các bậc
150–300 đơn/s của lượt mở, k6 bỏ cuộc 3 514–13 258 request mà Inventory vẫn commit. Ở những bậc đó `analyze.py` ghi "—",
còn đối soát phía DB là căn cứ.

## 7. Những điều quan sát được ngoài câu hỏi chính

1. **Inventory không tự giới hạn số lệnh nhận vào.** Khi caller gửi nhiều hơn khả năng phục vụ, request xếp hàng ở
   Tomcat (200 thread) và Hikari (24 connection), gây timeout Hikari 3 s, timeout client, và thông lượng tụt dần. Bulkhead
   64 của Order là thứ duy nhất ngăn điều này. Bulkhead tính **theo replica Order**: N replica gửi tới 64 × N lệnh đồng
   thời. Khoảng giữa 64 và 4 000 chưa đo, nên chưa biết từ bao nhiêu replica Order thì Inventory bắt đầu sụp.
2. **Khi sụp, phiên giữ khoá thường ở trạng thái `ClientRead`** (PostgreSQL đợi Inventory gửi câu tiếp theo). Các phiên
   còn lại chờ `transactionid`/`tuple`.
   - **Đã loại trừ:** thiếu CPU (Inventory khoảng 60% trên trần 200%, PostgreSQL khoảng 120% trên trần 300%), CPU
     throttling của cgroup (`nr_throttled` không tăng trong 10 s đo), GC (tổng pause khoảng 1 s qua nhiều phút), phình
     bảng (`stock_position` có 500–800 dead tuple, autovacuum chạy mỗi phút, HOT update hoạt động).
   - **Chưa xác định:** vì sao thời gian giữ khoá tăng khi hàng đợi sâu. Mỗi câu SQL cũng chậm hơn (ví dụ UPDATE
     `stock_position` 0,196 ms so với 0,104 ms ở baseline), nhưng không giải thích hết.
3. **Alert ADR-032 vẫn đúng ở đây.** Có 0 `INVENTORY_BUSY` ở mọi lượt; tín hiệu chỉ nằm ở latency và Hikari pending. Ở
   kịch bản C, Hikari có trung bình 29–31 request chờ khi bão hoà (bậc 200–300 đơn/s), vượt ngưỡng 10 của
   `InventoryDbPoolSaturated`. Ở các bậc đạt SLO, giá trị là 0–0,2.

## 8. Hệ quả cho đề xuất khoá muộn

- Một lệnh giữ khoá khoảng 3,0–3,5 ms, trong khi riêng `UPDATE` + `COMMIT` tốn khoảng 1,3–1,4 ms
  ([pgbench](./inventory-reserve-hot-sku-evidence-2026-10-19/pgbench-commit-floor.txt)). Trần lý thuyết sau khoá muộn vì
  vậy cao hơn khoảng 2,2–2,5 lần, cho cả Reserve lẫn Commit.
- Commit và Release khoá `stock_position` trong `moveAll` rồi mới cập nhật dòng hàng, root, audit và kết quả command.
  Chỉ sửa Reserve thì một nửa số lượt giữ khoá của mỗi đơn vẫn như cũ, nên phải sửa cả ba lệnh.
- Khoá muộn không giải quyết mục 7.1. Nếu caller vượt bulkhead (nhiều replica Order), hàng đợi vẫn dồn vào Inventory.
  Giới hạn số lệnh nhận vào phía Inventory là một câu hỏi thiết kế riêng, không thuộc đề xuất này.

## 9. Giới hạn

- **Một máy, một replica Inventory, một replica Order mô phỏng (bulkhead 64), disk của Docker Desktop.**
- **Order thật không có ở đây.** Không có Temporal, không có replay khi `BULKHEAD_FULL`. Phần bị bỏ ở kịch bản C sẽ quay
  lại sau backoff 5 s, 10 s, ..., làm tải thật ở các bậc vượt trần cao hơn số đo.
- **Đơn một dòng, một kho, khoảng trễ thanh toán 15 s** (thật là tối đa 15 phút). Khoảng trễ không đổi tải trên hàng
  nóng ở trạng thái ổn định, nhưng làm số reservation đang sống ít hơn thật (không tới 9 000).
- **Mỗi bậc một lần chạy.** Lần chạy C đầu tiên trên máy bận cho số khác hẳn, nên các con số rất nhạy với tải nền.

## 10. Chạy lại

Xem [loadtest/inventory-bench/README.md](../../loadtest/inventory-bench/README.md). Các lệnh của báo cáo:

```bash
# lượt mở
MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 DURATION=120s RUN_ID=20261019-flash RATES="50 75 100 125 150 200 300" \
  bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar flash 1
# đối chứng A và B
RUN_ID=20261019-flash-controls RATES="250" DURATION=120s bash scripts/ci/inventory-bench.sh reserve-120s loadtest/inventory-bench/jars/inventory-baseline.jar hot 1
MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 RUN_ID=20261019-flash-controls RATES="50 100" DURATION=60s \
  bash scripts/ci/inventory-bench.sh flash-60s loadtest/inventory-bench/jars/inventory-baseline.jar flash 1
# kịch bản C (giới hạn như một replica Order)
MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 MAX_VUS=32 TIMEOUT=8s DURATION=120s RUN_ID=20261019-bulkhead \
  RATES="50 75 100 125 150 200 300" bash scripts/ci/inventory-bench.sh bulkhead64 loadtest/inventory-bench/jars/inventory-baseline.jar flash 1
MAX_VUS=64 TIMEOUT=8s DURATION=120s RUN_ID=20261019-bulkhead RATES="150 200 250 300 400" \
  bash scripts/ci/inventory-bench.sh bulkhead64 loadtest/inventory-bench/jars/inventory-baseline.jar hot 1
python loadtest/inventory-bench/analyze.py loadtest/inventory-bench/results/20261019-bulkhead
```

Khi so sánh với khoá muộn, dùng **kịch bản C**, vì đó là kịch bản giống tải thật. Hãy kiểm tra máy rảnh trước khi chạy.

Evidence nằm ở [inventory-flash-sale-evidence-2026-10-19/](./inventory-flash-sale-evidence-2026-10-19/):

- `summary-flash.*`, `summary-flash-controls.*`, `summary-bulkhead.*`: số liệu từng bậc của ba lượt.
- `environment-*.txt`: SHA-256 của jar, git HEAD, cấu hình tải và PostgreSQL, giờ chạy.
- `host-load-bulkhead.txt`: tải CPU của máy và số tiến trình game mỗi 15 s trong kịch bản C.
- `reconciliation-flash.txt`: đối soát bucket, dòng hàng và movement cuối lượt mở.
- `flash-100-sql-statements.txt`, `bulkhead-flash-150-sql-statements.txt`: `pg_stat_statements` của một bậc bão hoà.
- `raw-results.tar.gz`: k6, docker stats, Prometheus, `pg_stat_activity` và `hot_reconcile` thô của mọi bậc, gồm cả
  lần C bị loại và lượt smoke.
