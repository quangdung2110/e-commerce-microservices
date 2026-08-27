---
doc_id: auth-adr-004
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence: []
---

# ADR-004 — Store ephemeral security state in Redis

## Context

Revocation marker, rate limit, failed-login counter và one-time secret cần lookup
nhanh và TTL tự dọn. Refresh token vẫn cần durable lifecycle/audit.

## Decision

Redis dùng prefix `auth:` cho denylist, revoke marker, rate limit, failed-login,
OTP và reset-token hash. PostgreSQL là source of truth cho refresh token.

## Alternatives considered

- Chỉ PostgreSQL: durable nhưng không phù hợp lookup/TTL hot path.
- Redis cho refresh token: đơn giản hóa cache nhưng giảm durability và audit.

## Consequences

Redis availability/eviction ảnh hưởng security flow; cần fail policy, TTL, atomic
consume và runbook rõ ràng.

## Migration impact and verification

Cần key naming, TTL, monitoring, Redis-failure test và quyết định backup/fallback.
