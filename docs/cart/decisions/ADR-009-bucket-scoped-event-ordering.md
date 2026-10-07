---
doc_id: cart-adr-009
service: cart
status: superseded
date: 2026-08-30
decision_owners:
  - Cart Service team
supersedes: ADR-004
evidence:
  - docs/cart/data.md
  - docs/cart/workflows.md
  - docs/cart/schema/README.md
---

# ADR-009 — Ordering theo stream bucket và event identity kép

## Status

`Superseded` bởi [ADR-011](./ADR-011-current-pull-read-and-redis-outbox-scope.md).
ADR này từng thay thế [ADR-004](./ADR-004-stream-id-ordering.md); nội dung bên dưới
được giữ làm lịch sử cho thiết kế cold-store/outbox cũ.

## Context

ADR-004 giả định một tập stream có ID có thể coi là duy nhất toàn cục. ADR-008 thay
outbox bằng 64 stream bucket độc lập. Redis chỉ bảo đảm `stream_id` tăng đơn điệu
trong chính stream đã sinh nó; hai stream khác nhau có thể có cùng entry ID.

Tuy nhiên, routing của Cart cố định theo `user_id`, nên mọi event của một cart luôn
đi cùng một outbox stream. Cold-store projection chỉ cần thứ tự trong phạm vi cart;
Kafka event identity lại cần duy nhất giữa mọi bucket.

## Decision

`stream_id` là token ordering của một cart trong stream bucket của cart đó. Consumer
PostgreSQL tiếp tục guard `redis_stream_id` và `last_event_stream_id` bằng so sánh
Stream ID, vì cùng cart không đổi bucket trong namespace `v1`.

Kafka event identity là cặp `outbox_key + stream_id`, ví dụ:

```text
cart-outbox:v1:cart:v1:{b17}:outbox:1780000000000-0
```

Relay truyền ít nhất `event_id`, `outbox_bucket`, `stream_id` và `cart_id` trong
Kafka headers. Downstream dedupe bằng `event_id`; không dedupe bằng `stream_id` đơn
lẻ. Timestamp nghiệp vụ chỉ dùng để hiển thị, không dùng làm ordering.

## Alternatives considered

**Giữ `stream_id` là global ID.** Sai ngữ nghĩa khi có nhiều stream bucket.

**Dùng timestamp ứng dụng để ordering.** Dính clock skew và không phân biệt nhiều
write trong cùng mili giây.

**Thêm revision bền theo cart.** Có thể cần nếu routing một cart phải thay đổi trong
namespace đang chạy, nhưng hiện làm tăng state/lifecycle. Routing `v1` bất biến nên
không chọn; việc đổi routing phải tạo version mới và drain version cũ.

## Consequences

Mọi dashboard và log phải hiển thị `outbox_bucket` cùng `stream_id`. Database guard
vẫn hợp lệ vì nó chỉ so sánh event của cùng cart. Consumer ngoài Cart phải coi
`event_id` là khóa dedupe duy nhất.

## Migration impact

Relay/Kafka headers và tài liệu consumer thay `stream_id + cart_id` bằng
`outbox_key + stream_id` cho `event_id`. Không cần migration bảng vì stream ID vẫn
đủ để guard một cart trong routing `v1`.

## Verification

- Test chứng minh hai bucket có thể có cùng `stream_id` nhưng `event_id` khác nhau.
- Test event của một user luôn có cùng bucket và stream.
- Test cold-store guard vẫn loại duplicate/event cũ của cùng cart.
- Contract test xác nhận Kafka header có đủ `event_id`, `outbox_bucket`, `stream_id`
  và `cart_id`.
