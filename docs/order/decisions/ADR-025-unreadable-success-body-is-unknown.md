---
doc_id: order-adr-025
service: order
status: accepted
date: 2026-10-16
decision_owners:
  - Order Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md
  - java/services/order-service/src/main/java/com/ecommerce/order/client/HttpRemote.java
  - java/services/order-service/src/test/java/com/ecommerce/order/client/HttpRemoteFaultInjectionTest.java
  - java/services/order-service/src/test/java/com/ecommerce/order/client/FaultServer.java
---

# ADR-025 — Phản hồi 2xx có body không đọc được là UNKNOWN

## Status

`Accepted`. Áp dụng [contracts ADR-010](../../../contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md). Bổ sung ADR-011 (UNKNOWN không bao giờ là kết quả nghiệp vụ âm) và ADR-024.

## Context

Test fault-injection ([contracts ADR-010](../../../contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md)) tìm ra rằng `HttpRemote` trả `ACCEPTED` với `body == null` khi provider gửi status 2xx nhưng body bị cắt giữa chừng, đến quá chậm hoặc không phải JSON (`readBody` nuốt `IOException` và trả `null`). `CheckoutSaga` đọc trường từ body của mọi kết quả đã chấp nhận (`r.body().path("outcome")`, `expires_at`, `discount`... hơn 15 chỗ), nên một phản hồi như vậy gây `NullPointerException` thay vì đưa saga tới nhánh query hoặc replay đã thiết kế cho kết quả chưa rõ.

## Decision

1. Body không đọc được (lỗi I/O, quá `read-timeout`, JSON hỏng) với status 2xx → `UNKNOWN` mã `BODY_UNREADABLE`, giữ `httpStatus` thật. Saga xử lý như mọi UNKNOWN: query hoặc replay cùng command identity (ADR-011).
2. Body rỗng vẫn là `ACCEPTED` với `body == null` (202/204 hợp lệ).
3. Status không phải 2xx mà body không đọc được vẫn phân loại theo status như trước (body coi như không có).

## Alternatives considered

- **Để `ACCEPTED` và vá từng chỗ `r.body()` trong saga.** Không chọn: hơn 15 chỗ, và chỗ thứ 16 sẽ lại quên.
- **Coi là `REJECTED`.** Không chọn: provider đã chấp nhận lệnh; từ chối sai làm saga hoàn tác một lệnh đã thực hiện.

## Consequences

Một provider trả 2xx hỏng body kích hoạt query/replay (an toàn nhờ idempotency key) thay vì lỗi NPE. Metric `order.remote.http` ghi outcome `UNKNOWN` với status `2xx` cho trường hợp này.

## Migration impact

Code: `HttpRemote.send` và `readBody` (ngoại lệ nội bộ `UnreadableBody`).

## Verification

`HttpRemoteFaultInjectionTest` (13 test, socket-level): bốn test về body cụt, chậm, hỏng và vòng hồi phục fail trước khi sửa với `ACCEPTED/null`, xanh sau khi sửa; các test hang, reset, refused, garbage, 503/429, từ chối xác định, bulkhead và metric xanh không cần đổi code.
