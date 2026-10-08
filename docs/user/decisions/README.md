# User Service — Hồ sơ quyết định kiến trúc

Các ADR dưới đây ghi lại lựa chọn có ảnh hưởng dài hạn của User Service. `accepted`
nghĩa là quyết định đã được chấp thuận; ADR-001 đến ADR-012 đều đã có code và test
trong `go/services/user-service`, nhưng `accepted` không phải chứng nhận `verified`
(chưa có contract test tự động và đo đạc production).

## Quy tắc

- Sao chép [ADR-000-template.md](./ADR-000-template.md) thành
  `ADR-<sequence>-<short-name>.md` cho quyết định mới.
- Không sửa lịch sử ADR `accepted`; tạo ADR mới để thay thế và liên kết hai chiều.
- Mỗi ADR phải nêu lựa chọn khác, hệ quả và cách xác minh.
- Architecture/workflow chỉ liên kết đến ADR, không che giấu trade-off.

## Danh mục quyết định

| ADR | Quyết định | Trạng thái | Ngày | Thay thế |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
| [ADR-001](./ADR-001-profile-bootstrap-from-auth-event.md) | Khởi tạo profile từ event Auth verification | Đã chấp thuận | 2026-08-28 | — |
| [ADR-002](./ADR-002-address-cache-aside.md) | Cache-aside chỉ cho danh sách address | Đã chấp thuận | 2026-08-28 | — |
| [ADR-003](./ADR-003-default-address-and-hard-delete.md) | Một address mặc định và hard delete address | Đã chấp thuận | 2026-08-28 | — |
| [ADR-004](./ADR-004-direct-event-publication.md) | Độ tin cậy của direct event publishing | Đã chấp thuận | 2026-08-28 | — |
| [ADR-005](./ADR-005-gdpr-two-stage-deletion.md) | GDPR anonymization và retention purge hai giai đoạn | Đã chấp thuận | 2026-08-28 | — |
| [ADR-006](./ADR-006-auth-owns-account-deletion-event.md) | Auth sở hữu event xóa account | Đã chấp thuận | 2026-08-28 | ADR-005 (phần republish) |
| [ADR-007](./ADR-007-projection-reconciliation-without-outbox.md) | Reconcile projection định kỳ khi không dùng outbox | Đã chấp thuận | 2026-08-28 | — |
| [ADR-008](./ADR-008-internal-caller-auth-delegated-to-mesh.md) | Cổng internal xác thực caller qua service mesh, ứng dụng chỉ kiểm allowlist `X-Service-Identity` | Đã chấp thuận | 2026-10-09 | — |
| [ADR-009](./ADR-009-marketing-opt-in-and-complaint-opt-out.md) | Marketing là opt-in; tắt ở nguồn khi Notification báo complaint | Đã chấp thuận | 2026-10-09 | — |
| [ADR-010](./ADR-010-deleted-account-invisible-to-owner.md) | Account trong retention window không còn đọc/ghi được bởi owner | Đã chấp thuận | 2026-10-09 | — (bổ sung ADR-005) |
| [ADR-011](./ADR-011-address-cache-invalidation-epoch.md) | Epoch chống refill address cache muộn | Đã chấp thuận | 2026-10-09 | ADR-002 (chỉ phần cửa sổ stale) |
| [ADR-012](./ADR-012-delete-last-address.md) | Cho xóa address duy nhất còn lại | Đã chấp thuận | 2026-10-09 | ADR-003 (chỉ quy tắc xóa default) |
