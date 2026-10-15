---
doc_id: review-adr-014
service: review
status: accepted
date: 2026-10-15
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-002-correlation-and-trace-propagation.md
  - contracts/decisions/ADR-003-deadline-propagation.md
  - java/services/review-service/src/main/java/com/ecommerce/review/common/RequestCorrelation.java
  - java/services/review-service/src/main/java/com/ecommerce/review/config/RequestCorrelationFilter.java
  - java/services/review-service/src/main/resources/logback-spring.xml
  - java/services/review-service/src/test/java/com/ecommerce/review/config/RequestCorrelationFilterTest.java
---

# ADR-014 — Request ID, trace ID và deadline của caller trong log của Review

## Status

`Accepted`. Áp dụng [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md)
và [ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) cho Review là bên nhận. Không đổi
ADR nào của Review.

## Context

- `InternalIdentityFilter` đặt `RequestContext.correlationId` từ header cấu hình
  (`gateway.request-id-header`). Giá trị này thành `correlationid` của outbox event và `trace_id` của problem,
  nên phải giữ nguyên.
- `logback-spring.xml` (profile `prod`) đã khai báo MDC key `request_id` và `correlation_id`, nhưng không có
  code nào đặt chúng; profile `dev`/`test` dùng `base.xml` với pattern mặc định không có MDC.
- Hai caller nội bộ (PDP BFF, Search) sắp gửi `X-Request-Timeout-Ms`; Review chưa đọc nó.

## Decision

1. Thêm `RequestCorrelationFilter` chạy trước `InternalIdentityFilter`: chuẩn hoá `X-Request-Id`, đưa
   `request_id` và `trace_id` vào MDC (khớp key `request_id` mà logback đã khai báo), trả `X-Request-Id`.
2. `RequestContext.correlationId` không đổi, vì nó là danh tính nghiệp vụ của outbox event và problem.
   `X-Request-Id` chỉ dùng cho log và header trả về.
3. `logback-spring.xml` thêm MDC key `trace_id` cho profile `prod`; `application.yml`
   `logging.pattern.level` hiển thị `[request_id,trace_id]` cho `dev`/`test`.
4. Request vượt `X-Request-Timeout-Ms` được đếm (`http.server.caller_deadline_exceeded{route}`) và ghi
   WARN; không hủy công việc. Route đọc của PDP summary (BFF cắt ở 300 ms) và export của Search là nơi
   counter có ý nghĩa nhất.

## Alternatives considered

- **Thay `correlationid` của outbox bằng request ID đã chuẩn hoá.** Không chọn: đổi dữ liệu của event
  và hành vi của consumer; ngoài phạm vi.
- **Bỏ qua profile `dev`.** Không chọn: debug local là lúc cần ID nhất.

## Consequences

Log của Review tra chéo được với PDP BFF và Search. Không đổi contract HTTP, event hay schema.

## Migration impact

Code: `RequestCorrelation`, `RequestCorrelationFilter` (mới). Config: `logback-spring.xml`,
`application.yml`.

## Verification

`RequestCorrelationTest`, `RequestCorrelationFilterTest` (MDC `request_id`/`trace_id` đặt và dọn, header trả
về, deadline sai bị bỏ qua, vượt deadline bị đếm). `HttpContractIT` không đổi.
