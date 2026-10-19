# Inventory Reserve trên một SKU nóng: baseline — 19/10/2026

## Kết luận chính

Đây là phép đo **trước khi sửa** cho đề xuất rút ngắn vùng giữ khoá `stock_position` của lệnh Reserve (khoá muộn, gộp
trừ tồn kho thành một câu lệnh có điều kiện ngay trước COMMIT). Chưa có thay đổi code nào được đo ở đây.

| Chỉ số (một replica Inventory, giới hạn 2 CPU; PostgreSQL 3 CPU) | Một SKU nóng | Đối chứng: 2 000 SKU nguội |
|---|---:|---:|
| Mức tải cao nhất còn đạt SLO Reserve (p95 ≤ 200 ms, p99 ≤ 500 ms) | **250 /s** | **600 /s** |
| RESERVED tối đa mỗi giây | **≈ 330** | ≈ 774 |
| Ở mức vượt trần: p50 / p99 | 1,6 s / 3,6 s (300/s) | 0,6 s / 2,5 s (800/s) |
| CPU Inventory / PostgreSQL khi bão hoà (trên trần 200% / 300%) | **60–63% / 114–116%** | 191% / 269% |
| Connection đang chờ khoá hàng (trên pool 24) | **22–23** | 0 |

- **Trần của một SKU nóng là hàng khoá, không phải CPU.** Ở 300–600/s mọi connection trừ một đều đứng chờ khoá
  `stock_position` của `BENCH-HOT`. Cả Inventory lẫn PostgreSQL còn dư hơn một nửa CPU. Cùng service, cùng máy, khi tải
  chia ra 2 000 SKU thì chịu được gấp 2,4 lần.
- **Mỗi Reserve giữ khoá khoảng 3 ms.** Trần khoảng 330 lệnh/s ứng với chừng 3,0 ms mỗi lệnh. Trong đó PostgreSQL chỉ
  mất khoảng **0,7 ms** để chạy các câu SQL sau khi lấy khoá. Phần còn lại là round-trip JDBC, việc của Java và COMMIT,
  tất cả diễn ra trong lúc vẫn giữ khoá.
- **SKU nóng kéo theo cả đơn của SKU khác.** Kịch bản trộn 50/50 ở 600/s có 300/s vào SKU nóng. Khi đó đơn SKU nguội
  có p50 **3,9 s** và cả hai nhóm chỉ còn khoảng 240/s. Riêng 600/s SKU nguội thì đạt SLO. Nguyên nhân là đơn nguội
  phải xếp hàng chờ cùng pool connection đã bị đơn nóng chiếm hết.
- **Không có `INVENTORY_BUSY` nào.** Pool chỉ có 24 connection, nên tối đa 23 transaction chờ khoá cùng lúc, và mỗi
  lần chờ chỉ khoảng 80 ms, còn xa `lock_timeout` 2 s. Hàng đợi dồn lên Hikari (tối đa 176 request chờ) và Tomcat.
  Alert `INVENTORY_BUSY > 1%` trong [operations.md](../inventory/operations.md) sẽ **không** báo khi một SKU nóng làm
  bão hoà Inventory. Chỉ alert latency mới bắt được.
- **Tính đúng giữ nguyên dưới mọi mức tải.** Ở cả 19 bậc, số `reserved` tăng thêm của `BENCH-HOT` bằng đúng số
  response RESERVED cho SKU đó. Không có deadlock, không có rollback.

**Hệ quả cho đề xuất.** Trên cùng PostgreSQL, riêng một `UPDATE` + `COMMIT` trên một hàng nóng đã tốn khoảng 1,3–1,4 ms,
tức trần khoảng **700–750/s** (mục 5). Vì vậy khoá muộn trên máy này chỉ có thể đưa trần một SKU nóng từ khoảng 330/s
lên **tối đa khoảng 2,2 lần**. Con số này thấp hơn mức 2–4 lần ước lượng trước khi đo. Muốn vượt trần COMMIT thì phải
tách hàng nóng thành nhiều hàng, không thể chỉ rút ngắn transaction.

**Đối chiếu với envelope đã thống nhất.** Gate production trong [development.md](../inventory/development.md#preconditions-before-production)
ghi envelope là **600 Reserve/phút (10/s)**, p95 tám dòng/đơn, 10% lưu lượng vào SKU nóng. Như vậy SKU nóng chỉ nhận
khoảng 1/s, trong khi một replica chịu được 250/s đơn một dòng trong SLO. Đơn tám dòng giữ khoá nhiều hàng lâu hơn và
chưa được đo, nhưng khó xoá được khoảng cách hơn hai bậc độ lớn này. **Trong envelope Release 1, hàng khoá nóng không
phải rủi ro hiệu năng.** Nó chỉ thành nút thắt khi một đợt bán vượt khoảng 200–250 đơn/s cho cùng một SKU trong cùng
một kho. Việc có cần chuẩn bị cho mức đó hay không là quyết định sản phẩm, không phải kết quả đo. Phép đo này **không**
thoả gate load-test kia: nó không dùng đơn tám dòng, nhiều kho hay 9 000 reservation đồng thời.

Các con số là **so sánh tương đối trên một máy**, không phải năng lực production (mục 7).

## 1. Đối tượng đo

`POST /internal/inventory/v1/reservations` ([ReservationService.reserve](../../java/services/inventory-service/src/main/java/com/ecommerce/inventory/service/ReservationService.java))
chạy trong một transaction duy nhất ([CommandExecutor](../../java/services/inventory-service/src/main/java/com/ecommerce/inventory/command/CommandExecutor.java)).
Thứ tự câu lệnh với một đơn một dòng:

1. Claim `command_execution`, advisory lock theo `order_id`, `warehouse … FOR SHARE`.
2. **`stock_position … FOR UPDATE`.** Từ đây hàng tồn kho bị khoá tới hết transaction.
3. INSERT `order_reservation`, INSERT `reservation_line`, UPDATE `stock_position`, INSERT `stock_movement`, UPSERT
   `sku_availability_dirty`, INSERT `audit_entry`, UPDATE `command_execution`, rồi COMMIT.

Jar được đo là `java/services/inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar` (SHA-256 `4697d0c9…`),
build 18/10 21:34 từ HEAD `0b71bdcd` cộng 4 file Inventory đã staged của đợt observability 18/10 (OTel bridge, JSON
log, histogram HTTP). Các file này không nằm trên đường Reserve. Không có file nguồn nào mới hơn jar. Tracing tắt
(`TRACING_SAMPLING_PROBABILITY=0.0`).

## 2. Môi trường và phương pháp

### Stack tách biệt

[`loadtest/inventory-bench/compose.yaml`](../../loadtest/inventory-bench/compose.yaml) dựng project `inventory-bench` với
network và PostgreSQL riêng. Lúc đo stack dùng chung đang tắt: 0 container ngoài bench.

| Thành phần | Giới hạn CPU | Ghi chú |
|---|---:|---|
| Inventory | 2 | Chạy từ jar, heap 1 GB, Hikari 24 (mặc định), `lock_timeout` 2 s, retry nội bộ 2 lần, worker **bật** như production |
| PostgreSQL 16 | 3 | `fsync`/`synchronous_commit` bật, `wal_sync_method=fdatasync`, `pg_stat_statements` |

Máy đo: Windows 11, Docker Desktop (WSL2) 16 CPU / 14,9 GiB. k6 0.54.0 chạy trong container trên cùng network.

### Dữ liệu

[`seed/inventory.sql`](../../loadtest/inventory-bench/seed/inventory.sql) tạo một kho `BENCH-WH-1`, SKU nóng `BENCH-HOT`
và 2 000 SKU nguội, mỗi SKU 10¹² hàng nên không bậc nào hết hàng (không có `REJECT_ALL`). Mỗi kịch bản bắt đầu bằng
cách dừng Inventory, seed lại, khởi động lại và warm-up trên SKU nguội (20→150/s), rồi 30/s trên SKU nóng.

### Tải

[`loadtest/k6/inventory-reserve.js`](../../loadtest/k6/inventory-reserve.js) dùng mô hình mở (`constant-arrival-rate`).
Mỗi lượt là một đơn mới, một dòng, số lượng 1, gửi đúng header mà Order gửi: `X-Service-Identity: order-service`,
`Idempotency-Key`, `X-Correlation-ID`, `X-Causation-ID` đều mới. Timeout 30 s, bằng `StartToClose` của activity Order.
**k6 không retry.** Ngoài đời Temporal retry 3 lần rồi vòng `untilKnown` tiếp tục thử, nên tải thật còn cao hơn.
Tối đa 2 000 request đồng thời (`MAX_VUS`). Mỗi bậc chạy 60 giây, nghỉ 15 giây.

| Kịch bản | `HOT_SHARE` | Bậc (lệnh/s) |
|---|---:|---|
| hot | 1 | 50, 100, 150, 200, 250, 300, 400, 600 |
| cold (đối chứng) | 0 | 100, 200, 300, 400, 600, 800 |
| mixed | 0,5 | 100, 200, 300, 400, 600 |

### Tiêu chí SLO cho một bậc

[operations.md](../inventory/operations.md) đặt SLO Reserve là p95 ≤ 200 ms và p99 ≤ 500 ms. Bậc đạt khi cả hai lớp
SKU đều thoả, đồng thời không quá 1% kết quả khác RESERVED (ngưỡng alert `INVENTORY_BUSY`) và không quá 1% lượt đến
bị bỏ.

### Số liệu thu thập mỗi bậc

- Từ k6: outcome theo lớp SKU (`reserved`, `rejected`, `busy`, `unavailable`, `timeout`) và latency.
- Từ Docker: CPU theo `docker stats`.
- Từ Prometheus, mỗi giây: Hikari pending/active/timeout và `inventory_commands_total`.
- Từ `pg_stat_activity`, mỗi giây: số backend đang chờ `Lock`.
- Từ PostgreSQL: `pg_stat_statements` sau mỗi bậc; commit, rollback và deadlock của `pg_stat_database`.
- Kiểm tra tính đúng: `reserved` của `BENCH-HOT` trước và sau bậc.

## 3. Kết quả: một SKU nóng

| Lệnh/s đưa vào | RESERVED/s | SLO | p50 / p95 / p99 ms | Bỏ | Câu `FOR UPDATE` TB ms | Chờ khoá TB / max | Hikari chờ TB / max | CPU Inv / PG % |
|---:|---:|:---:|---:|---:|---:|---:|---:|---:|
| 50 | 50,0 | đạt | 5,6 / 9,7 / 15,1 | 0 | 0,1 | 0 / 0 | 0 / 0 | 25 / 15 |
| 100 | 100,0 | đạt | 4,9 / 8,5 / 14,7 | 0 | 0,1 | 0 / 0 | 0 / 0 | 25 / 26 |
| 150 | 150,0 | đạt | 5,1 / 11,4 / 122 | 0 | 2,6 | 1,5 / 20 | 0 / 2 | 33 / 39 |
| 200 | 200,0 | đạt | 4,9 / 27,0 / 225 | 0 | 6,3 | 2,0 / 22 | 0,2 / 13 | 40 / 53 |
| **250** | **250,0** | **đạt** | 5,2 / 152 / 376 | 0 | 15,8 | 6,5 / 23 | 1,5 / 28 | 50 / 71 |
| 300 | 288,8 | không | 1 630 / 3 377 / 3 600 | 673 | 82,6 | 22,4 / 23 | 154 / 176 | 63 / 116 |
| 400 | **329,9** | không | 6 140 / 6 655 / 6 901 | 4 207 | 76,0 | 22,2 / 23 | 139 / 176 | 60 / 116 |
| 600 | 299,4 | không | 7 180 / 8 290 / 8 659 | 18 034 | 84,7 | 22,3 / 23 | 129 / 176 | 63 / 114 |

Ở mọi bậc: 0 `INVENTORY_BUSY`, 0 `INVENTORY_UNAVAILABLE`, 0 timeout, 0 deadlock, 0 rollback. Số `reserved` tăng thêm
bằng số response RESERVED.

Đọc kết quả:

- **Đuôi latency xuất hiện từ 150/s.** Khi lệnh tới dồn cục, chúng xếp hàng sau hàng khoá. p50 vẫn khoảng 5 ms tới
  250/s, nhưng p99 đã tăng từ 15 ms lên 376 ms.
- **Từ 300/s là gãy hẳn.** Tốc độ phục vụ tối đa (khoảng 290–330/s) thấp hơn tốc độ đến, nên hàng đợi lớn dần suốt
  bậc. Latency 6–8 s ở 400–600/s bị chặn bởi bộ sinh tải: 2 000 request đồng thời chia cho khoảng 330/s là khoảng 6 s
  theo định luật Little. Nếu Order có nhiều worker hơn, latency còn tăng tới khi chạm timeout 30 s.
- **CPU không tăng theo tải sau điểm gãy.** Cả hai container đứng yên ở khoảng một nửa trần. Đây là dấu hiệu của một
  nút thắt tuần tự.

## 4. Đối chứng và kịch bản trộn

### SKU nguội (đối chứng)

| Lệnh/s | RESERVED/s | SLO | p50 / p95 / p99 ms | Bỏ | Hikari chờ TB / max | CPU Inv / PG % |
|---:|---:|:---:|---:|---:|---:|---:|
| 100 | 100,0 | đạt | 5,8 / 11,3 / 17,3 | 0 | 0 / 0 | 53 / 36 |
| 200 | 200,0 | đạt | 5,0 / 8,9 / 12,2 | 0 | 0 / 0 | 45 / 61 |
| 300 | 300,0 | đạt | 5,4 / 9,7 / 16,1 | 0 | 0 / 0 | 73 / 105 |
| 400 | 400,0 | đạt | 5,1 / 9,2 / 15,4 | 0 | 0 / 0 | 90 / 121 |
| **600** | **600,0** | **đạt** | 5,4 / 10,9 / 53,9 | 0 | 1,0 / 62 | 132 / 177 |
| 800 | 773,7 | không | 641 / 2 191 / 2 468 | 1 581 | 61 / 176 | **191** / 269 |

Khi tải chia ra nhiều SKU, Inventory chạm trần CPU của chính nó (191% trên 200%) ở khoảng 774/s, không có backend nào
chờ khoá. Đây là năng lực của một replica trong cấu hình này. SKU nóng chỉ dùng được khoảng 40% năng lực đó.

### Trộn 50/50

| Lệnh/s (trong đó nóng) | RESERVED/s | SLO | Nóng p50 / p95 / p99 ms | Nguội p50 / p95 / p99 ms | Bỏ | Chờ khoá TB / max |
|---:|---:|:---:|---:|---:|---:|---:|
| 100 (50) | 100,0 | đạt | 7,0 / 13,1 / 19,1 | 7,0 / 13,1 / 19,7 | 0 | 0 / 0 |
| 200 (100) | 200,0 | đạt | 5,3 / 10,1 / 16,5 | 5,4 / 8,9 / 13,0 | 0 | 0,1 / 2 |
| 300 (150) | 300,0 | đạt | 5,9 / 23,4 / 257 | 5,6 / 10,7 / **192** | 0 | 1,0 / 22 |
| **400 (200)** | **400,0** | **đạt** | 7,2 / 58,3 / 263 | 5,6 / 10,9 / 40,5 | 0 | 2,8 / 22 |
| 600 (300) | 480,2 | không | 3 977 / 5 305 / 5 586 | **3 886 / 5 195 / 5 382** | 7 188 | 20,7 / 23 |

Ở 600/s, đơn nguội chậm gần bằng đơn nóng. Nguyên nhân là pool 24 connection của Hikari là FIFO dùng chung: khi 23
connection đang chờ khoá `BENCH-HOT`, một đơn cho SKU bất kỳ cũng phải đợi tới lượt lấy connection. Mỗi nhóm chỉ còn
khoảng 240/s, ít hơn cả trần khoảng 330/s của SKU nóng chạy một mình. Ở 300/s đã thấy tác động sớm: p99 của đơn nguội
là 192 ms, so với 16 ms ở cùng mức tải trong kịch bản đối chứng.

## 5. Khoá được giữ trong bao lâu

### SQL sau khi lấy khoá (bậc hot 300/s, `pg_stat_statements`)

| Câu lệnh | Thời gian thực thi TB (ms) |
|---|---:|
| `SELECT … FROM stock_position … FOR UPDATE` (gồm thời gian chờ khoá) | **82,6** |
| `INSERT reservation_line` | 0,180 |
| `UPDATE stock_position` | 0,104 |
| `INSERT stock_movement` | 0,091 |
| `INSERT order_reservation` | 0,088 |
| `UPDATE command_execution` (kết quả) | 0,086 |
| `INSERT … ON CONFLICT` `sku_availability_dirty` | 0,068 |
| `INSERT audit_entry` | 0,063 |
| Kiểm tra FK `order_reservation` (`FOR KEY SHARE`) | 0,039 |
| **Tổng phần chạy sau khi lấy khoá** | **≈ 0,72** |

Với khoảng 330 lệnh/s qua một hàng, mỗi lệnh giữ khoá khoảng 3,0 ms. PostgreSQL chỉ thực thi khoảng 0,7 ms trong số đó.
Khoảng 2,3 ms còn lại là 7 round-trip JDBC, việc của Java giữa các câu (dựng JSON audit và kết quả) và COMMIT.
`pg_stat_statements` không ghi COMMIT, và `track_wal_io_timing` tắt, nên phép đo này **không tách** được phần ghi WAL
khỏi phần round-trip.

### Mức sàn của COMMIT

Sau lượt chính, `pgbench` chạy trên cùng container PostgreSQL với một bảng một hàng
([pgbench-commit-floor.txt](./inventory-reserve-hot-sku-evidence-2026-10-19/pgbench-commit-floor.txt)):

| Transaction trên một hàng nóng | Kết nối | Client | tps | Latency TB |
|---|---|---:|---:|---:|
| `UPDATE` (autocommit) | socket local | 1 | 771 | 1,30 ms |
| `UPDATE` (autocommit) | network bench | 1 | 702 | 1,42 ms |
| `UPDATE` (autocommit) | network bench | 24 | 746 | 32 ms |
| `BEGIN; SELECT … FOR UPDATE;` 6 × `SELECT 1`; `UPDATE; COMMIT` | network bench | 24 | 468 | 51 ms |

- Riêng `UPDATE` + `COMMIT` đã tốn khoảng 1,3–1,4 ms, nên trên máy này **không transaction nào khoá hàng qua COMMIT có
  thể vượt khoảng 700–750/s** cho một hàng.
- Giả lập "khoá + 8 round-trip + COMMIT" bằng SQL rỗng đạt 468 tps. Inventory thật đạt khoảng 330/s, chậm hơn khoảng
  1 ms mỗi lệnh. Khoảng 1 ms đó là phần thực thi SQL thật (khoảng 0,7 ms) cộng việc của Java.

Như vậy, đề xuất khoá muộn (chỉ còn một câu lệnh trước COMMIT trong vùng khoá) có trần lý thuyết khoảng 700–750/s, tức
tối đa khoảng 2,2 lần so với baseline trên máy này. Số thực tế chỉ biết khi đo lại bằng cùng harness. Trên storage
production, COMMIT có thể nhanh hoặc chậm hơn Docker Desktop, nên tỉ lệ cải thiện cũng sẽ khác.

## 6. Những điều quan sát được ngoài câu hỏi chính

1. **Alert `INVENTORY_BUSY` không phát hiện được SKU nóng.** Pool 24 connection giới hạn số transaction chờ khoá cùng
   lúc ở mức 23. Mỗi lần chờ chỉ khoảng 80 ms, không bao giờ chạm `lock_timeout` 2 s. Thời gian chờ connection của
   Hikari ước tính khoảng 0,5 s (176 request chờ chia cho khoảng 330/s) và đo được 0 lần timeout, tức không chạm
   `connection-timeout` 3 s. Kết quả là 0 response `503`. Hàng đợi nằm ở Hikari (tối đa
   176 request; có lẽ đó là 200 thread Tomcat mặc định trừ 24) và ở hàng đợi kết nối phía trước. Với nhiều replica
   Inventory, tổng số connection chờ trên hàng sẽ là N × 23 và thời gian chờ khoá dài hơn, nên khi đó có thể xuất
   hiện `INVENTORY_BUSY`. Điều này chưa đo. **Đã xử lý ngày 2026-10-19** bằng
   [Inventory ADR-032](../inventory/decisions/ADR-032-reserve-saturation-alerts.md): alert latency SLO theo đúng route
   Reserve, alert số request chờ connection Hikari trung bình > 10, và rule `InventoryCommandBusy` cho trigger cũ. Trước
   đó trigger này mới chỉ có trên giấy, chưa có rule.
2. **Bậc sát ngưỡng rất nhạy với warm-up.** Lượt smoke 10 giây chạy ngay sau khởi động cho p95 657 ms ở 200/s. Lượt
   chính 60 giây sau warm-up cho 27 ms. Mức "đạt SLO tới 250/s" chỉ là một lần chạy. Hãy đọc nó như "khoảng 200–250/s".
3. **Sau điểm gãy, thông lượng không tăng mà còn hơi giảm** (330/s ở 400 so với 299/s ở 600). Chỉ có một lần chạy nên
   không kết luận nguyên nhân.

## 7. Giới hạn

- **Một máy, một replica Inventory, bộ sinh tải cùng máy, disk của Docker Desktop.** Chi phí COMMIT (khoảng 1,3 ms) phụ
  thuộc storage, nên trần tuyệt đối trên production sẽ khác. Quan hệ "một hàng = một hàng đợi tuần tự" thì không đổi.
- **Chỉ có Reserve, đơn một dòng, một kho.** Không có Commit/Release/Recover chạy xen, mà các lệnh này cũng khoá
  `stock_position` của cùng SKU. Đơn nhiều dòng giữ khoá nhiều hàng lâu hơn.
- **Không có Order/Temporal phía trước.** Không có retry của activity và `untilKnown`, không có timeout 12 s của
  Checkout submit. Trong flash sale thật, retry làm tải lên hàng nóng cao hơn số đo ở đây.
- **Mỗi bậc chạy một lần, 60 giây.** Không có soak test.
- Bảng tăng dần qua các bậc của một kịch bản: khoảng 100 000 reservation ở cuối kịch bản hot, 140 000 ở cuối kịch bản
  cold. Mỗi kịch bản seed lại từ đầu. Không thấy tác động, nhưng cũng không đo riêng.

## 8. Chạy lại

Xem [loadtest/inventory-bench/README.md](../../loadtest/inventory-bench/README.md). Các lệnh của lượt này:

```bash
cp java/services/inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar loadtest/inventory-bench/jars/inventory-baseline.jar
RUN_ID=20261019-baseline RATES="50 100 150 200 250 300 400 600" bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar hot 1
RUN_ID=20261019-baseline RATES="100 200 300 400 600 800" bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar cold 0
RUN_ID=20261019-baseline RATES="100 200 300 400 600" bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar mixed 0.5
python loadtest/inventory-bench/analyze.py loadtest/inventory-bench/results/20261019-baseline
```

Để so sánh sau khi sửa: copy jar mới thành `jars/inventory-lock-late.jar` và chạy lại đúng ba lệnh với tên biến thể
`lock-late` và cùng `RUN_ID`. Nên bật thêm `track_wal_io_timing` cho cả hai biến thể để tách được thời gian ghi WAL.

Evidence nằm ở [inventory-reserve-hot-sku-evidence-2026-10-19/](./inventory-reserve-hot-sku-evidence-2026-10-19/):

- `summary.json`, `summary.md`: số liệu từng bậc, gồm cả CPU tối đa, Hikari active/timeout và số commit.
- `environment-baseline-*.txt`: SHA-256 của jar, git HEAD, cấu hình PostgreSQL, thời gian chạy.
- `hot-300-sql-statements.txt`: `pg_stat_statements` của bậc hot 300/s.
- `pgbench-commit-floor.txt`: phép đo mức sàn COMMIT.
- `alert-check.txt`: kiểm chứng alert của Inventory ADR-032 trên Prometheus thật (400/s kêu, 200/s im lặng).
- `raw-results.tar.gz`: k6, docker stats, mẫu Prometheus và `pg_stat_activity` thô của mọi bậc, kể cả warm-up.
