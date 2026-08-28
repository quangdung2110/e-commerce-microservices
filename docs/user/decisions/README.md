# User Service — Hồ sơ quyết định kiến trúc

Các ADR dưới đây ghi lại lựa chọn có ảnh hưởng dài hạn trong thiết kế mục tiêu.
`accepted` nghĩa là thiết kế đã được chấp thuận trong bộ tài liệu này, không phải
bằng chứng implementation đã được verified.

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
