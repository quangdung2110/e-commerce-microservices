# Order dispatcher: một thread so với tám worker và batch ACK

> Sau [ADR-029](../order/decisions/ADR-029-parallel-workflow-messages-per-target-lanes.md) (2026-10-20), message
> `SignalWithStart` cũng chạy trên pool RPC chung với lane theo target và ACK theo lô; `-Variant parallel` nay đo
> implementation đó, còn implementation của báo cáo này được giữ làm fixture `-Variant adr028`. Xem
> [so sánh message lanes](./order-dispatcher-message-lanes-benchmark-2026-10-20.md). Số liệu lịch sử bên dưới giữ nguyên.

## Kết quả chính

Đã cập nhật code production sang **8 worker gửi start Temporal**, ghi kết quả theo lô, kiểm tra claim generation khi ACK/renew và gia hạn lease cho work chưa ACK.
Benchmark dùng PostgreSQL/Temporal thật, cùng bộ đo với [baseline trước tối ưu](./order-dispatcher-single-thread-benchmark-2026-10-16.md), và **chạy lại implementation cũ** để có cặp so sánh trong cùng phiên ngày 2026-10-16.

| Chỉ số | Cũ: 1 thread, ACK từng row | Mới: 8 worker, batch ACK | Thay đổi |
|---|---:|---:|---:|
| Median start/s khi luôn có backlog, 3 cửa sổ | 54,47 | **177,67** | **3,26 lần**, tăng 226% |
| ACK transaction / start, các cohort saturated | 1,000 | **0,174** | **Giảm 82,6%** |
| Start trung bình / ACK transaction, saturated | 1,00 | **5,75** | Gom nhiều kết quả trong một lần ghi |
| Tải vào 100/s: ACK/s trong cửa sổ | 55,07 | **99,47** | Theo kịp gần toàn bộ tải vào |
| Tải vào 100/s: dispatch latency p95 | 11.944 ms | **96 ms** | **Giảm 99,19%** |
| Tải vào 100/s: backlog tại giây 15 | 674 | **8** | Hết tích tụ lớn trong phép đo ngắn này |
| Burst 500: thời gian xử lý hết | 10,314 s | **2,926 s** | Nhanh khoảng 3,53 lần |
| Mixed 50% start / 50% message: tổng ACK/s | 54,40 | **86,67** | **1,59 lần** |

Hai lượt có tổng **35.150 thao tác được đo**: legacy 12.050, parallel 23.100. Tất cả ACK và ghi trạng thái terminal sau drain, mỗi row `attempts=1`, **0 row retry ở dispatcher và 0 row bỏ sót**. Không tính 100 start chuẩn bị target và 500 start warmup của mỗi lượt vào số trên.

Đây là cải thiện ở **khâu dispatch**, không phải throughput checkout hoặc latency tới `PAYMENT_READY`. Những số liệu trên không phải cam kết năng lực production.

## 1. Code đã thay đổi

- [WorkflowDispatcher](../../java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java): một coordinator claim/ghi DB, một pool **8 platform thread** gọi start. Mỗi lúc tối đa 8 future chưa được thu kết quả, queue executor giới hạn 8, nhóm row đã claim tối đa 50. RPC worker không giữ JDBC connection/transaction.
- Thu kết quả theo thứ tự hoàn thành. Coordinator flush khi đủ **50** kết quả, đã gom **20 ms** từ kết quả đầu đang chờ, hoặc nhóm start đã xong. Kết quả thành công được flush trong lúc một RPC khác còn chờ; không phải đợi đủ 50 row mới ghi. 20 ms là thời gian gom chủ động, không phải hard SLA nếu DB/coordinator bận.
- [WorkflowOutboxStore](../../java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java): một câu `UPDATE ... FROM (VALUES ...) RETURNING` trong mỗi transaction ACK, hỗ trợ cả `STARTED` và `RETRY`, giữ error/backoff riêng từng row.
- ACK/renew chỉ cập nhật khi **ID + `IN_FLIGHT` + owner + `attempts`** còn khớp. `attempts` tăng mỗi lần claim, bảo vệ cả trường hợp cùng pod reclaim. Kết quả cũ không ghi đè row của claim mới.
- Coordinator gia hạn các row chưa ACK mỗi **lease/3**, mặc định 10 giây. Row chưa gửi đã mất ownership được bỏ khỏi queue local. RPC đã gửi vẫn dựa vào fixed workflow ID/idempotency và fenced ACK khi trả về muộn.
- Start idempotent; shutdown ngắt coordinator/pool và chờ có giới hạn. Work chưa có ACK durable được phục hồi bằng lease/replay. Không cần schema migration.
- Message vẫn `SignalWithStart` tuần tự trên coordinator sau nhóm start, giữ nguyên closed-target resolver và transaction từng message. Workload mixed vì vậy tăng ít hơn start-only.

Chi tiết thiết kế, metric và giới hạn scheduler: [ADR-028](../order/decisions/ADR-028-parallel-workflow-starts-and-batched-ack.md).

| Property / env | Default |
|---|---:|
| `order.workers.dispatch-threads` / `ORDER_DISPATCH_THREADS` | **8** |
| `order.workers.dispatch-ack-batch-size` / `ORDER_DISPATCH_ACK_BATCH_SIZE` | **50** |
| `order.workers.dispatch-ack-interval` / `ORDER_DISPATCH_ACK_INTERVAL` | **PT0.02S** |

Claim batch 50, lease 30 giây, idle poll 200 ms, Hikari 24/4 giữ như baseline. Đặt thread về 1 chỉ thay concurrency của code mới; để đo đúng code cũ, benchmark dùng fixture legacy trong test scope, không có switch legacy trong runtime production.

## 2. Môi trường và cách so sánh

| Thành phần | Cả hai lượt |
|---|---|
| Thời gian đo | Ngày 2026-10-16, Asia/Bangkok; parallel khoảng 16:10–16:14, legacy khoảng 16:14–16:19 |
| JVM | Windows 11, Java `21.0.12.1+1-LTS`, 16 processor, `-Xms256m -Xmx1024m` |
| Docker Desktop | 16 CPU, khoảng 14,87 GiB RAM; bộ sinh tải cùng máy |
| Order DB | PostgreSQL **16.15**, `fsync`, `synchronous_commit`, `full_page_writes` đều bật; shared buffers 128 MiB |
| Temporal | Server **1.25.2**, SDK **1.25.1**, namespace `default`, PostgreSQL persistence riêng với Order DB |
| Pool | Hikari max 24 / min idle 4, `READ_COMMITTED` |
| Isolation | Project Compose, network, DB/volumes mới cho từng lượt; chỉ `api-gateway` của stack dùng chung chạy bên ngoài |
| Warmup | 100 pending workflow target cho message, 500 start warmup, không tính vào kết quả |
| Workflow worker | Không chạy business worker; chỉ đo Temporal nhận/persist start và signal, không gọi Inventory/Promotion/Payment |

Harness, gateway, producer và instrumentation giống nhau giữa hai variant. `legacy` dùng bản dispatcher tuần tự và ACK SQL cũ được giữ tại [fixture test-only](../../java/services/order-service/src/test/java/com/ecommerce/order/benchmark/baseline/LegacyWorkflowDispatcher.java); claim/message SQL hiện tại không đổi so với baseline. `parallel` gọi chính lifecycle và implementation production mới.

Mỗi lượt gồm burst 50/500, ba cửa sổ saturated start-only, tải đều 40/50/70/100 mỗi mức 15 giây và một ca saturated mixed. Bản mới có thêm hai mức adaptive 120/230 theo median riêng, ngoài các mức chung để so sánh.

Saturated preload 1.000 row và bổ sung nhóm 100 khi outstanding dưới 1.000; đo ACK trong đúng **15 giây**, sau đó dừng bơm tải và drain. Giữa các ca dừng dispatcher rồi reset hai bảng outbox; JVM/Temporal tiếp tục trong cùng lượt. Tải đều phát theo lịch tuyệt đối, không chờ ACK trước khi phát row tiếp theo.

**Latency trong các bảng dưới:** từ enqueue transaction commit tới ACK transaction commit, lấy toàn cohort kể cả phần drain. **Throughput:** chỉ số ACK trong cửa sổ chia độ dài cửa sổ; không cộng ACK sau cửa sổ. Dùng `System.nanoTime()`, callback sau JDBC commit và percentile nearest-rank, như báo cáo cũ.

## 3. Start-only khi luôn có việc chờ

| Cửa sổ 15 giây | Legacy ACK / start/s | Parallel ACK / start/s | Parallel gateway mean | Parallel ACK transaction mean |
|---|---:|---:|---:|---:|
| 1 | 803 / 53,53 | 2.487 / **165,80** | 39,73 ms | 7,81 ms |
| 2 | 817 / 54,47 | 2.816 / **187,73** | 35,53 ms | 6,34 ms |
| 3 | 843 / 56,20 | 2.665 / **177,67** | 37,23 ms | 6,77 ms |

Median tăng **54,47 → 177,67 start/s**, tương ứng **3,26 lần**. Verifier xác nhận giới hạn concurrency 8 từ timestamp RPC, và harness xác nhận cả ba ca saturated thực sự đạt 8 lời gọi đồng thời.

Một RPC của bản mới mất trung bình 36–40 ms ở tải saturated, cao hơn khoảng 14 ms của baseline cũ. Vì nhiều RPC đang cùng sử dụng Temporal/persistence, không thể lấy tốc độ một thread nhân 8 để dự báo kết quả. Số đo cho thấy tăng concurrency cải thiện tổng lượng việc, còn latency mỗi RPC và các bước coordinator/DB vẫn giới hạn mức tăng; benchmark không tách nguyên nhân nội bộ Temporal.

## 4. Chi phí ACK theo lô

Đếm trên toàn bộ ba cohort saturated, gồm cả drain, để không cắt một batch ở biên cửa sổ:

| | Legacy | Parallel |
|---|---:|---:|
| Row start được ACK | 5.700 | 11.100 |
| Transaction ACK start | 5.700 | **1.931** |
| Transaction / start | 1,000 | **0,174** |
| Row / transaction | 1,00 | **5,75** |

Giảm **82,6% transaction ACK trên mỗi start**. Đây là số chuẩn hóa theo lượng việc, không so sánh trực tiếp tổng transaction của hai cohort có số row khác nhau.

Giới hạn batch là 50 nhưng batch thực tế trung bình 5–6 row vì timer 20 ms flush sớm. Một transaction batch mất khoảng 6–8 ms trong các cửa sổ trên; nó cập nhật nhiều row thay vì khoảng 4 ms cho một row ở baseline. Giảm transaction/start không có nghĩa toàn bộ DB dùng ít CPU hơn, vì bản mới xử lý nhiều start và Temporal persistence hơn mỗi giây.

## 5. Cùng mức tải vào: latency và backlog

| Tải vào | ACK/s legacy → parallel | p95 legacy → parallel | p99 legacy → parallel | Backlog tại giây 15 legacy → parallel |
|---:|---:|---:|---:|---:|
| 40/s | 39,80 → **39,93** | 98,86 → **47,29 ms** | 153,90 → **57,39 ms** | 3 → **1** |
| 50/s | 49,33 → **49,80** | 317,73 → **61,30 ms** | 331,30 → **87,01 ms** | 10 → **3** |
| 70/s | 54,07 → **69,80** | 4.165,50 → **75,94 ms** | 4.514,78 → **93,92 ms** | 239 → **3** |
| 100/s | 55,07 → **99,47** | 11.944,22 → **96,21 ms** | 12.521,76 → **117,09 ms** | 674 → **8** |

Các mức 40–100/s đều được producer commit đủ tốc độ dự kiến trong cửa sổ. Bản mới không còn tích tụ backlog lớn ở 70/100/s trong phép đo 15 giây; vài row còn lại là work đang chạy/gom ACK tại mốc kết thúc. Điều này chưa chứng minh SLO cho tải kéo dài nhiều giờ.

Ở 100/s, p95 giảm **99,19%** vì tránh được phần lớn thời gian xếp hàng. Đây không phải một RPC được làm nhanh hơn 124 lần. So với baseline lịch sử, con số p95 của lượt legacy mới có dao động (khoảng 11,94 giây so với trước 11,07 giây), trong khi throughput saturated vẫn cùng khoảng 54–55/s. Báo cáo này dùng cặp đo mới để tính tỷ lệ.

### Tải cao hơn của bản mới

| Tải dự kiến | Commit/s thực tế trong 15 giây | ACK/s | Dispatch p95 | Backlog tại giây 15 |
|---:|---:|---:|---:|---:|
| 120/s | 120,00 | **119,13** | **117,57 ms** | 13 |
| 230/s | **204,20** | **185,60** | **1.803,53 ms** | 279 |

Ca 230/s có giới hạn ở producer: cần **17,04 giây** để commit 3.450 row dự kiến cho 15 giây, enqueue lateness p95 **1,92 giây**. Intended-arrival-to-ACK p95 là **3,69 giây**, lớn hơn commit-to-ACK **1,80 giây**. Vì vậy không coi ca này là đã phát đều đủ 230/s và không dùng nó để công bố năng lực ở 230/s. Tải commit thực tế vẫn cao hơn tốc độ phục vụ quan sát được và đã tạo backlog. Các ca saturated dùng transaction enqueue theo nhóm, vẫn bảo đảm luôn có việc để đo throughput dispatcher.

## 6. Burst và message

| Burst | Thời gian drain legacy → parallel | p95 legacy → parallel |
|---:|---:|---:|
| 50 row | 0,968 → **0,349 giây** | 928,47 → **350,54 ms** |
| 500 row | 10,314 → **2,926 giây** | 9.755,00 → **2.839,93 ms** |

Với mixed 50% start / 50% message:

| | Legacy | Parallel |
|---|---:|---:|
| Start/s | 27,73 | **43,33** |
| Message/s | 26,67 | **43,33** |
| Tổng thao tác/s | 54,40 | **86,67** |

Tăng **1,59 lần**, thấp hơn start-only vì message vẫn dùng RPC và ACK từng row trên coordinator. Một start chậm ở cuối nhóm cũng có thể trì hoãn lượt claim message/nhóm start tiếp theo; thiết kế này chưa chuyển thành scheduler liên tục giữa các queue. Mixed dùng message payload `{}` trên workflow pending, không đại diện đầy đủ mọi message nghiệp vụ.

## 7. Kiểm chứng, build và giới hạn

- **Order suite:** 109 test, **0 failure/error**, 1 benchmark test skip theo opt-in. Gồm 5 unit test mới về concurrency/batch/straggler/error/renewal/shutdown và 6 test PostgreSQL thật về mixed ACK/retry, rollback/reclaim, hai owner, cùng owner khác generation, renewal, `SKIP LOCKED`.
- Suite cuối chạy trên PostgreSQL test mới để tránh tái sử dụng ID payment giả giữa các JVM. Các lỗi phát hiện khi triển khai (binding constructor cấu hình, assertion shutdown dùng nhầm thread của context khác) đã được sửa trước khi chốt kết quả.
- Hai lượt benchmark đều PASS. [Verifier độc lập](../../loadtest/order-dispatch-bench/analyze.py) đối chiếu counts, ID duy nhất, trạng thái RPC, thứ tự timestamp, concurrency từ các khoảng RPC, throughput theo biên cửa sổ, backlog, latency/transaction percentile và số row/batch ACK từ CSV nén.
- [Comparator](../../loadtest/order-dispatch-bench/compare.py) xác nhận 18 field môi trường giống nhau và tính các tỷ lệ trong báo cáo. Hash của các file được đo khớp lại với source sau cả hai lượt; fixture legacy chỉ có trong test scope.
- Containers/volumes benchmark đã được dọn; inventory stack dùng chung trước/sau đều chỉ có `api-gateway`. Chạy `mvn -DskipTests package` riêng sau benchmark để tạo jar từ code mới; log build lưu trong evidence.

Giới hạn: một máy Windows/Docker Desktop, cửa sổ ngắn và một lượt/JVM mỗi variant với ba cửa sổ saturated, không randomized/soak test; DB outbox nhỏ và reset giữa ca. Instrumentation có overhead chưa tách riêng. Không chạy business worker, HTTP, Kafka hoặc dependency, nên không đo checkout end-to-end, `PAYMENT_READY` hay tỷ lệ HTTP `503`. Kiểm thử lỗi/lease nằm ở functional tests; benchmark không inject timeout quá 30 giây hoặc chạy nhiều dispatcher. Tăng tốc là hiệu quả gộp của concurrency, batch ACK và fencing/renewal, chưa có ablation để quy riêng cho từng thay đổi.

## 8. Chạy lại và dữ liệu

```powershell
./scripts/ci/order-dispatch-bench.ps1 -Variant parallel -Seconds 15
./scripts/ci/order-dispatch-bench.ps1 -Variant legacy -Seconds 15
```

Xem [benchmark README](../../loadtest/order-dispatch-bench/README.md) cho runtime/port và [ADR-028](../order/decisions/ADR-028-parallel-workflow-starts-and-batched-ack.md) cho cấu hình vận hành. Đặt `-Seconds 30` để kéo dài cửa sổ; actual arrival rate/producer lateness luôn phải được xem cùng throughput.

- [Kết quả so sánh và phép tính](./order-dispatcher-parallel-evidence-2026-10-16/comparison.json).
- [Legacy summary](./order-dispatcher-parallel-evidence-2026-10-16/legacy/summary.json), [parallel summary](./order-dispatcher-parallel-evidence-2026-10-16/parallel/summary.json).
- [Legacy verification](./order-dispatcher-parallel-evidence-2026-10-16/legacy/verification.txt), [parallel verification](./order-dispatcher-parallel-evidence-2026-10-16/parallel/verification.txt).
- [Evidence inventory](./order-dispatcher-parallel-evidence-2026-10-16/README.md), gồm raw CSV nén, metadata, SHA-256, Docker samples, Maven test/build logs và diff production.

```text
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-parallel-evidence-2026-10-16/legacy
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-parallel-evidence-2026-10-16/parallel
python loadtest/order-dispatch-bench/compare.py docs/performance/order-dispatcher-parallel-evidence-2026-10-16/legacy docs/performance/order-dispatcher-parallel-evidence-2026-10-16/parallel
```
