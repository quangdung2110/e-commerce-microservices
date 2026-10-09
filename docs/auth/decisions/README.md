# Auth Service — Architecture Decision Records

Thư mục này lưu các quyết định kiến trúc dài hạn. Trạng thái `Accepted` nghĩa là
owner đã chấp thuận quyết định; nó không có nghĩa implementation đã được verified.

## Quy tắc

- Sao chép [ADR-000-template.md](./ADR-000-template.md) thành
  `ADR-<sequence>-<short-name>.md` khi tạo quyết định mới.
- Không sửa lịch sử ADR `Accepted`; tạo ADR mới để thay thế và liên kết hai chiều.
- Decision phải nêu alternative, hệ quả và cách verification.
- Workflow/architecture chỉ liên kết đến ADR; không ẩn trade-off trong mô tả luồng.

## Decision index

| ADR | Decision | Status | Date | Supersedes |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
| [ADR-001](./ADR-001-dual-token-revocation.md) | Dual token và revoke access token | Accepted | 2026-08-27 | — |
| [ADR-002](./ADR-002-refresh-token-rotation.md) | Rotate refresh token không grace period | Accepted | 2026-08-27 | — |
| [ADR-003](./ADR-003-jwks-key-rotation.md) | JWKS và overlap key rotation | Accepted | 2026-08-27 | — |
| [ADR-004](./ADR-004-redis-security-state.md) | Redis cho ephemeral security state | Accepted | 2026-08-27 | — |
| [ADR-005](./ADR-005-event-publication-reliability.md) | Reliability của identity event | Accepted | 2026-08-27 | — |
| [ADR-006](./ADR-006-publish-before-revocation-marker.md) | Phát event trước khi ghi revocation marker | Accepted | 2026-10-09 | — (bổ sung ADR-005) |
| [ADR-007](./ADR-007-internal-caller-auth-delegated-to-mesh.md) | Listener internal xác thực caller qua service mesh | Accepted | 2026-10-09 | — |
| [ADR-008](./ADR-008-account-optimistic-concurrency.md) | Ghi account bằng compare-and-set, tải lại khi thua | Accepted | 2026-10-09 | — |
| [ADR-009](./ADR-009-login-password-before-state.md) | Đăng nhập kiểm mật khẩu trước trạng thái; unlock inline phát `USER_UNLOCKED` | Accepted | 2026-10-09 | — |
