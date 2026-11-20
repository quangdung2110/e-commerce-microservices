---
doc_id: order-adr-029
service: order
status: accepted
date: 2026-10-20
decision_owners:
  - Order Service team
refines:
  - ADR-015
  - ADR-028
supersedes:
  - ADR-028 mục 6 (message tuần tự trên coordinator)
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java
  - java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java
  - java/services/order-service/src/test/java/com/ecommerce/order/worker/WorkflowMessageLanesTest.java
  - java/services/order-service/src/test/java/com/ecommerce/order/integration/WorkflowDispatchStoreIT.java
  - docs/performance/order-dispatcher-message-lanes-benchmark-2026-10-20.md
---

# ADR-029 — Message workflow song song theo lane từng target, ACK theo lô có fencing

> Cập nhật 21/10/2026: [ADR-030](./ADR-030-bounded-rolling-workflow-dispatch.md) thay giới hạn
> chờ hết lượt bằng rolling claim có giới hạn và exclusion cho work đang theo dõi/backoff.
> Các số benchmark dưới đây giữ nguyên phạm vi implementation ADR-029 lúc được đo.

## Bối cảnh

[ADR-028](./ADR-028-parallel-workflow-starts-and-batched-ack.md) đưa start Temporal lên 8 worker và
ACK theo lô (khoảng 54 → 178 start/s). Message `SignalWithStart` vẫn giữ mô hình cũ (mục 6 của ADR-028):
coordinator claim một nhóm message **sau khi** nhóm start xong, rồi gửi từng message và ghi một
transaction ACK cho từng row. Kịch bản mixed 50% start / 50% message chỉ đạt **86,67 ACK/s**, tức khoảng
43 message/s. Một đơn thành công cần một start và một message `PAYMENT_RESULT`, nên khâu này giới hạn
khoảng 43 đơn/s trên một replica Order. Đây là trần thấp nhất đo được trên đường mua hàng, thấp hơn
Inventory SKU nóng sau ADR-034 (khoảng 241 đơn/s).

Các vấn đề còn lại:

- Message chặn lượt claim start kế tiếp, và nhóm start chặn message.
- `messageDelivered`/`messageRetry`/`messageQuarantined` cập nhật theo `message_id`, không kiểm tra owner
  hay claim generation. Kết quả muộn của owner cũ có thể ghi đè row mà owner khác (hoặc chính pod đó)
  đã reclaim.
- Message đã claim nhưng chưa ACK không được gia hạn lease.

## Quyết định

1. **Một pool RPC chung, cùng giới hạn.** Start và message dùng chung bounded executor của ADR-028:
   `dispatch-threads` (mặc định 8) platform thread, queue giới hạn bằng số thread. Tổng RPC đồng thời tới
   Temporal trên mỗi instance không đổi và không có cấu hình mới. Thread đổi tên từ `order-workflow-start-N`
   thành `order-workflow-rpc-N`. Worker RPC chỉ dựng message/input và gọi gateway; nó không mở hoặc giữ
   JDBC connection hay transaction.
2. **Claim cả hai hàng đợi trong một transaction, lập lịch luân phiên.** Đầu mỗi lượt, coordinator claim tối
   đa `dispatch-batch-size` start và tối đa ngần ấy message trong cùng một transaction (SQL claim không đổi).
   Khi có slot trống, coordinator luân phiên một start rồi một message. Loại nào hết việc thì loại kia dùng slot.
   Message không còn phải chờ cả nhóm start, và ngược lại.
3. **Lane theo `target_workflow_id`.** Mỗi target workflow có tối đa **một message đang bay** trên instance.
   Message của cùng target được gửi theo `created_at`, hoà thì theo `message_id`. Coordinator tự sắp xếp vì
   `UPDATE ... RETURNING` không có thứ tự xác định. Các target khác nhau chạy song song và luân phiên
   round-robin. Message kế tiếp của lane chỉ được gửi khi message trước đã có kết quả.
4. **Head phải retry thì cả lane chờ theo.** Nếu message đầu lane kết thúc ở `RETRY` (Temporal lỗi,
   resolver lỗi), các message sau nó trong lượt này **không được gửi**. Chúng được trả về `RETRY` với cùng
   backoff và `last_error = Deferred behind message <id>`, nên lần claim sau có head và successor đến hạn
   cùng lúc theo `created_at`. Counter `order.workflow.message{result=DEFERRED}` đếm các row này.
5. **Closed target vẫn xử lý trên coordinator.** `CLOSED_TARGET` cần DB (`ClosedTargetResolver` đọc Order,
   có thể `markPaid` hoặc reroute trong transaction riêng), nên chạy trên coordinator như trước. Đây là ca hiếm.
   Semantics ADR-015 giữ nguyên: bằng chứng đủ thì `DELIVERED` với `ALREADY_APPLIED|STALE_NOOP|REROUTED`,
   mơ hồ thì `QUARANTINED`, resolver lỗi thì `RETRY` (và defer cả lane). Lane tiếp tục sau kết quả terminal.
6. **ACK message theo lô có fencing.** `WorkflowOutboxStore.completeMessages` ghi `DELIVERED`, `RETRY` và
   `QUARANTINED` trong **một** `UPDATE ... FROM (VALUES ...) RETURNING`. Câu lệnh chỉ cập nhật khi `message_id`,
   `status = IN_FLIGHT`, `lease_owner` và `attempts` cùng khớp, như `completeDispatches`. Mỗi row giữ
   outcome, replacement, lỗi và backoff riêng. Kết quả stale trả về ít ID hơn và được đếm metric, không ghi
   đè claim mới. Start và message gom chung ngưỡng của ADR-028 (50 kết quả hoặc 20 ms) và flush trong
   **cùng một transaction**, mỗi bảng một câu lệnh. Các hàm ACK message không fencing bị xoá khỏi store production.
7. **Gia hạn lease cho message.** Mỗi `lease/3`, coordinator renew mọi start và message còn thuộc lượt và
   chưa ACK, trong một transaction, cùng fencing. Message chưa gửi mà mất ownership bị bỏ khỏi lane.
   RPC đã gửi có thể trả muộn nhưng ACK của nó bị fencing chặn.
8. **Lifecycle như ADR-028.** `stop()` ngắt coordinator và pool, chờ tối đa 5 giây mỗi phần. Kết quả đã thu
   được flush. RPC chưa có kết quả xác định và message chưa gửi giữ lease, sau đó được claim lại.

### Không thay đổi

Schema/migration, SQL claim, contract workflow/message, `WorkflowMessage`, signal name, Workflow ID reuse policy,
`ClosedTargetResolver`, dedupe theo `message_id` trong workflow và cấu hình `order.workers.*` đều giữ nguyên.
At-least-once vẫn như ADR-015: RPC thành công nhưng ACK lỗi hoặc pod chết thì row được gửi lại sau lease,
và workflow dedupe theo `message_id`.

## Hệ quả và giới hạn

- **Thứ tự chỉ trong một lượt claim của một instance.** Hai instance có thể claim message khác nhau của cùng
  target và gửi song song, như trước ADR-029. Retry của owner khác cũng không có thứ tự toàn cục. Correctness
  vẫn dựa vào dedupe `message_id` và revision high-watermark (ADR-015); lane giảm out-of-order và tránh
  nhiều signal đồng thời vào cùng một execution, không phải bảo đảm thứ tự toàn hệ thống.
- **Vẫn theo lượt.** Lượt claim sau vẫn chờ RPC chậm nhất của lượt hiện tại, giờ là của cả hai loại. Lease
  renewal ngăn RPC chậm làm mất ownership của phần còn lại. Đây chưa phải scheduler liên tục.
- **Target nóng bị tuần tự hoá.** Nhiều message cho một target chỉ đi được một message mỗi round-trip.
  Theo nghiệp vụ Order, một target thường chỉ có vài message.
- **Defer làm tăng `attempts`.** Successor bị defer đã được claim nên claim generation tăng; backoff về sau
  của chính row đó lớn hơn một chút.
- **Resolver chặn coordinator khi chạy.** Trong lúc đó RPC khác vẫn chạy trên worker, nhưng không có slot mới,
  ACK hay renew. Ca này hiếm và đã như vậy trước ADR-029.
- **Mixed chia chung 8 slot.** Trần start-only không đổi. Trần mixed (start + message) tiến gần trần
  start-only thay vì bị chặn bởi message tuần tự.

## Cấu hình và quan sát

Không thêm property. `order.workers.dispatch-threads`, `dispatch-ack-batch-size`, `dispatch-ack-interval`,
`dispatch-batch-size` và `lease` (ADR-028) nay áp dụng cho cả start và message.

- `order.workflow.dispatch.active`: worker RPC đang chạy, gồm cả start và message.
- `order.workflow.message{type,result}`: `result` là `SIGNALLED`, `CLOSED_TARGET`, `RETRY` hoặc `DEFERRED` (mới).
- `order.workflow.message.ack.batch` (mới): distribution summary số kết quả message trong mỗi ACK transaction.
- `order.workflow.message.ack.stale` (mới): kết quả message không còn khớp claim generation.
- `order.workflow.dispatch.ack.batch` / `.ack.stale` giữ nghĩa cho start.

`DEFERRED` tăng đều cho một target thường đi kèm head bị retry lặp lại: xem `last_error` của head và tuổi
outbox. `ack.stale` khác 0 nghĩa là có reclaim, thường do coordinator dừng lâu hơn lease.

## Kiểm chứng

- `WorkflowMessageLanesTest` (8 test): mỗi target tối đa một message đang bay và giữ `created_at` khi claim trả
  về lộn xộn; start và message chia 8 slot, luân phiên 4/4 và không có transaction trong RPC; ACK message theo lô,
  một transaction chung với start, stale được đếm; head `RETRY` defer successor không gửi; closed target giải
  quyết trên coordinator và lane tiếp tục; resolver mơ hồ/lỗi thành `QUARANTINED`/`RETRY`; renewal message và
  bỏ row mất ownership; shutdown ngắt worker, không ACK kết quả chưa biết.
- `WorkflowDispatchStoreIT` (PostgreSQL thật, thêm 6 test): ACK lô `DELIVERED`/`RETRY`/`QUARANTINED`; owner cũ
  không ACK/renew được claim mới, cả khác owner lẫn cùng pod khác generation; rollback batch rồi reclaim; renewal
  chỉ cho generation hiện tại; hai dispatcher thật trên cùng DB giao mỗi message đúng một lần, không chồng lane
  trong cùng owner, thứ tự `created_at` theo target, và reclaim message của pod đã chết, ACK muộn của pod đó bị chặn.
- Order suite: **124 test, 0 failure/error, 1 benchmark opt-in skip**.
- Benchmark ([báo cáo](../../performance/order-dispatcher-message-lanes-benchmark-2026-10-20.md)): ADR-028 và ADR-029 chạy
  xen kẽ từng cửa sổ (ABBA) trong cùng JVM/Temporal/DB, 8 cặp. Mixed 50/50 bão hoà **104,30 → 249,94 ACK/s**
  (median theo cặp 2,47 lần; message khoảng 50 → 125/s), start-only không đổi (221–223/s, tỷ lệ theo cặp 0,97–1,02),
  ACK transaction/thao tác **0,58 → 0,124**. Tải mở mixed 160/s: ADR-028 bão hoà khoảng 106–110/s với p95 6–7 giây,
  ADR-029 theo kịp với p95 khoảng 160 ms. Trần dispatch suy ra khoảng **50 → 125 đơn/s** mỗi replica
  (một start + một `PAYMENT_RESULT` mỗi đơn); vẫn dưới Inventory SKU nóng sau ADR-034 (khoảng 241 đơn/s).
