# Order dispatcher: message lanes (ADR-029) so với ADR-028

## Kết quả chính

Message `SignalWithStart` nay chạy trên pool RPC chung với start, tối đa một message đang bay mỗi target workflow,
ACK/renew theo lô có fencing và luân phiên với start ([ADR-029](../order/decisions/ADR-029-parallel-workflow-messages-per-target-lanes.md)).
Implementation cũ (ADR-028) được giữ nguyên làm fixture test-only và **chạy xen kẽ từng cửa sổ với implementation mới
trong cùng JVM, cùng Temporal và DB** (ABBA, hai lượt, tổng 8 cặp cửa sổ). Đo ngày 2026-10-20.

| Chỉ số (median của 8 cửa sổ mỗi bên) | ADR-028 | ADR-029 | Thay đổi |
|---|---:|---:|---:|
| Mixed 50% start / 50% message, tổng ACK/s | 104,30 | **249,94** | **2,40 lần**; median theo cặp 2,47 lần |
| Message/s trong mixed (median từng lượt) | 49,5–53,2 | **125,0–127,5** | khoảng 2,4 lần |
| Start-only, start/s | 223,07 | 221,37 | không đổi đo được (tỷ lệ theo cặp 0,97–1,02, median 0,99) |
| Mixed / start-only | 0,45–0,49 | **1,13–1,15** | trần mixed nay ngang (cao hơn) trần start-only |
| ACK transaction / thao tác trong mixed | 0,579–0,580 | **0,124** | **giảm 78,6%** |
| Mọi transaction dispatcher / thao tác trong mixed | 0,600–0,601 | **0,134** | giảm 77,7% |
| Tải vào mixed 160/s: ACK/s | 106,4 / 110,4 | **159,3 / 159,1** | theo kịp tải vào |
| Tải vào mixed 160/s: p95 commit-to-ACK | 7.004 / 6.194 ms | **159 / 162 ms** | giảm khoảng 97% |
| Tải vào mixed 160/s: backlog tại giây 15 | 803 / 743 | **10 / 13** | |
| Tối đa message đồng thời trên một target | 1 | 1 | lane được giữ (kiểm cả từ timestamp thô) |

Mỗi đơn thành công cần một start và một message `PAYMENT_RESULT`, nên trần dispatch Order trên một replica trong
môi trường này tăng từ khoảng **50–53 lên khoảng 125 đơn/s**. Con số vẫn thấp hơn Inventory SKU nóng sau
ADR-034 (khoảng 241 đơn/s), nên Order dispatcher vẫn là trần thấp nhất đo được trên đường mua hàng, nhưng khoảng
cách thu hẹp từ khoảng 4,6 lần xuống khoảng 1,9 lần.

Hai lượt paired có **149.300 thao tác được đo** (73.700 + 75.600). Mọi row terminal sau drain, `attempts = 1`,
**0 retry, 0 row bỏ sót, 0 message `DEFERRED`**. Không tính 100 start chuẩn bị target và 500 start warmup mỗi lượt.

Đây là cải thiện ở **khâu dispatch** (Temporal nhận và persist start/signal), không phải throughput checkout
end-to-end hay latency tới `PAYMENT_READY`, và không phải cam kết năng lực production.

## 1. Thay đổi code

- [WorkflowDispatcher](../../java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java):
  mỗi lượt claim start và message trong **một** transaction; một vòng lập lịch luân phiên start/message trên
  pool 8 thread (`order-workflow-rpc-N`); lane theo `target_workflow_id` giữ `created_at` và tối đa một message
  đang bay; head `RETRY` làm cả lane trả `RETRY` cùng backoff; closed-target resolution vẫn trên coordinator.
- [WorkflowOutboxStore](../../java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java):
  `completeMessages` (một `UPDATE ... FROM (VALUES ...) RETURNING` cho `DELIVERED`/`RETRY`/`QUARANTINED`) và
  `renewMessages`, fencing `message_id + IN_FLIGHT + lease_owner + attempts`. Các hàm ACK message không fencing đã bị xoá.
- Start và message flush chung một ACK transaction (50 kết quả hoặc 20 ms); renew cả hai mỗi lease/3.
- Không đổi schema, SQL claim, contract workflow/message, dedupe `message_id`, cấu hình.

Diff production đầy đủ: [production-change.diff](./order-dispatcher-message-lanes-evidence-2026-10-20/validation/production-change.diff).

## 2. Phương pháp và vì sao dùng đo xen kẽ

| Thành phần | Giá trị |
|---|---|
| Thời gian | 2026-10-20, Asia/Bangkok; paired-1 khoảng 15:13–15:22, paired-2 khoảng 15:31–15:40 |
| JVM | Windows 11, Java `21.0.12.1+1-LTS`, 16 processor, `-Xms256m -Xmx1024m` |
| Docker Desktop | 16 CPU, khoảng 14,87 GiB RAM; bộ sinh tải cùng máy; không có container nào khác chạy |
| Order DB | PostgreSQL 16.15, `fsync`/`synchronous_commit`/`full_page_writes` bật, shared buffers 128 MiB |
| Temporal | Server 1.25.2, SDK 1.25.1, PostgreSQL persistence riêng; không có workflow worker |
| Dispatcher | 8 thread, claim 50/hàng đợi, ACK 50 hoặc 20 ms, lease 30 giây, idle poll 200 ms, Hikari 24/4 |
| Message | `SignalWithStart` payload `{}` vào 100 workflow pending tạo trước (mỗi message chọn target theo hash) |

Profile `paired` (`./scripts/ci/order-dispatch-bench.ps1 -Profile paired`) chạy sau warmup:

- 4 vòng, mỗi vòng: mixed 50/50 của hai variant rồi start-only của hai variant, thứ tự ABBA
  (`adr028, parallel`, rồi `parallel, adr028`, ...). Mỗi cửa sổ 15 giây, luôn giữ khoảng 1.000 row chờ, đo ACK
  trong cửa sổ rồi drain; giữa các cửa sổ dừng dispatcher và reset hai bảng outbox.
- Tải mở mixed 50/50 ở 80, 120, 160 thao tác/s cho từng variant, thứ tự xen kẽ.

**Vì sao không dùng hai lượt riêng như báo cáo ADR-028:** bốn lượt riêng (mỗi variant hai lượt, cách nhau vài phút)
cho kết quả nhiễu lớn. Có những khoảng vài giây mà RPC Temporal mất 1–7 giây trong khi mọi container gần như rảnh.
Hiện tượng này xảy ra ở cả start-only (không đi qua code message), ở cả hai variant, và cả ở cửa sổ đầu tiên trước khi có
traffic mixed nào. Nó làm median start-only một lượt mới tụt còn 84/s và một lượt cũ có cửa sổ mixed chỉ 40/s.
[`rpc_gaps.py`](./order-dispatcher-message-lanes-evidence-2026-10-20/rpc_gaps.py) cho thấy trong các khoảng đó RPC vẫn
đang bay, tức dispatcher không ngồi chờ, mà Temporal/host chậm. Đo xen kẽ trong một JVM làm hai variant chịu cùng
trôi dạt; tỷ lệ theo từng cặp cửa sổ liền nhau là con số chính. Các lượt riêng vẫn được giữ ở mục 7.

Throughput chỉ đếm ACK trong cửa sổ. Latency là commit enqueue tới commit ACK, gồm cả phần drain, percentile nearest-rank.

## 3. Mixed 50/50 khi luôn có việc chờ

| Lượt / vòng | ADR-028 ACK/s | ADR-029 ACK/s | Tỷ lệ |
|---|---:|---:|---:|
| paired-1 / 1 | 97,27 | 251,40 | 2,58 |
| paired-1 / 2 | 108,73 | 133,13 | 1,22 |
| paired-1 / 3 | 107,27 | 248,47 | 2,32 |
| paired-1 / 4 | 106,40 | 252,53 | 2,37 |
| paired-2 / 1 | 95,87 | 248,40 | 2,59 |
| paired-2 / 2 | 106,67 | 275,40 | 2,58 |
| paired-2 / 3 | 102,20 | 261,80 | 2,56 |
| paired-2 / 4 | 98,93 | 234,53 | 2,37 |
| **Median** | **104,30** | **249,94** | **2,47** |

Mỗi bên chia khoảng nửa start nửa message: ADR-028 khoảng 50–54 start/s + 49–53 message/s; ADR-029 khoảng
125–128 mỗi loại. Pool đạt đủ 8 RPC đồng thời ở cả hai variant; ở ADR-028 phần lớn thời gian coordinator gửi message
từng cái một nên 8 slot chỉ được dùng trong lúc gửi nhóm start.

Cửa sổ paired-1/2 của ADR-029 có khoảng 12 giây RPC Temporal chậm tới khoảng 1,1 giây, ACK còn 8–67/s rồi hồi
phục lên 175–263/s ([phân tích](./order-dispatcher-message-lanes-evidence-2026-10-20/paired-1/stall-analysis.txt)).
Trong cả cửa sổ chỉ có một khoảng 0,32 giây không có RPC đang bay (coordinator chờ một ACK transaction cũng đang chậm).
Cửa sổ ADR-028 ngay sau đó không bị. Hiện tượng cùng dạng đã gặp ở cả hai variant trong các lượt riêng (mục 7),
nên báo cáo dùng median và vẫn giữ nguyên cửa sổ này trong số liệu.

Khoảng cách giữa trần mixed và trần start-only đã hết: mixed/start-only khoảng 0,45–0,49 ở ADR-028 và 1,13–1,15 ở
ADR-029. Mixed cao hơn start-only một chút; ở môi trường này một signal vào workflow có sẵn có vẻ rẻ hơn tạo workflow
mới, nhưng benchmark không tách nguyên nhân phía Temporal.

## 4. Start-only

| Lượt / vòng | ADR-028 start/s | ADR-029 start/s | Tỷ lệ |
|---|---:|---:|---:|
| paired-1 / 1–4 | 235,27 · 216,40 · 216,67 · 222,80 | 238,40 · 211,60 · 214,27 · 226,33 | 1,01 · 0,98 · 0,99 · 1,02 |
| paired-2 / 1–4 | 233,33 · 226,60 · 223,33 · 212,33 | 235,67 · 221,80 · 220,93 · 205,40 | 1,01 · 0,98 · 0,99 · 0,97 |
| **Median** | **223,07** | **221,37** | **0,99** |

Đường start không đổi về thuật toán; khác biệt duy nhất là claim hai bảng trong một transaction. Chênh lệch nằm trong
dao động giữa các cặp (−3% tới +2%), không phải regression đo được. Start-only hôm nay cao hơn báo cáo
2026-10-16 (178/s) với cùng code ADR-028, nên chỉ so trong cùng phiên.

## 5. Tải mở mixed 50/50

| Tải vào | Variant | Lượt | Commit/s | ACK/s | Backlog giây 15 | p95 / p99 ms | Message p95 ms |
|---:|---|---|---:|---:|---:|---:|---:|
| 80/s | ADR-028 | 1 / 2 | 80,0 / 80,0 | 79,8 / 79,3 | 3 / 10 | 199 / 265 · 167 / 240 | 206 · 177 |
| 80/s | ADR-029 | 1 / 2 | 80,0 / 79,9 | 79,8 / 79,9 | 3 / 1 | **93 / 124 · 61 / 105** | **95 · 59** |
| 120/s | ADR-028 | 1 / 2 | 119,9 / 119,9 | 99,5 / 106,7 | 306 / 198 | 2.840 / 3.196 · 1.938 / 2.199 | 2.642 · 1.910 |
| 120/s | ADR-029 | 1 / 2 | 120,0 / 119,9 | **119,7 / 119,7** | **4 / 4** | **103 / 196 · 104 / 149** | **105 · 106** |
| 160/s | ADR-028 | 1 / 2 | 159,9 / 159,9 | 106,4 / 110,4 | 803 / 743 | 7.004 / 7.254 · 6.194 / 6.587 | 4.898 · 4.527 |
| 160/s | ADR-029 | 1 / 2 | 159,9 / 160,0 | **159,3 / 159,1** | **10 / 13** | **159 / 230 · 162 / 206** | **167 · 166** |

Producer commit đủ tốc độ dự kiến ở mọi mức (sai lệch dưới 0,1/s), nên khác biệt đến từ dispatcher. ADR-028 bão hoà
quanh 100–110 thao tác/s và tích backlog từ 120/s; ADR-029 theo kịp 160/s với p95 khoảng 160 ms. Ngay cả ở 80/s,
khi cả hai đều theo kịp, p95 giảm khoảng 53–63% vì message không còn chờ sau nhóm start và ACK từng row.
Benchmark chưa đo mức tải mở trên 160/s; ở chế độ bão hoà trần là khoảng 250/s.

## 6. Transaction và ACK

| Trong các cửa sổ mixed | ADR-028 | ADR-029 |
|---|---:|---:|
| ACK transaction / thao tác | 0,579 / 0,580 | **0,124 / 0,124** |
| Mọi transaction dispatcher / thao tác | 0,600 / 0,601 | **0,134 / 0,134** |
| Dạng ACK chính | `ack_message` một row, trung bình 3,2–3,6 ms; `ack_start` theo lô | `ack_start+ack_message` một transaction, trung bình 7,9–9,1 ms |
| Claim | hai transaction (`claim_start`, `claim_message`), mỗi cái 5–8 ms | một transaction `claim_start+claim_message`, trung bình 9,7–12,3 ms |

(Khoảng trị của các cửa sổ không bị nhiễu ở cả hai lượt paired.) Một transaction ACK chung gánh trung bình khoảng
8 kết quả (giới hạn 50, timer 20 ms flush sớm). Gateway mean của message tăng từ khoảng 10–12 ms (gửi tuần tự) lên
khoảng 26–30 ms khi 8 RPC chạy cùng lúc, tương tự start, nên không thể lấy latency một RPC nhân số thread để dự báo trần.

## 7. Các lượt đo riêng (bổ sung)

Trước khi có profile `paired`, mỗi variant được chạy hai lượt riêng bằng profile `mixed`
(3 start-only + 3 mixed + tải mở 80/120/160), thứ tự `adr028, parallel` rồi `parallel, adr028`:

| Lượt | Variant | Start-only median | Mixed median | Ghi chú |
|---|---|---:|---:|---|
| 1 | ADR-028 | 232,20 | 103,80 | ổn định, RPC chậm nhất 274 ms |
| 1 | ADR-029 | 216,07 | 110,13 | RPC Temporal 4–7 s trong 3/6 cửa sổ (kể cả start-only đầu tiên); cửa sổ mixed không bị: **242,07** |
| 2 | ADR-029 | 84,13 | 164,53 | start-only 2 và 3 bị chậm (75, 84); mixed 254,07 / 164,53 / 115,73; tải mở 80/120/160 đều theo kịp, p95 63 / 119 / 234 ms |
| 2 | ADR-028 | 194,00 | 73,33 | mixed 2 chỉ 40,27; tải mở 80/s cũng không theo kịp (58/s) |

Trong các khoảng chậm, phần lớn thời gian là RPC Temporal đang chờ. Chỉ có bốn khoảng 0,3–0,7 giây không có RPC
đang bay (một ở ADR-029 start-only, ba ở ADR-028 mixed), trùng lúc transaction DB của coordinator cũng chậm. Các cửa sổ không bị nhiễu
cho cùng kết luận với mục 3 (mixed ADR-029 khoảng 240–255/s, ADR-028 khoảng 99–112/s). Một lượt paired khác
(`aborted-20261020-152243`) dừng ngay ở warmup: Docker Desktop đứng khoảng 7 phút (docker stats ngừng ghi, Hikari báo
connection bị đóng). Lượt này không có số đo và không được tính.

## 8. Kiểm chứng chức năng

- **Order suite: 124 test, 0 failure/error, 1 benchmark opt-in skip** ([log](./order-dispatcher-message-lanes-evidence-2026-10-20/validation/order-suite.txt)),
  PostgreSQL test mới qua `TEST_DB_URL`. Hai lớp unit dispatcher chạy lại 8 lần liên tiếp đều xanh.
- `WorkflowMessageLanesTest` (8 test mới): per-target serialization và thứ tự `created_at` dù claim trả lộn xộn;
  start/message chia đúng 8 slot (4/4) và không có transaction trong RPC; ACK message theo lô, chung transaction với start,
  stale được đếm; defer lane sau head `RETRY`; closed target trên coordinator; resolver mơ hồ/lỗi; renewal message và
  bỏ row mất ownership; shutdown.
- `WorkflowDispatchStoreIT` (6 test PostgreSQL thật mới): ACK lô ba trạng thái; owner cũ không ACK/renew được (khác owner và
  cùng pod khác generation); rollback rồi reclaim; renewal chỉ generation hiện tại; hai `WorkflowDispatcher` thật trên cùng DB
  giao mỗi message đúng một lần theo thứ tự target, reclaim message của pod đã chết, ACK muộn của pod đó bị chặn.
- Mỗi cửa sổ benchmark tự kiểm: tối đa 8 RPC, tên thread, mọi row terminal với `attempts = 1`, số row khớp DB, tối đa một
  message đang bay trên một target. [`analyze.py`](../../loadtest/order-dispatch-bench/analyze.py) kiểm độc lập từ CSV thô:
  thứ tự timestamp, concurrency, **không có hai RPC cùng target chồng nhau**, throughput theo biên cửa sổ, backlog,
  percentile, số row và batch ACK theo từng loại trong transaction ghép.

## 9. Giới hạn

- Một máy Windows/Docker Desktop, cửa sổ 15 giây, 8 cặp; môi trường có nhiễu thời gian thực đáng kể (mục 2, 7).
  Không soak test, không chạy nhiều replica dispatcher trong benchmark (hai owner chỉ kiểm ở IT).
- Không có business worker, HTTP, Kafka hay dependency; không đo checkout end-to-end, `PAYMENT_READY` hay HTTP `503`.
- Message chỉ vào 100 workflow; mỗi lượt claim 50 message nên có target trùng trong lượt và lane thật sự tuần tự hoá
  một phần. Production có một target mỗi order nên trùng ít hơn; target rất nóng sẽ bị giới hạn bởi một RPC mỗi lần.
- Không ablation: mức tăng là hiệu quả gộp của RPC song song, lập lịch luân phiên và ACK theo lô.
- Không đo đường closed-target (resolver được mock và không được gọi) hay đường lỗi; các đường này nằm trong functional test.

## 10. Chạy lại và dữ liệu

```powershell
./scripts/ci/order-dispatch-bench.ps1 -Profile paired -Seconds 15
```

Chạy script như một tiến trình PowerShell riêng; xem [benchmark README](../../loadtest/order-dispatch-bench/README.md).

```text
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-1
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-2
python loadtest/order-dispatch-bench/compare.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-1
python docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/combine.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20
```

- [Evidence inventory](./order-dispatcher-message-lanes-evidence-2026-10-20/README.md), gồm raw CSV nén, verifier output,
  comparison JSON, số liệu gộp, Docker samples, log Maven/container, Order suite log và diff production.
