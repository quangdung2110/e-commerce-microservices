---
doc_id: auth-adr-002
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence: []
---

# ADR-002 — Rotate refresh token without a grace period

## Context

Refresh token cần chống replay, kể cả khi client gửi trùng request hoặc token bị
đánh cắp.

## Decision

Mỗi refresh token là opaque secret hash. Khi đổi thành công, token cũ bị revoke
và token mới cùng family được tạo; token cũ không có grace period hay cached
token-pair replay response.

## Alternatives considered

- Không rotation: đơn giản nhưng cửa sổ token bị lộ dài hơn.
- Grace period/replay response: chịu lỗi mạng tốt hơn nhưng tăng nguy cơ replay
  và độ phức tạp state.

## Consequences

Client timeout không được retry mù quáng bằng token cũ. Persistence phải đảm bảo
single successful consumption dưới concurrency.

## Migration impact and verification

Cần unique token hash, atomic update/CAS và concurrent-refresh test. Chưa xác
minh implementation.
