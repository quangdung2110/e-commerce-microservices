---
doc_id: auth-adr-003
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence: []
---

# ADR-003 — Publish JWKS with key-rotation overlap

## Context

Gateway cần verify JWT cục bộ mà không nhận private key, đồng thời key phải được
rotate an toàn.

## Decision

Auth ký JWT bằng asymmetric key, công bố public keys qua JWKS, dùng trạng thái
`ACTIVE`, `PHASE_OUT`, `RETIRED` và giữ phase-out key đến hết maximum access-token
lifetime. `JWKS_UPDATED` là cache-invalidation signal.

## Alternatives considered

- Shared symmetric key: đơn giản nhưng mở rộng trust boundary.
- Gateway gọi Auth để verify: giảm phân phối key nhưng thêm latency/dependency.
- Retire ngay key cũ: dễ vận hành nhưng làm token hợp lệ thất bại.

## Consequences

Key lifecycle cần scheduler, lock, alert và Gateway fallback refresh khi gặp
unknown `kid`.

## Migration impact and verification

Cần JWKS contract, secure key storage, overlap/rollback test và key-age metric.
