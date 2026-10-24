---
doc_id: order-adr-030
service: order
status: accepted
date: 2026-10-21
refines:
  - ADR-028
  - ADR-029
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java
  - java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java
  - java/services/order-service/src/test/java/com/ecommerce/order/worker/WorkflowRollingClaimsTest.java
  - docs/performance/order-dispatcher-rolling-benchmark-2026-10-21.md
---

# ADR-030 — Bổ sung công việc liên tục trong cửa sổ dispatch có giới hạn

## Bối cảnh

ADR-029 chia sẻ 8 RPC slot cho start và message, ACK theo lô và renew có fencing, nhưng
lượt claim sau vẫn đợi RPC cuối của lượt trước. Khi 49/50 công việc đã ACK, một RPC chậm
có thể để 7 slot rảnh trong khi DB còn backlog. Tối ưu này xử lý ranh giới giữa các lô;
không tăng concurrency hoặc bỏ durable outbox.

## Quyết định

1. Một coordinator tiếp tục claim trong lúc RPC trước còn chạy. Mỗi loại có cửa sổ tối đa
   `dispatch-batch-size` row chưa ACK, gồm row chờ gửi, RPC đang chạy và kết quả chờ flush.
   Với mặc định 50/loại, tối đa 100 row; tổng RPC vẫn `dispatch-threads` (8).
2. Khi một cửa sổ còn không quá nửa dung lượng, claim tối đa phần còn trống. Hai loại đủ
   điều kiện được claim chung một transaction; loại chưa cần bổ sung không query DB.
   Claim không lấy đủ số yêu cầu thì đợi `dispatch-interval` trước khi thử lại loại đó.
   Khi chỉ còn RPC chậm, completion poll vẫn thức ít nhất theo interval này để tìm việc mới.
3. Start/message tiếp tục luân phiên. Claim message loại các target coordinator còn theo dõi,
   kể cả kết quả chưa ACK, để một lane không chồng sang lượt claim khác. Trong nhóm mới,
   message cùng target vẫn sort theo `created_at`, rồi `message_id`. Start claim loại các
   dispatch ID còn đang được coordinator theo dõi.
4. Head retry vẫn defer successor đã claim. Target có head retry tiếp tục bị loại khỏi claim
   cho tới hết backoff tính từ ACK transaction trở về. Hold này sống qua idle pass của cùng
   instance. Số target giữ backoff dùng chung budget cửa sổ message, nên RAM không tăng theo
   tổng số lỗi. Khi budget này đầy, chỉ refill message bị dừng tới khi hold hết hạn; start
   vẫn tiến triển. Nếu ACK lỗi/stale, giữ hold bảo thủ thêm lease + backoff từ lúc xử lý lỗi.
5. Renew vẫn mỗi lease/3, ACK vẫn tối đa 50 hoặc 20 ms, với owner + generation fencing.
   Row chưa gửi mà mất ownership được loại khỏi queue. RPC đã gửi hoặc kết quả chưa flush
   vẫn chiếm cửa sổ và tiếp tục bị loại khỏi claim, dù renew báo mất ownership; ACK muộn bị
   fencing từ chối. Như vậy rolling claim không gửi lại chính RPC đang chạy sau lease loss.
6. Shutdown ngắt coordinator/pool. Chỉ kết quả coordinator đã thu trước khi ngắt mới được
   flush; completion bị ngắt hoặc chưa thu giữ lease cho replay. `dispatchOnce()` giờ drain
   theo cửa sổ tới khi không còn RPC có thể chạy, trả tổng số row đã claim; dưới backlog
   liên tục, nó chạy tới khi shutdown hoặc lỗi. Không gọi nó từ request thread.

Không đổi schema, HTTP/event/workflow contract, Temporal SDK/reuse policy, idempotency
hoặc cấu hình. Store thêm overload có exclusion list; caller cũ với ba tham số vẫn claim
như trước, dùng cho fixture baseline và worker/test khác.

## Quan sát và giới hạn

- `order.workflow.dispatch.claimed`: số row coordinator đang theo dõi (không phải tổng backlog DB).
  Không vượt `2 * dispatch-batch-size`; trở về 0 khi drain kết thúc hoặc shutdown.
- `order.workflow.dispatch.active` và ACK batch/stale metrics của ADR-029 giữ nghĩa.
- Giới hạn và lane là theo instance. Instance khác/restart có thể gửi lại hoặc đảo thứ tự;
  stable IDs, workflow dedup và revision guards vẫn bảo vệ correctness. Không thêm global lock.
- Coordinator vẫn thực thi claim/ACK/renew và closed-target resolution đồng bộ. DB/resolver
  chậm vẫn có thể trì hoãn refill; toàn bộ Temporal cùng chậm vẫn có thể chiếm đủ 8 slot.
- Partial/empty queue được poll theo interval, nên công việc mới có thể đợi thêm một interval.
  Claim nhỏ hơn/lặp thường hơn có thể tăng SQL/transaction trên mỗi thao tác; phải đo cả healthy.
- Khi lỗi DB làm pass dừng, các RPC chưa rõ kết quả giữ durable lease cho replay. Gauge local
  reset về 0 không có nghĩa DB đã được ACK. Không có bảo đảm exactly-once transport.

## Kiểm chứng và rollback

`WorkflowRollingClaimsTest`: lô sau tiến triển khi RPC đầu còn chờ; cap/concurrency và không
giữ transaction trong RPC; loại target đang chạy nhưng cho target khác tiến triển; retry
backoff qua idle pass; empty-poll rate; RPC mất lease vẫn chiếm cửa sổ và bị loại khỏi claim.
`WorkflowDispatchStoreIT`: exclusion cho target và dispatch ID, gồm expired lease, cộng
rollback/reclaim/fencing/two-owner tests của ADR-028/029. Giữ toàn bộ lane/shutdown tests cũ.

Benchmark dùng PostgreSQL/Temporal thật, fixture ADR-029 chụp trước thay đổi, 8 slot cả hai
bản, healthy và 1% lời gọi giữ thêm 1/2/3 giây. Số đo, giới hạn và lệnh tái hiện ở
[báo cáo](../../performance/order-dispatcher-rolling-benchmark-2026-10-21.md).

Rollback binary về ADR-029 sau graceful shutdown; row chưa ACK tự replay sau lease. Không có
migration hay keyspace cần đảo. Không chạy fixture test như một Spring component.
