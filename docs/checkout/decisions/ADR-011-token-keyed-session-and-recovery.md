---
doc_id: checkout-adr-011
service: checkout
status: accepted
date: 2026-09-20
decision_owners:
  - Checkout Service team
supersedes:
  - checkout-adr-002
  - checkout-adr-003
  - checkout-adr-007
evidence:
  - docs/checkout/data.md
  - docs/checkout/workflows.md
---

# ADR-011: Token-keyed session and atomic recovery

## Status

`Accepted` 2026-09-20. Thay thế ADR-002, ADR-003, ADR-007.

> **Ghi chú 2026-10-07:** vai trò con trỏ active và luật Preview khi submission đang chạy
> được làm rõ bởi [ADR-016](./ADR-016-fresh-preview-and-in-flight-submission-guard.md); giới
> hạn re-POST trong recovery bởi [ADR-014](./ADR-014-resilience-budget-and-mesh-gateway-split.md).
> Không có reconciler nền: "reconciler" dưới đây là luồng status do client gọi. Trạng thái
> chưa rõ được trả bằng `202` với `state=RESULT_UNKNOWN`, không phải problem
> `SUBMISSION_RESULT_UNKNOWN`
> ([ADR-015](./ADR-015-problem-catalog-view-model-and-order-outcomes.md)).

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
