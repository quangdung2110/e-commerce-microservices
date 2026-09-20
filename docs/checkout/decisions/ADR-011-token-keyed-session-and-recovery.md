---
title: ADR-011 Token-keyed session and atomic recovery
status: accepted
date: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
supersedes:
  - ADR-002
  - ADR-003
  - ADR-007
---

# ADR-011: Token-keyed session and atomic recovery

## Context

Mô hình một Redis Hash mutable theo user, distributed lock qua network call và các payload/result cache tách rời tạo race giữa preview/submit, có nguy cơ unlock sai owner, mất recovery state và làm token cũ trỏ vào snapshot mới.

## Decision

- Mỗi checkout token ánh xạ tới một immutable session record riêng bằng HMAC token hash.
- `checkout:active:{user_id}` là con trỏ UX và CAS fence tạo successor, không dùng làm source cho submit.
- Redis Lua/CAS atomically claim state và materialize immutable Order payload/idempotency key.
- Không giữ distributed lock trong lúc gọi downstream; Order idempotency là lớp chống side effect lặp.
- Terminal result được lưu cùng/ trước terminal state; success giữ 24 giờ, trạng thái recovery ít nhất 30 phút.
- Quote hết hạn logic sau 20 phút nhưng session record giữ 30 phút để owner-safe `410`; physical miss trả `404`.
- Status endpoint cho phép client/reconciler truy vấn token. Khi local result không rõ, query Order bằng idempotency key trước khi kết luận.
- Raw token không được lưu ở Redis keys/logs; bản cần cho Order request/recovery được mã hóa trong payload value. Client/payment secret không được cache.

## Consequences

- Nhiều Redis key hơn, nhưng preview đồng thời không overwrite nhau.
- Lua/CAS scripts và schema migration cần contract tests.
- Recovery có semantics rõ ràng cho timeout-after-write.
- ADR-002, ADR-003 và ADR-007 trở thành lịch sử superseded; ADR-010 về không cache secret vẫn có hiệu lực.

## Verification

- Concurrent submit test chỉ tạo một logical Order.
- Preview mới không xóa session đang submit.
- Fault injection ở mọi điểm trước/sau Order call vẫn recovery được hoặc trả `SUBMISSION_RESULT_UNKNOWN` an toàn.
