---
doc_id: auth-adr-002
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/infrastructure/postgres/refresh_token_repo.go
  - go/services/auth-service/test/integration/refresh_token_repo_test.go
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

Đã implement: `uq_refresh_tokens_token_hash` và một câu lệnh SQL duy nhất revoke token cũ rồi
tạo token mới cùng `family_id`. Integration test `refresh_token_repo_test.go` chạy 8 request
đồng thời trên cùng một token và chỉ một request thành công.
