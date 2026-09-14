---
doc_id: promotion-adr-002
service: promotion
status: accepted
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - docs/promotion/data.md
  - docs/promotion/workflows.md
---

# ADR-002 — Snapshot Redis đơn tầng, parse AST request-scoped

## Status

`Accepted`; implemented trong `java/services/promotion-service` (`SnapshotCache`, `PromotionSnapshotService`).

## Context

Discovery/calculate là read-heavy. Cache object AST trong từng JVM tạo invalidation khó
và lệch version giữa instance; wallet cache/fanout theo promotion gây write amplification.

## Decision

Cache JSON bundle ở `promo:{id}:snapshot` TTL một giờ, dùng global
`promo:rules:version` làm version fence. Read pipeline version + N snapshot, miss đọc DB.
AST parse trong request scope. Không có JVM L1, Pub/Sub eviction, refresh-after-write hay
wallet cache. Budget exhausted được guard read-time thay vì fanout invalidation.

## Alternatives considered

- JVM/Caffeine L1: nhanh hơn nhưng consistency/invalidation cross-instance phức tạp.
- No cache: đơn giản nhưng dồn read vào PostgreSQL.
- Wallet cache + reverse index: giảm DB read nhưng memory/invalidation rất lớn.



## Consequences

Một cache tier dễ phục hồi, version rõ. Đổi lại parse AST mỗi request, Redis là dependency
latency; invalidation+INCR lỗi có thể stale đến TTL.

## Migration impact

Cần serializer/version schema, cache-aside, telemetry hit/miss và safe invalidation sau
DB commit. Không migrate object cache cũ vì chưa có implementation.

## Verification

Test hit/miss/version mismatch/Redis failover/concurrent admin write; benchmark p99
discovery và AST parse N voucher.