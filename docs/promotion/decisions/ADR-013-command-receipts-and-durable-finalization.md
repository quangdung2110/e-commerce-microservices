---
doc_id: promotion-adr-013
service: promotion
status: accepted
date: 2026-09-11
decision_owners:
  - Promotion Service team
supersedes:
  - ADR-003
  - ADR-012
evidence:
  - docs/promotion/data.md
  - docs/promotion/workflows.md
  - docs/order/workflows.md
---

# ADR-013 — Command receipt và durable finalization PostgreSQL-first

## Status

`Accepted` ở mức thiết kế; chưa implemented. Supersede ADR-003 và ADR-012. Giữ các
quyết định còn phù hợp: reserve/cancel pre-payment là Redis runtime hold, wallet là
redemption state, customer refund không mutate Promotion và reconciliation DB → Redis.

## Context

Confirm và `cancel-confirmed` thay đổi wallet cùng durable used budget, nhưng thiết kế
Redis-first cũ mutate counter trước PostgreSQL. Crash ở giữa làm Redis đã đổi trong khi
wallet, budget và idempotency evidence chưa commit; retry có thể trả kết quả mơ hồ hoặc
áp delta lần hai. Dùng outbox dedup làm command idempotency còn trộn inbound processing
với outbound publication và buộc hai loại dữ liệu có cùng retention.

## Decision

- Redis-first chỉ dùng cho admission/runtime hold: limited claim, reserve và cancel
  pre-payment. Không reserve ở preview; reserve chỉ chạy sau Submit bằng Lua all-or-nothing
  cho quantity, amount, reservation payload và index. Hold có TTL 15 phút cộng grace,
  amount là integer VND và các key atomic nằm cùng Redis Cluster slot.
- PostgreSQL-first dùng cho durable ownership/final transition/event. PostgreSQL
  `promotion_budgets` là nguồn đúng bền vững để reconciliation dựng lại Redis.
- Thêm `promotion_command_receipts` cho `CONFIRM` và `CANCEL_CONFIRMED`. Receipt có
  stable idempotency key, canonical request hash, business identity và stored response;
  unique business operation chặn key khác nhau nhắm cùng transition.
- Receipt, guarded wallet transition, durable budget mutation và outbox event commit
  trong cùng PostgreSQL transaction. Không commit durable `PENDING` receipt.
- Outbox chỉ chịu trách nhiệm durable publication record, logical-event dedup và stable
  event identity; Kafka delivery vẫn at-least-once. Receipt chịu trách nhiệm inbound retry,
  payload mismatch và response replay; retention của hai bảng độc lập.
- Confirm tra receipt theo idempotency key và business identity trước Redis. Durable hit
  hợp lệ trả stored response và chỉ kick finalize/repair; durable mismatch bị từ chối.
  Nếu preflight miss, Redis preparation fence verify reservation, đổi runtime state
  `RESERVED→CONFIRMING` và gia hạn grace nhưng chưa move counter. PostgreSQL transaction
  chốt `SAVED→USED`, budget, receipt và redeemed outbox; sau commit Redis finalizer mới
  move `reserved→used`. Missing/already-finalized payload không được increment mù; enqueue
  reconciliation từ DB. Redis miss phải recheck receipt để đóng race với concurrent
  commit/finalize trước khi trả `RESERVATION_NOT_FOUND`.
- `cancel-confirmed` chỉ chạy sau durable confirm và do post-payment technical failure.
  PostgreSQL transaction chốt receipt, giảm durable used budget, chuyển wallet
  `USED→SAVED` nếu còn hạn hoặc `USED→EXPIRED`, rồi insert outbox. Sau commit chỉ trigger
  Redis projection repair; không decrement Redis trước transaction hoặc replay delta mù.
- Amount authoritative của `CONFIRM` lấy từ matching reservation `CONFIRMING` do Promotion
  đã tạo; của `CANCEL_CONFIRMED` lấy từ wallet `USED`. Amount trong request chỉ là expected
  value và thuộc request hash. Cùng key khác hash trả `IDEMPOTENCY_PAYLOAD_MISMATCH`.
- Wallet tiếp tục là current ownership/redemption state: `USED` lưu `used_order_id`,
  `discount_amount_vnd` và `used_at`; không khôi phục `coupon_usages`. Validity được
  snapshot khi claim; campaign kết thúc tự nhiên không thu hồi wallet đã cấp.
- Customer refund, kể cả partial hay full-order, vẫn không gọi Promotion, không hoàn
  used quantity/budget và không tái cấp voucher. Không thêm refund allocation/consumer;
  DB wallet/budget tiếp tục là nguồn để reconciliation Redis.

## Alternatives considered

- Giữ Redis-first và thêm Redis marker: giảm thay đổi nhưng marker có thể mất khi Redis
  rebuild, vẫn cần version/reconciliation và command receipt durable.
- Dùng outbox dedup làm receipt: ít bảng nhưng trộn trách nhiệm, không lưu response ổn
  định và retention bị buộc vào CDC watermark.
- Tạo usage ledger đầy đủ: audit phong phú hơn nhưng trùng current wallet state và kéo
  Promotion trở lại customer-refund lifecycle.
- Distributed transaction PostgreSQL–Redis: không có atomicity thực tế và tăng coupling.

## Consequences

Crash trước DB commit rollback toàn durable state; crash sau commit luôn có receipt và
outbox để replay response/repair Redis. Redis stale sau compensation chỉ bảo thủ ngân
sách cho tới khi hội tụ. Đổi lại schema cần thêm command receipt, retention/cleanup,
canonical request hash, confirm fence/finalizer và metric projection lag. Các bảng do
quyết định độc lập bổ sung được liệt kê tại `data.md`.

## Migration impact

Tạo `promotion_command_receipts`, unique/index/retention job và backfill receipt nếu có
in-flight prototype data. Đổi confirm thành prepare-fence → DB commit → Redis finalize;
đổi `cancel-confirmed` thành DB commit → Redis repair. Deploy schema/reader trước writer,
giữ compatibility của stable Order keys `REDEEM_FINAL`/`CANCEL_FINAL`, rồi loại Lua
technical-cancel Redis-first. Promotion OpenAPI phải mô tả replay/mismatch semantics.

## Verification

Test duplicate tuần tự/đồng thời trước và sau khi reservation bị xóa, cùng key khác
payload, key khác nhắm cùng business operation, race receipt-preflight/Redis-miss, crash
trước/sau DB commit, Redis unavailable, confirm fence expiry, duplicate finalizer/outbox
delivery, wallet còn hạn/hết hạn, guarded budget underflow, receipt và outbox retention
độc lập, cùng eventual DB→Redis convergence.
