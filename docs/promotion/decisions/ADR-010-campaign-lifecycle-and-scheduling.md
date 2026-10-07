---
doc_id: promotion-adr-010
service: promotion
status: superseded
date: 2026-09-10
decision_owners:
  - Promotion Service team
supersedes: ADR-007
superseded_by: ADR-014
evidence:
  - docs/promotion/domain.md
  - docs/promotion/workflows.md
---

# ADR-010 — Campaign lifecycle và Redis schedule tự phục hồi

## Status

`Superseded` bởi
[ADR-014](./ADR-014-claim-and-markdown-consistency.md). Campaign phase dẫn xuất và
self-healing schedule được giữ; ADR-014 chốt thêm immutable-definition và claim gate.

## Context

Voucher và markdown cùng campaign nhưng semantics khác nhau. Persist ACTIVE/ENDED trên
campaign/child tạo duplicated state và race với clock. Redis ZSET nhanh cho boundary,
nhưng destructive pop hoặc coi Redis là truth có thể mất schedule khi crash/failover.

## Decision

- Campaign persist `DRAFT/PUBLISHED/PAUSED/CANCELLED`, `[start_time,end_time)` và một
  `revision`. `UPCOMING/ACTIVE/ENDED` được suy ra; child không có lịch/status/revision riêng.
- Claim/save mới và markdown chỉ active trong campaign ACTIVE. Voucher đã lưu tiếp tục
  theo `wallet.expired_at`; mode `CAMPAIGN_END` snapshot expiry đúng bằng campaign end.
- Redis ZSET `promo:campaign:schedule` giữ START/END member có campaign revision.
- Worker đọc due member không destructive, lock, re-read DB/revision/time, commit outbox
  trước ZREM. START chạy muộn sau END không được phát ACTIVE.
- DB fallback 10–30 giây và startup/failover rebuild ZSET là bắt buộc.
- Pause/cancel tăng revision và làm markdown INACTIVE/đóng claim mới; không tự revoke
  voucher đã lưu.

## Alternatives considered

- Persist ACTIVE/ENDED và update bằng cron: dễ query nhưng duplicated clock state/race.
- ZSET per markdown rule: nhiều entry trùng lịch và child drift khỏi campaign.
- `ZPOPMIN` trước xử lý: đơn giản nhưng crash làm mất due member.
- Chỉ DB polling: bền vững nhưng tăng query/latency boundary.

## Consequences

Một schedule cho campaign đơn giản hơn, stale job bị revision fence và Redis loss tự
phục hồi. Đổi lại downstream là bounded eventual, không hard real-time 0 ms; worker cần
DB recheck, fallback, lock và outbox dedup.

## Migration impact

Loại `start_time/end_time/status/revision` khỏi promotion/markdown, thêm campaign
publication status/revision, đổi Redis key/member, backfill campaign linkage và rebuild
schedule. Rollout phải phát Product full snapshot để hội tụ trạng thái.

## Verification

Test exact boundary, timezone/clock, stale revision, pause/cancel, delayed START, duplicate
worker, crash commit-before-ZREM, missing ZSET, Redis restart và fallback convergence.
