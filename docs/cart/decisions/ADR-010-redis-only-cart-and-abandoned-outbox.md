---
doc_id: cart-adr-010
service: cart
status: accepted
date: 2026-08-30
decision_owners:
  - Cart Service team
supersedes:
  - ADR-001
  - ADR-002
evidence:
  - docs/cart/data.md
  - docs/cart/scripts
  - contracts/events/cart/v1/asyncapi.yaml
---

# ADR-010 — Redis-only Cart và outbox chỉ cho `CART_ABANDONED`

## Status

`Accepted`. Thay thế ADR-001 và ADR-002; vẫn giữ công nghệ Redis Streams nhưng
định nghĩa lại phạm vi outbox hiện hành.

## Context

Thiết kế trước giữ PostgreSQL cold store để rehydrate, retention, analytics và
đánh dấu abandoned. Hiện không có kế hoạch Analytics Service; Notification là
consumer duy nhất được xác định, chỉ cần `CART_ABANDONED`. Cold store, Postgres Sync
consumer và các event item trở thành chi phí vận hành không có consumer rõ ràng.

Cart vẫn cần state transition abandoned và event Kafka không thể tách rời. Publish
Kafka trực tiếp sau Lua có thể mất event khi process crash; publish trước Redis tạo
event ma. Do đó Redis Stream vẫn cần là durable outbox.

## Decision

Redis là store duy nhất của Cart: items hash, metadata hash, abandonment scheduler
ZSET, idempotency key và 64 outbox stream. Không còn PostgreSQL schema, cold store,
Postgres Sync hay rehydrate.

Cart chỉ phát `CART_ABANDONED`. `mark_abandoned.lua` atomically chuyển meta từ
`ACTIVE` sang `ABANDONED`, gỡ deadline ZSET, đặt retention TTL và `XADD` stream.
Notification dedupe CloudEvent ID do Relay dựng từ `outbox_key + stream_id`.

Giữ 64 stream cùng 64 cart bucket (`b00..b63`), dù chỉ có một event type. Stream
count theo Redis Cluster routing và atomicity, không theo số event type: giảm còn 4
stream nhưng giữ 64 cart bucket sẽ làm Lua `CROSSSLOT`; giảm state routing còn 4
bucket sẽ tạo hotspot không cần thiết.

Một deployment tải thấp chạy một marker loop, một relay loop và một trimmer loop,
mỗi loop round-robin 64 bucket. Scale bằng cách chia ownership bucket, không thêm
reader song song trên cùng stream.

## Alternatives considered

**Giữ PostgreSQL cold store.** Cho recovery và analytics nhưng có worker/schema
không còn use case hiện tại; không chọn.

**Bỏ cả Redis Stream.** Đơn giản nếu không có downstream consumer, nhưng Notification
cần event bền; không chọn.

**Bốn stream chung với 64 cart bucket.** Cần dispatcher hai pha và reconciliation
phức tạp hoặc mất atomicity; không chọn.

**Bốn state bucket và bốn stream.** Giảm phân tán tải cart ở Redis Cluster; không chọn.

## Consequences

Tích cực: bỏ Postgres Sync, migration, rehydrate, retention cleaner và event không
có consumer. State/event abandoned vẫn nguyên tử và Relay retry Kafka an toàn.

Tiêu cực: Redis data loss làm mất cart; không có historical cart query hoặc recovery.
Để hỗ trợ abandoned sau 30 ngày, active cart giữ Redis TTL 37 ngày, tăng memory cần
theo dõi. Nếu cần Analytics hoặc recovery về sau, phải tạo decision/store projection
mới thay vì âm thầm dùng Stream như database.

## Migration impact

Namespace Redis chuyển `v1` sang `v2`; không đổi số bucket trong namespace đang
chạy. Không xóa stream `v1` pending khi rollout. PostgreSQL schema Cart bị loại khỏi
tài liệu thiết kế; implementation/hạ tầng thật cần rollout riêng trước khi dừng DB.

## Verification

- Test `mark_abandoned.lua` chứng minh meta transition và XADD cùng nguyên tử.
- Test mọi KEYS script cùng `{bNN}` trên Redis Cluster.
- Test marker, relay và trimmer round-robin 64 bucket.
- Test Kafka retry giữ CloudEvent ID ổn định và Notification dedupe được duplicate.
- Test Redis miss trả cart rỗng, không query PostgreSQL.
