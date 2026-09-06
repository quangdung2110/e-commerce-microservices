# Review Service — Architecture Decision Records

Thư mục này lưu các quyết định kiến trúc dài hạn của Review Service. Trạng thái
`Accepted` nghĩa là owner đã chấp thuận quyết định; nó **không** có nghĩa
implementation đã được verified — Review Service hiện chưa có implementation.

## Quy tắc

- Sao chép [ADR-000-template.md](./ADR-000-template.md) thành
  `ADR-<sequence>-<short-name>.md` khi tạo quyết định mới.
- Không sửa lịch sử của ADR `Accepted`; tạo ADR mới để thay thế và liên kết hai chiều.
- Decision phải nêu alternative, hệ quả tiêu cực và cách verification.
- Workflow, architecture và data chỉ liên kết đến ADR; không giấu trade-off trong
  phần mô tả luồng.

## Decision index

| ADR | Decision | Status | Date | Supersedes |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
| [ADR-001](./ADR-001-eligibility-and-single-edit.md) | Eligibility 1-1-1-1 và đúng một lần chỉnh sửa | Accepted | 2026-09-05 | — |
| [ADR-002](./ADR-002-postgresql-only-read-path.md) | PostgreSQL là kho duy nhất, không cache | Accepted | 2026-09-05 | — |
| [ADR-003](./ADR-003-outbox-cdc-publication.md) | Outbox và Debezium CDC cho việc phát event | Accepted | 2026-09-05 | — |
| [ADR-004](./ADR-004-review-revision-ordering-guard.md) | `review_revision` làm ordering guard cho downstream | Accepted | 2026-09-05 | — |
| [ADR-005](./ADR-005-async-rating-aggregation.md) | Tổng hợp rating bất đồng bộ qua self-consume | Accepted | 2026-09-05 | — |
| [ADR-006](./ADR-006-rating-snapshot-export.md) | Snapshot export rating cho full reindex của Search | Accepted | 2026-09-05 | — |
| [ADR-007](./ADR-007-author-name-snapshot.md) | Tên tác giả là snapshot, không đồng bộ theo profile | Accepted | 2026-09-05 | — |
| [ADR-008](./ADR-008-auto-approve-with-report-threshold.md) | Tự động duyệt kèm ngưỡng báo cáo thay cho tiền kiểm duyệt | Accepted | 2026-09-05 | — |
| [ADR-009](./ADR-009-review-owns-report-flow.md) | Review sở hữu toàn bộ luồng báo cáo vi phạm | Accepted | 2026-09-05 | — |

## Quyết định của service khác ảnh hưởng tới Review

| ADR | Vì sao liên quan |
|---|---|
| [Search ADR-016](../../search/decisions/ADR-016-review-rating-snapshot-reindex.md) | Định nghĩa cách Search dùng snapshot export và event của Review khi reindex; là đối ứng của [ADR-006](./ADR-006-rating-snapshot-export.md) |
| [Search ADR-015](../../search/decisions/ADR-015-upstream-revision-guards.md) | Yêu cầu mọi upstream cấp revision đơn điệu; [ADR-004](./ADR-004-review-revision-ordering-guard.md) là phần Review thực hiện |
| [Product Detail BFF ADR-002](../../product-detail-bff/decisions/ADR-002-review-read-composition-without-domain-ownership.md) | Giữ Review là bounded context độc lập; BFF chỉ đọc aggregate/preview và degrade enrichment |
