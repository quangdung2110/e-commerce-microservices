---
doc_id: order-adr-028
service: order
status: accepted
date: 2026-10-16
decision_owners:
  - Order Service team
refines:
  - ADR-015
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java
  - java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java
  - java/services/order-service/src/test/java/com/ecommerce/order/worker/WorkflowDispatcherTest.java
  - java/services/order-service/src/test/java/com/ecommerce/order/integration/WorkflowDispatchStoreIT.java
---

# ADR-028 — Tám worker start Temporal và ACK theo lô có claim fencing

## Bối cảnh

[Baseline một thread](../../performance/order-dispatcher-single-thread-benchmark-2026-10-16.md)
đo khoảng 54–55 start/s trên môi trường local: khoảng 14 ms gateway và 4 ms ACK transaction mỗi row.
Tải vào 100/s tạo backlog và p95 commit-outbox-to-ACK khoảng 11 giây. Đây là số đo riêng dispatch,
không phải throughput checkout end-to-end hay trần production.

Claim SQL đã có `SKIP LOCKED` và lease nhưng row lock chỉ giữ tới commit. ACK theo ID không kiểm tra
phiên claim cho phép kết quả cũ ghi đè row sau khi một pod khác, hoặc chính pod đó, reclaim.

## Quyết định

1. **Tối đa 8 start RPC đồng thời/instance theo mặc định.** Coordinator claim tối đa 50 row một lượt,
   chỉ submit khi số future chưa thu kết quả dưới giới hạn thread. Executor có 8 platform thread và
   queue giới hạn 8; danh sách đã claim cũng giới hạn bằng batch size. Gateway tạo stub mới cho mỗi
   lời gọi và dùng chung Temporal client. RPC worker không mở/giữ JDBC transaction hoặc connection.
2. **Ghi kết quả start theo lô.** Coordinator thu kết quả theo thứ tự hoàn thành, flush khi đủ 50 kết quả,
   đã chờ 20 ms từ kết quả đầu đang gom, hoặc toàn bộ nhóm start đã xong. Một RPC chậm không giữ
   các kết quả đã hoàn thành chờ hết nhóm. 20 ms là thời gian gom chủ động, không là hard SLA nếu
   coordinator/DB đang bận. Mỗi flush là một `UPDATE ... FROM (VALUES ...) RETURNING` trong một transaction,
   hỗ trợ cả `STARTED` và `RETRY`, giữ backoff/lỗi riêng từng row. Không đánh dấu trước durable RPC ACK.
3. **Claim fencing trên ACK và renewal.** Chỉ cập nhật khi `dispatch_id`, `status=IN_FLIGHT`, `lease_owner`
   và `attempts` khớp. `attempts` tăng trong mỗi claim, dùng như generation; chỉ owner không đủ cho
   trường hợp cùng instance reclaim. Kết quả stale trả về không có ID và được đếm metric, không ghi
   đè kết quả mới. Không cần migration vì các cột đã tồn tại.
4. **Gia hạn work chưa ACK.** Coordinator renew các row còn thuộc lượt claim mỗi `lease/3`, mặc định
   10 giây. Renewal có cùng fencing. Row chưa gửi mất ownership được bỏ khỏi queue local; RPC đã
   chạy có thể trả về muộn nhưng ACK vẫn bị fencing. Không có transaction giữ mở trong lúc chờ RPC.
5. **At-least-once và recovery giữ nguyên.** RPC thành công nhưng ACK transaction lỗi/process chết
   thì row vẫn được reclaim sau lease. Fixed workflow ID/reuse policy và activity idempotency tiếp tục
   là correctness boundary theo ADR-015/020. Finalization/reconciliation `ALLOW_DUPLICATE` chỉ replay
   frozen commands như trước. Batch rollback không làm mất work chưa ACK.
6. **Message delivery giữ thứ tự xử lý hiện có.** Sau nhóm start, coordinator vẫn claim một nhóm message
   và xử lý `SignalWithStart`, closed-target resolution, ACK/retry từng message như trước. Không đổi
   workflow/message contract hoặc chạy business workflow trên executor này. Vì vậy workload nhiều
   message hoặc một start còn chậm ở cuối nhóm vẫn có thể trì hoãn lượt claim kế tiếp; đây không phải
   scheduler công bằng giữa mọi queue.
7. **Lifecycle có giới hạn.** Start idempotent; stop ngắt coordinator và RPC pool, chờ tối đa 5 giây cho
   mỗi phần. RPC chưa có kết quả xác định giữ lease để replay; kết quả đã thu có thể flush khi bị ngắt.
   Không tạo pool mới khi pool cũ còn đang dừng. Default shutdown không cần drain toàn bộ outbox.

## Cấu hình và quan sát

- `order.workers.dispatch-threads` / `ORDER_DISPATCH_THREADS`: **8**, hợp lệ 1–64.
- `order.workers.dispatch-ack-batch-size` / `ORDER_DISPATCH_ACK_BATCH_SIZE`: **50**, hợp lệ 1–1000.
- `order.workers.dispatch-ack-interval` / `ORDER_DISPATCH_ACK_INTERVAL`: **PT0.02S**, dương và nhỏ hơn lease/3.
- Claim batch giữ **50**, lease giữ **PT30S**; claim batch hợp lệ 1–1000, lease ít nhất 1 giây.
- `order.workflow.dispatch.active`: worker RPC đang hoạt động.
- `order.workflow.dispatch.ack.batch`: distribution summary số kết quả gửi trong mỗi ACK transaction.
- `order.workflow.dispatch.ack.stale`: số kết quả ACK không còn khớp claim generation.
- `order.workflow.dispatch{result=STARTED|RETRY}` tiếp tục đếm kết quả start RPC; không dùng counter này
  thay trạng thái outbox durable khi điều tra commit failure.

Đặt thread về 1 chỉ giảm concurrency của implementation mới; ACK vẫn theo lô và có fencing.
Benchmark implementation cũ dùng fixture test-only riêng, không phải switch runtime.

## Kiểm chứng

`WorkflowDispatcherTest` kiểm tra giới hạn 8 RPC, không giữ transaction trong RPC, ACK gom nhiều row,
flush trước straggler, kết quả lỗi không làm mất row khác, renewal khi RPC chờ và lifecycle shutdown.
`WorkflowDispatchStoreIT` dùng PostgreSQL thật kiểm tra mixed ACK/retry, rollback/reclaim, fencing
giữa hai owner và cùng owner khác generation, renewal và hai consumer `SKIP LOCKED`.
Order suite kiểm tra lifecycle/saga/contract hiện có. Benchmark so sánh dùng cùng compose cô lập,
workload burst/tải đều/saturated/mixed và raw timestamp như baseline.
