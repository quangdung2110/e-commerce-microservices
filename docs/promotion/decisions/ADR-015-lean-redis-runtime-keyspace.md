---
doc_id: promotion-adr-015
service: promotion
status: accepted
date: 2026-09-13
decision_owners:
  - Promotion Service team
  - API Gateway team
supersedes: null
evidence:
  - docs/promotion/data.md
  - docs/promotion/workflows.md
  - docs/promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md
  - docs/promotion/decisions/ADR-014-claim-and-markdown-consistency.md
  - docs/gateway/runtime-contract.md
---

# ADR-015 — Redis runtime keyspace gọn và discovery rate limit tại edge

## Status

`Accepted`; implemented trong `java/services/promotion-service`. ADR này tinh gọn physical Redis key mapping
và ownership rate limit. Nó không supersede toàn bộ ADR-013 hoặc ADR-014: command receipt,
claim hard boundary, Redis schedule, SPOP/pending rescue và DB-first reconciliation vẫn giữ
nguyên.

## Context

Keyspace cũ có mười bảy pattern, trong đó applied marker và per-order mutation lock lặp lại
guarantee đã có từ Lua atomic transition, `promotion_command_receipts` và PostgreSQL
transaction. Claim gate và budget runtime lại có cùng promotion scope, hash slot, lifecycle
rebuild, nhưng bị tách thành hai key. RedisBloom code pre-check không phải authority, còn
Promotion-local discovery rate limit trùng trách nhiệm edge Gateway đã sở hữu distributed
rate limit.

Nhiều key làm runbook, rebuild, test matrix và yêu cầu Redis module trở nên phức tạp hơn mà
không thêm business invariant hoặc durable recovery evidence.

## Decision

- Dùng mười một key pattern trong `data.md`; không yêu cầu RedisBloom.
- Gộp `promo:{promotion_id}:budget` và `promo:{promotion_id}:claim_gate` thành
  `promo:{promotion_id}:runtime`. Hash giữ counter/quota và claim-gate fields
  `claim_enabled`, `claim_revision`, `opens_at_ms`, `closes_at_ms`. Đây là thay đổi
  physical key; Claim Lua vẫn dùng Redis `TIME` và revision trước `SPOP`.
- Không tạo `promo:codes`. PostgreSQL unique `promotions.code` là authority cho create và
  code lookup; cache chỉ là optimization không quyết định tồn tại/không tồn tại.
- Không tạo Redis `confirm_applied` marker hoặc `lock:{action}:{order_id}`. Reserve,
  cancel, Confirm-Prepare và Confirm-finalize phải là Lua state transition atomic; command
  receipt là evidence durable cho final-command retry. Finalizer thấy payload đã xóa thì
  no-op; thiếu/mismatch payload chỉ enqueue reconciliation, không mutate counter mù.
- Không tạo `schedule_lock`. Worker có thể cùng đọc due schedule member; DB re-read,
  deterministic outbox dedup và idempotent `ZREM` bảo đảm outcome đúng. Duplicate work là
  chi phí chấp nhận được, không tạo duplicate event.
- Voucher discovery không có Promotion Redis rate-limit key. Gateway profile
  `promotion-discovery` giới hạn authenticated user 20 request/phút ở edge; Gateway phải
  có shared distributed state và fail behavior theo `runtime-contract.md` trước khi expose
  route này.
- Giữ `promo:campaign:schedule` và `promo:{promotion_id}:reconcile_lock`. Schedule ZSET
  vẫn đáp ứng boundary propagation hiện tại; reconcile lock chưa bị thay bằng protocol CAS
  được chứng minh an toàn nên không xóa.

## Alternatives considered

- Giữ đủ mười bảy key: dễ triển khai tuần tự từ các flow, nhưng vận hành nhiều state không
  authority và lặp locking/idempotency.
- Bỏ Redis schedule, chỉ poll PostgreSQL: có thể cân nhắc sau load test vì PostgreSQL đã là
  source of truth, nhưng thay đổi latency/capacity boundary và chưa thuộc quyết định này.
- Nhét pending payload vào ZSET hoặc `claimed_users`: giảm một key nhưng tạo serialization
  mơ hồ hoặc lookup không hiệu quả cho API retry và sweeper; giữ Hash/ZSET dual index.
- Xóa reconcile lock ngay: không đủ vì protocol repair cạnh tranh với runtime mutation chưa
  có CAS/version fence được chỉ rõ.

## Consequences

Redis runtime giảm từ mười bảy xuống mười một key pattern, bỏ module RedisBloom và bớt ba
loại state ephemeral không tăng correctness. Claim/budget runtime vẫn atomic trong cùng
cluster slot. Recovery sau Redis loss vẫn dựa PostgreSQL, pending evidence, command receipt
và reconciliation như trước.

Gateway trở thành nơi duy nhất sở hữu quota discovery; điều này yêu cầu route policy phải
được deploy cùng Promotion public route. Khi Gateway distributed rate limit chưa sẵn sàng,
không được coi việc bỏ Promotion-local limiter là đã hoàn tất.

## Migration impact

Vì Promotion chưa có source module/migration, không cần migration dữ liệu. Implementation
tương lai chỉ tạo `runtime` hash, không tạo các key đã loại và không provision RedisBloom.
Nếu prototype tồn tại, rollout dual-read `budget`/`claim_gate` → `runtime`, rebuild từ
PostgreSQL, xác nhận drift rồi trim key cũ. Gateway triển khai profile/route discovery trước
khi bỏ limiter cũ; không thay đổi HTTP response hay PostgreSQL/Kafka contract.

## Verification

- Lua tests chứng minh claim exact boundary, reserve/cancel/prepare/finalize race và retry
  finalizer không cần applied marker hay mutation lock.
- Test nhiều scheduler worker cùng due member: chỉ một causal outbox row và `ZREM` an toàn.
- Redis restart: rebuild runtime claim fields/pool/counter projection và reconcile như cũ.
- Gateway integration/load test: quota discovery đúng 20 request/user/phút qua nhiều replica,
  Redis outage theo policy edge và Promotion không phát sinh local rate-limit key.
- Redis inventory test xác nhận chỉ có mười một key pattern đã công bố, không RedisBloom.
