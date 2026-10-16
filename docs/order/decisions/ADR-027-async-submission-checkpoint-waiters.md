---
doc_id: order-adr-027
service: order
status: accepted
date: 2026-10-16
decision_owners:
  - Order Service team
refines:
  - ADR-020
  - ADR-023
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order/service/SubmissionWaiters.java
  - java/services/order-service/src/main/java/com/ecommerce/order/service/SubmissionNotificationListener.java
  - java/services/order-service/src/main/resources/db/migration/V3__submission_checkpoint_notification.sql
  - contracts/http/order/internal/v1/openapi.yaml
---

# ADR-027 — Async HTTP waiters cho checkout checkpoint

## Bối cảnh

Order POST submission cần trả initial Payment client data cho Checkout trong cùng response,
nhưng chỉ chờ tới `PAYMENT_READY`, không chờ capture hay kết thúc workflow. Trước quyết định này,
`awaitCheckpoint` giữ một Tomcat platform thread và `SELECT *` mỗi 100 ms cho từng request.
Saga chậm làm tăng số thread chờ và đọc lặp snapshot JSON. Chưa có load-test đường ghi để kết
luận ngưỡng RPS; Checkout đã có bulkhead Order POST 50/instance và circuit breaker.

## Quyết định

1. **Giữ wire contract.** POST vẫn `201` khi request tạo submission, `200` khi replay thành công,
   terminal business problem `409/422`, hoặc `503` retryable. Không thêm callback Checkout,
   endpoint, integration event hay status enum. Checkout tiếp tục WebFlux HTTP và recovery hiện có.
2. **Durable accept trước async wait.** Canonical snapshot/digest và workflow dispatch cùng một
   transaction. Sau commit, controller trả `DeferredResult<ResponseEntity<Object>>`; HTTP vẫn mở,
   Tomcat trả thread về pool. Registry theo token nằm trong RAM từng instance, hỗ trợ nhiều
   request cùng token với creation flag/deadline/context riêng. Registry không là nguồn sự thật.
3. **Thông báo transaction-safe.** Migration V3 thêm row trigger khi `PREPARING` chuyển sang state
   khác: `PAYMENT_READY`, `RECALCULATION_REQUIRED` hoặc `FAILED`, kể cả nhánh cancellation. Trigger
   gọi `pg_notify('order_submission_changed', checkout_token::text)` trong chính transaction.
   PostgreSQL chỉ giao khi commit; rollback, attachOrder và CAS no-op không phát tín hiệu.
   Payload chỉ có token, không snapshot/PII/secret. Đây là wake-up hint nội bộ DB, không phải
   durable business event và không đưa vào Kafka/outbox.
4. **Một LISTEN session/replica.** `SubmissionNotificationListener` mở JDBC connection chuyên dụng
   bằng `spring.datasource.url/username/password`, ngoài Hikari request/activity pool; commit LISTEN
   trước khi yêu cầu đọc bù. pgJDBC `getNotifications(1000)` chạy trên listener platform thread;
   listener chỉ lookup token và enqueue, không query trạng thái hoặc gọi Payment. Mỗi replica nhận
   thông báo nhưng chỉ xử lý waiter local của mình. URL phải tới PostgreSQL/session pooling;
   transaction pooling không bảo toàn LISTEN. URL cũng phải chứa các tuỳ chọn TLS/schema cần thiết.
5. **Đóng race và phục hồi mất notification.** Đăng ký waiter trước initial checkpoint read để bao
   phủ commit trước hoặc trong lúc đăng ký. Khi listener reconnect, đọc bù sau khi LISTEN commit.
   Mỗi giây có tối đa một sweep task/instance, chia token local thành batch tối đa 256, chỉ query
   token terminal. Không có query nếu không có waiter. Notification trùng/race với query được
   khử bằng trạng thái atomic; sweep và deadline vẫn tiến triển khi listener ngắt kết nối.
6. **Executor có giới hạn.** Mặc định 512 waiter/instance, 16 response platform threads, queue
   tối đa `max-waiters + 1` (một task/waiter và một recovery task). Task đọc projection
   `checkout_token,state,order_id,failure,updated_at`; không đọc snapshot. Khi terminal, task đọc
   Payment client data tươi như trước, không cache secret, rồi `setResult`/`setErrorResult`.
   Spring redispatch về container để serialize/gửi response. Payment chậm không chặn listener;
   khi executor bận mọi request vẫn có timer riêng trên một timer thread. Vượt waiter/queue trả
   `503 ORDER_UNAVAILABLE`, `Retry-After: 2`; submission đã commit vẫn recovery bằng cùng token.
7. **Deadline/context.** Checkpoint timer là `max(0,min(submit-wait, caller còn lại − 1 s))`, mặc định
   12 s. Timer hết trước khi nhận terminal state trả `503 SUBMISSION_IN_PROGRESS`, `Retry-After: 2`.
   Khi bắt đầu dựng terminal response thì bỏ checkpoint timer; toàn response còn được chặn bởi
   caller deadline nếu có, hoặc checkpoint budget + Payment read timeout + connect timeout + 1 s.
   Response Payment quá hạn trả `503 DEPENDENCY_UNAVAILABLE`. Đây không phải cam kết mọi HTTP
   hoàn tất trong đúng 12 s: DB accept diễn ra trước timer, Payment data được lấy sau checkpoint.
   Capture `RequestCorrelation` cùng receivedAt gốc và MDC cho task; restore/clear khi kết thúc.
   Filter ASYNC redispatch dùng context gốc, không tính lại deadline từ đầu hay đếm latency khi
   dispatch đầu vừa trả thread. Response chỉ được hoàn tất một lần.
8. **Cleanup và crash recovery.** Result/error/timeout/completion/shutdown xoá waiter, huỷ timer,
   trả capacity. Remote disconnect có thể chỉ được container phát hiện khi ghi response; timer
   vẫn giới hạn thời gian giữ RAM. Instance chết làm mất registry/kết nối HTTP đó, không mất
   dữ liệu đã commit. Checkout GET status qua instance khác nếu có (hoặc sau restart), cùng
   token/key/body cho replay. Cần replica còn sống, routing và PostgreSQL/Temporal khả dụng;
   một instance Compose không tự tạo HA. Workflow tiếp tục `AWAITING` sau PAYMENT_READY.

## Cấu hình và vận hành

`order.checkout.waiter`: `max-waiters=512` (`ORDER_SUBMISSION_MAX_WAITERS`),
`response-threads=16` (`ORDER_SUBMISSION_RESPONSE_THREADS`), `batch-size=256`,
`recovery-interval=1s`, `reconnect-delay=1s`. Bounds là giá trị khởi điểm, chưa có capacity benchmark.
Budget PostgreSQL cộng **một listener connection trên mỗi replica** ngoài Hikari. Listener dùng
connect timeout 3 s, socket timeout 5 s (URL JDBC có thể override), reconnect backoff cố định 1 s.
Không mở transaction dài trên LISTEN connection.

Metrics: `order.checkout.waiters`, `order.checkout.response.active`, `order.checkout.response.queued`,
`order.checkout.waiter.rejected`, `order.checkout.notification.connected` (0/1). Connected=0 nhưng
DB query còn chạy thì sweep vẫn phục hồi; không dùng notification như điều kiện correctness.

Triển khai V3 trước hoặc cùng binary mới; binary cũ bỏ qua notification nên migration tương thích.
Rollback binary không cần drop trigger/function, giữ Flyway history. Không thay đổi Temporal workflow
history/SDK, Payment API, mesh timeout hoặc Checkout bulkhead; không bật virtual threads toàn hệ thống.

## Kiểm chứng

- `SubmissionWaitersTest`: replay khi đã commit trước register, duplicate waiter và `201/200`,
  deadline, capacity/cleanup, batch recovery, caller context không leak, Payment chậm và deadline response.
- `SubmissionNotificationIT`: PostgreSQL thật, rollback không phát, commit phát, CAS no-op không phát,
  hai listener/registry độc lập nhận cùng transition, reconnect đọc lại thay đổi bị lỡ khi chỉ session
  test bị ngắt, MockMvc async release và `503`/headers/recovery state.
- Toàn bộ Order suite và Checkout suite xác nhận lifecycle/compensation, schema, recovery không đổi.
  Kết quả chạy và giới hạn bằng chứng được ghi ở [development](../development.md) và
  [verification 2026-10-16](../async-submission-verification-2026-10-16.md); pass test chức năng
  không chứng minh tăng RPS ([performance plan](../../performance/load-test.md)).

## Tham khảo

- [Spring MVC async requests](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [PostgreSQL 16 NOTIFY](https://www.postgresql.org/docs/16/sql-notify.html)
- [PostgreSQL 16 LISTEN và startup race](https://www.postgresql.org/docs/16/sql-listen.html)
- [pgJDBC LISTEN/NOTIFY](https://jdbc.postgresql.org/documentation/server-prepare/#listen--notify)
