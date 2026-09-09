---
doc_id: promotion-adr-007
service: promotion
status: accepted
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - docs/promotion/architecture.md
  - contracts/examples/events/promotion-product-markdown-snapshot.v1.json
---

# ADR-007 — Markdown CQRS và expiry near-real-time

## Status

`Accepted` ở mức thiết kế; chưa implemented.

## Context

Promotion biết rule/scope nhưng không sở hữu category membership hay original price.
Search cần final selling price sẵn có; cron mỗi phút làm giá stale quá lâu lúc rule hết hạn.

## Decision

Promotion sở hữu rule/schedule và phát thay đổi; Product resolve scope, tính/persist giá rồi
phát catalog price cho Search. Search chỉ đọc. Expiry dùng Redis ZSET loop 250ms–1s,
per-rule SETNX, DB guarded transition và cron fallback 10–30s; startup rebuild index.

## Alternatives considered

- Promotion join Product data/tính từng SKU: vi phạm ownership và fanout DB lớn.
- Search tự tính discount: đưa business write logic vào read model.
- Chỉ cron một phút: đơn giản nhưng có cửa sổ giá sai tối đa một phút.

## Consequences

Browse nhanh và responsibility rõ; schedule gần chuẩn giây, self-heal. Đổi lại event
eventual/best-effort có thể để Product stale, cần scheduler + fallback + replay runbook.
Contract category/product scope hiện chưa thống nhất và được hoãn.

## Migration impact

Cần canonical markdown snapshot/version, Product consumer, Redis schedule bootstrap,
fallback DB query/index và rollout consumer-before-producer.

## Verification

Test start/end boundary, duplicate worker, crash commit-before-ZREM/send, Redis loss/startup
rebuild, Product apply/reprice và Search nhận giá công bố.
