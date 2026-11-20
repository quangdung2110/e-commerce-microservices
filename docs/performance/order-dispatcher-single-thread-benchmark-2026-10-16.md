# Benchmark Order workflow dispatcher một thread — 2026-10-16

> Sau [ADR-028](../order/decisions/ADR-028-parallel-workflow-starts-and-batched-ack.md), implementation production
> đã đổi sang tám worker và batch ACK. Tái chạy baseline cũ bằng `-Variant legacy`; runner mặc định
> hiện đo `-Variant parallel`. Fixture legacy nằm trong test scope, các số liệu lịch sử bên dưới được giữ nguyên.

## Kết quả chính

Dispatcher hiện tại đạt **53,73–55,33 workflow start/giây** trong ba cửa sổ luôn có backlog của lượt baseline; median **55,07 start/giây**.
Lượt chạy độc lập trước đó đạt **53,80–54,47 start/giây**, median **53,87**. Đây là số đo của một dispatcher instance trên môi trường local bên dưới, không phải trần cố định mỗi pod production.

- Tải đều **50 yêu cầu/giây**: ACK **49,73/giây**, latency từ commit outbox tới ACK **p95 129 ms**, còn 4 row đang chờ tại giây 15 và xử lý hết sau khoảng 79 ms nữa.
- Tải đều **70 yêu cầu/giây**: ACK **56,87/giây**, còn **196 row** đã commit chưa ACK tại giây 15; latency **p95 3,33 giây**.
- Tải đều **100 yêu cầu/giây**: ACK **55,47/giây**, còn **667 row** đã commit chưa ACK tại giây 15; latency **p95 11,07 giây**, **p99 11,61 giây**.
- Một lời gọi gateway start mất trung bình khoảng **14 ms**, cộng khoảng **4 ms** cho transaction đánh dấu mỗi row. Một thread chờ tuần tự hai bước này nên số đo quanh 55 start/giây có thể giải thích được từ chi phí từng bước.
- Hai lượt chạy có tổng **24.000 thao tác được đo**, tất cả được ACK và ghi trạng thái terminal sau khi drain; **0 row retry ở dispatcher, 0 row bỏ sót**, mỗi row có `attempts=1`. Không tính 100 start chuẩn bị message target và 500 start warmup của mỗi lượt vào số này.

**Bằng chứng đã có:** khâu dispatch tuần tự tạo hàng đợi khi tải vào 70–100/s trong môi trường này. **Chưa đo:** throughput toàn checkout, latency tới `PAYMENT_READY`, khả năng của dispatcher nhiều thread hoặc dung lượng production.

## 1. Môi trường và code được đo

| Thành phần | Cấu hình thực tế |
|---|---|
| Thời gian | 2026-10-16, khoảng 15:34–15:44, Asia/Bangkok; timestamp trong evidence dùng UTC |
| Git HEAD | `0b71bdcdc23ef9bf6fda2ad8f70c06fa82eda3a3`; working tree có thay đổi khác, lưu inventory và SHA-256 của các file trực tiếp được đo |
| JVM chạy dispatcher | Windows 11, Eclipse Adoptium Java `21.0.12.1+1-LTS`, heap `-Xms256m -Xmx1024m`, 16 processor được JVM nhìn thấy |
| Docker Desktop | 16 CPU, RAM 15.969.443.840 byte, khoảng 14,87 GiB; workload và bộ sinh tải cùng máy |
| Order PostgreSQL | `postgres:16-alpine`, thực tế PostgreSQL **16.15**, database/volume riêng |
| Temporal | Server `temporalio/auto-setup:1.25.2`, SDK **1.25.1**, namespace `default`, một server local |
| Temporal persistence | Một PostgreSQL 16.15 riêng với Order DB, volume riêng; start/signal được persist thật |
| Độ bền Order DB | `fsync=on`, `synchronous_commit=on`, `full_page_writes=on`, `shared_buffers=128MB` |
| Dispatcher | **1 thread**, batch **50**, lease **30 giây**, idle poll **200 ms**, một ACK transaction mỗi row |
| Connection pool | Hikari tối đa **24**, minimum idle **4**, `READ_COMMITTED`, connection timeout 3 giây |
| Workload khác | Chỉ `api-gateway` của stack dùng chung đang chạy; không khởi động hoặc dừng service của stack đó |

Đo trực tiếp các class production:

- [WorkflowDispatcher](../../java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java): dùng chính lifecycle `start()` và vòng lặp một thread, không viết lại vòng dispatch trong benchmark.
- [WorkflowOutboxStore](../../java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java): dùng SQL claim/ACK thật, schema và index từ Flyway migrations của Order.
- [TemporalWorkflowGateway](../../java/services/order-service/src/main/java/com/ecommerce/order/worker/TemporalWorkflowGateway.java): dùng `WorkflowStub.start` / `signalWithStart` và reuse policy hiện tại.

Không thay đổi code production để chạy benchmark. Harness kế thừa store/transaction template và bọc gateway để ghi timestamp, gọi nguyên implementation bên trong. Phần mock duy nhất là closed-target resolver không được sử dụng trong các ca thành công; không mock DB hay Temporal RPC.

## 2. Phạm vi và định nghĩa latency

Benchmark chỉ đo đường **outbox → dispatcher → Temporal ACK → Order DB ACK**. Có Temporal server và persistence thật; không dùng `TestWorkflowEnvironment` hay độ trễ RPC giả lập.

Không chạy business worker: Temporal nhận và persist yêu cầu, workflow ở trạng thái pending. Nhờ đó không trộn chi phí Inventory, Promotion, Payment và các activity vào baseline dispatcher. JVM cũng không khởi động toàn bộ Spring application, HTTP server, Kafka hay Redis.

| Metric | Điểm bắt đầu → kết thúc |
|---|---|
| Queue latency (`queue_to_rpc_ms`) | Enqueue transaction commit → bắt đầu gọi gateway; gồm chờ trong outbox và chờ tới lượt trong batch đã claim |
| Claim-to-RPC (`claim_to_rpc_ms`) | Claim transaction commit → bắt đầu gọi gateway; chỉ phần chờ sau khi đã claim |
| Gateway latency (`gateway_ms`) | Bắt đầu → kết thúc lời gọi gateway production; gồm serialization, SDK/transport, Temporal xử lý/persist và SDK retry nếu có |
| ACK transaction (`transaction_ms.ack_start`) | Bắt đầu → kết thúc transaction cập nhật `STARTED`, gồm lấy connection, SQL và commit |
| Dispatch latency (`commit_to_ack_ms`) | Enqueue transaction commit → ACK transaction commit; **đây là latency trong các bảng burst/tải đều bên dưới** |
| Intended-arrival latency | Thời điểm dự kiến phát yêu cầu → ACK; tính thêm độ trễ producer và enqueue DB để phát hiện producer không tạo đủ tải |

Timestamp dùng `System.nanoTime()`. Mốc commit lấy trong callback phía client sau khi JDBC commit trả về, không phải timestamp WAL nội bộ server. Percentile dùng nearest rank. Thời gian transaction được xuất riêng để kiểm tra lại.

Throughput là **số thao tác được ACK trong cửa sổ / độ dài cửa sổ**. Với start-only, đây là start/s; với mixed, đơn vị là tổng start + message/s. Không cộng số ACK sau cửa sổ vào throughput, nhưng vẫn chờ xử lý hết để tính latency của toàn cohort và kiểm tra mất row.

## 3. Phương pháp

Mỗi lượt dựng project Compose mới với network và volumes mới. Warmup 500 start trước khi đo. Giữa các kịch bản, dừng và chờ dispatcher thread kết thúc rồi truncate hai bảng outbox của **database benchmark**; Temporal server/JVM tiếp tục chạy trong cùng lượt.

1. **Burst 50 / 500:** commit cả nhóm vào outbox trước, bật dispatcher, đo tới ACK cuối. Không tính thời gian seed vào thời gian drain.
2. **Saturated start-only, ba lần:** có sẵn 1.000 row, producer bổ sung mỗi nhóm 100 khi outstanding xuống dưới 1.000. Đo ACK trong **15 giây**, sau đó dừng producer và drain. Các transaction bổ sung đang chạy có thể commit ngay sau cửa sổ.
3. **Tải đều:** phát theo lịch tuyệt đối trong **15 giây**, không đợi dispatch trước khi phát yêu cầu kế tiếp. Các mức 50/s, 100/s và khoảng 70%/130% median saturated làm tròn thành **40/s, 70/s**. Enqueue từng row bằng transaction riêng, nudge dispatcher sau commit như đường production.
4. **Mixed 50/50:** preload và bổ sung 50% start, 50% message. Message dùng `SignalWithStart` vào 100 workflow pending đã được chuẩn bị ngoài phép đo, ID message riêng; payload benchmark `{}`. Đo 15 giây, rồi drain.

Các ca saturated dùng để đo tốc độ phục vụ khi luôn có việc. Latency khoảng 20 giây của backlog chủ động 1.000 row không đại diện cho latency khi hệ thống rảnh. Các ca tải đều mới cho thấy tốc độ tích tụ hàng đợi từ trạng thái trống.

## 4. Burst: hàng đợi ảnh hưởng từng yêu cầu như thế nào?

Số liệu lượt baseline:

| Row đã commit sẵn | Thời gian xử lý hết | Throughput | Latency p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|
| 50 | 1,002 s | 49,91 start/s | 492 ms | 953 ms | 1.003 ms |
| 500 | 9,561 s | 52,30 start/s | 4.828 ms | 9.023 ms | 9.476 ms |

Một RPC không mất 9 giây: row cuối của nhóm 500 phải đợi hàng trăm row trước được gửi và ACK. Lượt pilot cho thời gian drain 1,023 s / 10,220 s, cùng hiện tượng.

## 5. Throughput khi luôn có backlog

Mỗi cửa sổ dài đúng 15 giây; transaction latency và gateway latency trong bảng chỉ lấy mẫu thuộc cửa sổ đó.

| Lần baseline | Start đã ACK trong cửa sổ | Start/s | Gateway mean | Gateway p95 | ACK transaction mean |
|---|---:|---:|---:|---:|---:|
| 1 | 806 | 53,73 | 14,18 ms | 17,64 ms | 4,07 ms |
| 2 | 826 | 55,07 | 13,94 ms | 17,56 ms | 3,93 ms |
| 3 | 830 | 55,33 | 13,83 ms | 17,04 ms | 3,92 ms |

Lần 2: claim một nhóm start mất trung bình 8,15 ms; kiểm tra nhóm message rỗng mất 2,54 ms mỗi lượt. Phân bổ cho khoảng 50 row mỗi batch:

```text
Chi phí mỗi row ≈ 13,94 ms gateway
                 + 3,93 ms ACK transaction
                 + (8,15 + 2,54) / 50 ms claim
                 + chi phí vòng lặp khác
               ≈ 18,2 ms

Throughput ≈ 1.000 / 18,2 ≈ 55 start/s
```

Đây là cách diễn giải từ số đo, không phải mô hình năng lực cố định của Temporal. Transaction ACK chiếm khoảng 22% ngân sách tuần tự trong lần này; phần gateway chiếm phần lớn còn lại. Chưa thể suy ra mức tăng khi batch ACK hoặc thêm thread mà chưa đo phiên bản đó.

Lượt pilot độc lập: **53,80 / 54,47 / 53,87 start/s**. Sáu cửa sổ của hai lượt nằm trong khoảng **53,73–55,33 start/s**. Throughput ở một số cửa sổ tải đều đạt 55–57/s; cửa sổ ngắn và chi phí theo từng thời điểm tạo dao động, nên 55/s là baseline gần đúng, không phải hard limit.

## 6. Tải vào tăng: throughput giữ nguyên, latency và backlog tăng

Lượt baseline; latency tính trên toàn bộ row của từng cohort, kể cả ACK trong giai đoạn drain:

| Tải dự kiến | Tốc độ commit thực tế trong 15 s | ACK/s trong 15 s | Latency p50 | p95 | p99 | Backlog tại giây 15 |
|---:|---:|---:|---:|---:|---:|---:|
| 40/s | 40,00/s | 39,93/s | 34 ms | 83 ms | 151 ms | 1 |
| 50/s | 50,00/s | 49,73/s | 75 ms | 129 ms | 144 ms | 4 |
| 70/s | 69,93/s | 56,87/s | 1.776 ms | 3.334 ms | 3.509 ms | 196 |
| 100/s | 99,93/s | 55,47/s | 6.117 ms | 11.066 ms | 11.611 ms | 667 |

Backlog chỉ đếm row **đã commit trước cuối cửa sổ** nhưng chưa ACK. Ở 70/s và 100/s, một row cuối commit ngay sau giây 15 nên tổng cohort trừ ACK trong cửa sổ lớn hơn backlog một row. Toàn bộ cohort vẫn được drain và kiểm chứng.

- 40–50/s: backlog còn lại tại mốc kết thúc nhỏ, drain thêm khoảng **20 ms / 79 ms**. Trong phép đo ngắn này chưa thấy tích tụ đáng kể.
- 70/s: dừng bơm tải vẫn cần thêm **3,54 giây** để xử lý hết cohort.
- 100/s: cần thêm **11,71 giây** để xử lý hết cohort. Riêng dispatch đã dùng phần lớn budget chờ checkpoint 12 giây mặc định; phép đo này không chạy saga/HTTP nên không đưa ra tỷ lệ `503` thực tế.

Producer không phải nguyên nhân khiến tải 100/s chỉ được phục vụ 55/s: producer hoàn tất lịch 15 giây trong **15,003 giây**, enqueue lateness p95 **17,90 ms**; intended-arrival-to-ACK p95 vẫn **11,071 giây**, gần với commit-to-ACK **11,066 giây**. Cả phần enqueue bị trễ cũng được ghi lại, không âm thầm giảm tải theo tốc độ ACK.

Pilot cho kết quả tương tự: tải 50/s p95 **115 ms**; 70/s p95 **3,39 giây**, backlog **207**; 100/s p95 **10,97 giây**, backlog **665**.

## 7. Start và message dùng chung một thread

| Lượt mixed | Tổng ACK/s | Start/s | Message/s |
|---|---:|---:|---:|
| Pilot | 56,67 | 30,00 | 26,67 |
| Baseline | 51,27 | 26,67 | 24,60 |

Tỷ lệ đầu vào là 50/50; số ACK trong cửa sổ có thể lệch vì dispatcher xử lý nhóm start trước nhóm message và cửa sổ có thể kết thúc giữa nhóm. Sau drain, tất cả row của cả hai loại đã được ghi terminal.

Trong baseline mixed, gateway start mean **14,93 ms**, gateway message mean **14,89 ms**. Message sử dụng chính năng lực của thread dispatch; không thể vừa duy trì khoảng 55 start/s vừa có một lượng message tương đương trên thread này. Hai lượt mixed dao động hơn start-only; payload nhỏ và workflow pending cũng chưa đại diện đủ workload message production.

## 8. Kiểm chứng và giới hạn

Hai lần chạy benchmark đều Maven/JUnit **PASS**. Các assertion kiểm tra sau mỗi kịch bản:

- Chỉ thread `order-workflow-dispatcher` gọi gateway; tối đa **1** gateway call đang chạy.
- Số row đầu vào = số ACK sau drain = số row trong DB; mỗi row đúng trạng thái `STARTED` / `DELIVERED`, `attempts=1`.
- Timestamp theo thứ tự commit → claim → gọi gateway → gateway trả kết quả → ACK commit.

[Verifier Python](../../loadtest/order-dispatch-bench/analyze.py) chạy độc lập trên file CSV nén của baseline và kiểm tra lại số lượng, ID duy nhất, kết quả RPC, throughput theo đúng biên cửa sổ, backlog, p50/p95/p99/mean/max của latency và transaction, cùng intended-arrival latency. Kết quả: **11.950 thao tác / 10 kịch bản khớp hoàn toàn**. Pilot có **12.050 thao tác**; verifier xác nhận latency toàn cohort, còn bản export pilot chưa có biên cửa sổ/CSV transaction để kiểm chứng độc lập hai phần đó. Báo cáo dùng baseline làm số liệu chính.

SHA-256 của dispatcher, store, gateway và benchmark harness được đối chiếu lại sau chạy, không đổi. Containers/volumes của benchmark đã được xoá; inventory trước/sau của stack dùng chung đều chỉ có `api-gateway`.

Giới hạn khi sử dụng kết quả:

- Một máy Windows/Docker Desktop, các kết nối JVM tới DB/Temporal đi qua port forwarding loopback. Không phải network/pod production; disk, RTT và cấu hình server khác sẽ đổi throughput.
- Chỉ có một dispatcher và không có business worker/HTTP/dependency cạnh tranh tài nguyên. Không suy ra checkout RPS hoặc latency `PAYMENT_READY` từ start/s.
- Cửa sổ duy trì 15 giây, ba lần trong mỗi JVM; chưa phải soak test, chưa chứng minh 50/s an toàn trong nhiều giờ hoặc dưới biến động provider.
- Outbox nhỏ, có reset giữa ca; chưa đo chi phí khi bảng giữ hàng triệu row. Message dùng payload `{}` và workflow pending, không có chi phí xử lý saga/history thực tế.
- Instrumentation có chi phí ghi timestamp/samples trong RAM, chưa đo riêng overhead. Sau cửa sổ mới xuất CSV/JSON; sampler Docker chạy khoảng mỗi 5 giây thực tế (lệnh lấy mẫu cộng thời gian nghỉ).
- Không inject lỗi/timeout quá lease 30 giây và không chạy nhiều dispatcher; kết quả 0 retry không chứng minh an toàn stale-owner khi lease hết hạn.
- Không chạy bản song song hoặc batch ACK trong task này; baseline phục vụ đối chiếu khi triển khai và benchmark các thay đổi đó.

## 9. Chạy lại và evidence

```powershell
./scripts/ci/order-dispatch-bench.ps1 -Variant legacy -Seconds 15
# Có thể kéo dài cửa sổ:
./scripts/ci/order-dispatch-bench.ps1 -Variant legacy -Seconds 30
```

Cần Docker Desktop, Java 21 và Maven 3.9; runner hỗ trợ `-JavaHome`, `-Maven`, cũng tự tìm các bản đã có trong `.tools` của workspace này. Hướng dẫn chi tiết ở [benchmark README](../../loadtest/order-dispatch-bench/README.md).

- [Harness](../../java/services/order-service/src/test/java/com/ecommerce/order/benchmark/WorkflowDispatcherBenchmarkTest.java), chỉ chạy khi `ORDER_DISPATCH_BENCH=true`.
- [Compose cô lập](../../loadtest/order-dispatch-bench/compose.yaml) và [runner](../../scripts/ci/order-dispatch-bench.ps1).
- [Baseline summary](./order-dispatcher-single-thread-evidence-2026-10-16/baseline/summary.json), [environment](./order-dispatcher-single-thread-evidence-2026-10-16/baseline/environment.json), [verification](./order-dispatcher-single-thread-evidence-2026-10-16/baseline/verification.txt).
- [Pilot summary](./order-dispatcher-single-thread-evidence-2026-10-16/pilot/summary.json) và [evidence inventory](./order-dispatcher-single-thread-evidence-2026-10-16/README.md).

Các file `*.csv.gz` chứa timestamp từng row và transaction, lưu cùng summary trong evidence. Có thể kiểm tra lại không cần dựng DB/Temporal:

```text
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-single-thread-evidence-2026-10-16/baseline
```
