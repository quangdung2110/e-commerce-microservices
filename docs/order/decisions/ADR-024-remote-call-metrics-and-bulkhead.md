---
doc_id: order-adr-024
service: order
status: accepted
date: 2026-10-15
decision_owners:
  - Order Service team
supersedes: null
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order/client/HttpRemote.java
  - java/services/order-service/src/main/java/com/ecommerce/order/client/HttpGateways.java
  - java/services/order-service/src/main/java/com/ecommerce/order/config/OrderProperties.java
  - java/services/order-service/src/test/java/com/ecommerce/order/client/HttpRemoteResilienceTest.java
---

# ADR-024 — Đo mọi lời gọi remote theo dependency và giới hạn số lời gọi đồng thời

## Status

`Accepted`. Bổ sung [ADR-023](./ADR-023-per-dependency-timeouts-and-inbound-deadline.md) (timeout theo provider);
không đổi ADR-011 (kết quả UNKNOWN được replay cùng command).

## Context

`HttpRemote` là client JSON đơn giản trên `HttpURLConnection`: không pool, không giới hạn đồng thời, không metric
theo dependency, và bỏ qua `Retry-After`. Với saga có Temporal giữ retry, thiếu breaker là chấp nhận được; nhưng hai
điều không chấp nhận được: một provider chậm có thể giữ mọi worker thread của activity, và khi nó chậm thì không có
số đo nào cho biết provider nào, từ route nào, với kết quả nào. Chỉ có metric `order.remote.command` theo
operation (ở `RemoteCommands`), không có độ trễ HTTP.

## Decision

1. Mỗi lời gọi được đo bằng timer `order.remote.http`, nhãn `dependency` (`inventory`, `promotion`, `payment`),
   `method`, `outcome` (`ACCEPTED`, `REJECTED`, `UNKNOWN`: chính phân loại của `RemoteResult`) và `status`
   (`2xx`, `4xx`, `5xx`, `none`). Không có path hay ID trong nhãn.
2. **Bulkhead theo dependency**: tối đa `order.clients.max-concurrent` lời gọi cùng lúc tới một provider (mặc
   định 64). Lời gọi thứ nhiều hơn không được gửi và trả `UNKNOWN` mã `BULKHEAD_FULL`; saga xử lý đúng như mọi
   `UNKNOWN` khác (query hoặc replay cùng command, Temporal backoff). Không có hàng chờ.
3. `Retry-After` của `429`/`503` được ghi WARN (`dependency`, method, path, giá trị). Order không đổi nhịp retry
   theo nó: nhịp do Temporal và luồng replay quyết định.
4. Không thêm circuit breaker. Nó sẽ biến một provider đang lỗi thành hàng loạt `UNKNOWN` giả không có thông tin;
   bulkhead và timeout theo provider (ADR-023) là đủ cho tải của saga.

## Alternatives considered

- **Circuit breaker như Checkout.** Không chọn: Checkout cần fail-fast trong request của người dùng; saga cần
  kết quả thật của từng command và đã có replay.
- **Hàng chờ cho bulkhead.** Không chọn: chờ trong worker là cách giữ thread mà bulkhead định tránh.
- **Giữ `Retry-After` làm nhịp retry.** Không chọn: hai nguồn nhịp (provider và Temporal) làm khó dự đoán thời
  gian saga.

## Consequences

Có số đo p50/p99 và tỷ lệ UNKNOWN theo provider trước khi chỉnh timeout. Dưới tải rất cao, một số lời gọi trả
`BULKHEAD_FULL` rồi được replay; metric `outcome=UNKNOWN,status=none` tăng, và bulkhead tăng thêm độ trễ thay vì
đổ sập provider. `FakeRemotes` của test không đi qua `HttpRemote` nên không đổi.

## Migration impact

Code: `HttpRemote` (hai constructor, timer, semaphore), `HttpGateways` (truyền `MeterRegistry` và giới hạn),
`OrderProperties.Clients.maxConcurrent`. Config: `application.yml`. Tài liệu: `operations.md`.

## Verification

`HttpRemoteResilienceTest`: timer theo outcome và status class; với `max-concurrent = 2`, lời gọi thứ ba trả
`BULKHEAD_FULL` mà không tới provider, hai lời gọi đang chạy hoàn tất bình thường và slot được trả; remote không có
`MeterRegistry` vẫn chạy. `HttpRemotePropagationTest` không đổi.
