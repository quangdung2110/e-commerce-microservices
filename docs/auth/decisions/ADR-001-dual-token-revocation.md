---
doc_id: auth-adr-001
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence: []
---

# ADR-001 — Dual token and access-token revocation

## Context

Access token cần sống ngắn để giảm rủi ro, nhưng logout/xóa account/đổi mật khẩu cần có
cách vô hiệu token trước khi TTL kết thúc.

## Decision

Dùng JWT access token sống ngắn với `jti`, kết hợp opaque refresh token server-side.
Logout một thiết bị denylist `jti`; logout all, đổi mật khẩu hoặc xóa account dùng
mốc `revoked_before` của user và Gateway so sánh `iat`. Cả hai tín hiệu nằm trong
namespace Redis `auth:revocation:*` dùng chung với `gateway-auth`; tên key đầy đủ
do [Gateway runtime contract](../../gateway/runtime-contract.md#6-quy-ước-xác-thực)
quy định và được nhắc lại ở [Data Design](../data.md).

## Alternatives considered

- JWT hoàn toàn stateless: đơn giản nhưng không revoke ngay được.
- Opaque access token introspection: revoke trực tiếp nhưng Gateway phải gọi Auth
  cho mỗi request.
- Denylist mọi access token: xử lý được revoke nhưng tăng memory/lookup rộng.

## Consequences

Gateway cần Redis check theo policy và token claim phải nhất quán. Redis outage
trở thành security/availability trade-off cần runbook.

## Migration impact and verification

Cần contract claim/JWKS, cache policy, integration test revoke và observability.
Chưa có implementation evidence trong phạm vi refactor này.
