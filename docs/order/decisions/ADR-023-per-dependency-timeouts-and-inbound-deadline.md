---
doc_id: order-adr-023
service: order
status: accepted
date: 2026-10-15
decision_owners:
  - Order Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-003-deadline-propagation.md
  - docs/mesh/runtime-contract.md
  - java/services/order-service/src/main/java/com/ecommerce/order/client/HttpRemote.java
  - java/services/order-service/src/main/java/com/ecommerce/order/config/OrderProperties.java
  - java/services/order-service/src/main/java/com/ecommerce/order/common/RequestCorrelation.java
  - java/services/order-service/src/main/java/com/ecommerce/order/web/RequestCorrelationFilter.java
  - java/services/order-service/src/main/java/com/ecommerce/order/service/CheckoutSubmissionService.java
  - java/services/order-service/src/test/java/com/ecommerce/order/client/HttpRemotePropagationTest.java
  - java/services/order-service/src/test/java/com/ecommerce/order/web/RequestCorrelationFilterTest.java
---

# ADR-023 — Timeout riêng cho từng provider và deadline của request đến

## Status

`Accepted`. Áp dụng [contracts ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) và
phần Order của [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md).
Không đổi ADR nào của Order; làm rõ con số mà [ADR-020](./ADR-020-release-1-implementation-clarifications.md)
và [operations.md](../operations.md) chưa nêu.

## Context

- `order.clients.read-timeout` là một giá trị `10s` cho mọi lời gọi. Mesh cũng đặt `10 giây` cho
  `POST /internal/inventory/v1/reservations/**`, `/refund-scopes/**` và
  `POST /internal/v1/promotion-allocation/*`, nên timeout của Order bằng timeout của mesh. Quy tắc
  "timeout ứng dụng nhỏ hơn mesh ít nhất 1 giây" bị vi phạm: mesh có thể cắt trước và trả
  `503` không có problem body, còn Order phân loại nó như lỗi mạng.
- Hai nhóm route Order gọi không có dòng nào trong mesh §7: `GET /internal/inventory/v1/commands/*`
  (cùng `GET` reservations, refund-scopes) và `GET` Payment (`/v1/payments/{id}`, `/client-data`,
  `/v1/refunds/{id}`).
- `PaymentClientDataReader` gọi Payment ngay trên thread của request Checkout, sau khi đã chờ
  `PAYMENT_READY` tối đa 12 giây (`submit-wait`). Checkout chỉ chờ Order 13 giây, nên hai khoảng chờ
  cộng lại có thể vượt ngân sách của Checkout.
- `awaitCheckpoint` chờ đủ `submit-wait` dù Checkout còn bao nhiêu thời gian.

## Decision

1. **Timeout đọc theo provider**, mỗi giá trị nhỏ hơn timeout mesh của route ít nhất 1 giây:

   | Provider | Route (mesh) | Mesh | Order |
   |---|---|---:|---:|
   | Inventory | `reservations/**`, `refund-scopes/**`, `commands/*` | 10 giây | 8 giây |
   | Promotion | `promotion-allocation/*` | 10 giây | 8 giây |
   | Payment | `initiate`, `cancel`, `refund`, và các `GET` | 15 giây | 10 giây |

   `order.clients.inventory-read-timeout`, `promotion-read-timeout`, `payment-read-timeout` cấu hình
   từng giá trị; `read-timeout` là mặc định khi thiếu. `connect-timeout` giữ `2s`.

2. **Timeout hiệu dụng** của một lời gọi = `min(timeout provider, deadline còn lại của request đến − 500 ms)`.
   Lời gọi không chạy trên thread của một request (Temporal activity) chỉ có timeout provider.
   Deadline của request đến lấy từ `X-Request-Timeout-Ms` (Checkout gửi). Còn dưới 200 ms thì Order không
   gửi lời gọi và trả `UNKNOWN` với mã `DEADLINE_EXHAUSTED`; `PaymentClientDataReader` biến nó thành
   `503 DEPENDENCY_UNAVAILABLE` retryable như mọi lỗi Payment khác.

3. **Header gửi đi**: mọi lời gọi tới Inventory, Promotion, Payment mang `X-Request-Timeout-Ms` bằng
   đúng timeout hiệu dụng, cùng `X-Request-Id` (ADR-002).

4. **`submit-wait` bị kẹp**: checkpoint wait chờ `min(12 giây, deadline còn lại − 1 giây)`, không âm.
   Hết thời gian chờ thì trả trạng thái hiện tại (`PREPARING` → `503 SUBMISSION_IN_PROGRESS`, retry với
   cùng key và body), đúng như khi hết `submit-wait` ngày nay.
   [ADR-027](./ADR-027-async-submission-checkpoint-waiters.md) thay implementation `awaitCheckpoint`
   bằng `DeferredResult`/commit notification/batch recovery; giữ công thức này và truyền context/deadline
   gốc sang response executor, không reset deadline khi ASYNC redispatch.

5. **Quan sát**: request vượt `X-Request-Timeout-Ms` được đếm
   (`http.server.caller_deadline_exceeded{route}`) và ghi WARN; Order không hủy công việc vì header
   (ghi trạng thái, outbox và workflow dispatch phải hoàn tất).

6. **Mesh doc** thêm các dòng còn thiếu ở nhóm hai (đã thực hiện trong cùng thay đổi).

## Alternatives considered

- **Tăng timeout mesh lên 12 giây.** Không chọn: kéo dài thời gian giữ thread và connection của
  waypoint cho mọi route; vấn đề nằm ở app chạm trần mesh, không phải mesh quá chặt.
- **Giữ một `read-timeout` rồi hạ xuống 8 giây cho tất cả.** Không chọn: Payment tạo PaymentIntent qua
  Stripe cần dư địa hơn Inventory/Promotion; hạ chung sẽ tăng `UNKNOWN` không cần thiết ở Payment.
- **Per-call timeout bằng cấu hình trên từng activity.** Không chọn: timeout là thuộc tính của provider
  và route, không của activity; đặt ở `HttpRemote` giữ một nơi.
- **Hủy `awaitCheckpoint` ngay khi header hết hạn mà không chờ 1 giây dự phòng.** Không chọn: Order cần
  thời gian trả response có kiểm soát trước khi Checkout bỏ cuộc.

## Consequences

- Provider thấy timeout thật của Order; Order không gửi lời gọi mà người gọi đã bỏ cuộc.
- Cấu hình thêm ba khóa; đổi một con số phải giữ bất đẳng thức "Order < mesh − 1 giây" và cập nhật
  mesh §7 cùng lúc.
- Request thread của Order tạo một `RestClient` mới khi timeout hiệu dụng khác timeout cấu hình
  (chỉ khi bị kẹp); chi phí không đáng kể vì Order dùng `HttpURLConnection`, không pool.
- Saga không đổi: activity không có request đến nên không bao giờ nhận `DEADLINE_EXHAUSTED`.

## Migration impact

Code: `OrderProperties.Clients`, `HttpGateways`, `HttpRemote`, `RequestCorrelation`,
`RequestCorrelationFilter`, `CheckoutSubmissionService`. Config: `application.yml`. Tài liệu:
[interfaces.md §3.1](../interfaces.md), [operations.md](../operations.md), mesh §7 và design.

## Verification

- `HttpRemotePropagationTest`: header bằng timeout cấu hình khi không có request; nhỏ hơn và dương khi
  bị kẹp bởi deadline còn lại; không gửi gì và trả `DEADLINE_EXHAUSTED` khi còn dưới 200 ms.
- `RequestCorrelationTest`: `effectiveTimeout` với và không có deadline; giá trị header sai bị bỏ qua.
- `RequestCorrelationFilterTest`: request vượt deadline tăng counter, request đúng hạn thì không.
- `CheckoutFlowIT` vẫn xanh (không có header thì `submit-wait` giữ nguyên).
