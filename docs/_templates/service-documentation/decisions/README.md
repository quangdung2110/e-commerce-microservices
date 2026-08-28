# <Service Name> — Architecture Decision Records

Thư mục này chứa các quyết định kiến trúc của service. Các nội dung như “vì sao
chọn Redis”, “vì sao dùng Temporal”, “vì sao refund nằm trong Payment Service”
phải được ghi thành ADR, không trộn vào workflow.

## Khi nào phải tạo ADR

Tạo ADR khi quyết định:

- Thay đổi service boundary hoặc data ownership.
- Chọn/thay thế database, broker, framework hoặc protocol quan trọng.
- Chọn consistency, concurrency, caching hoặc messaging pattern.
- Tạo một trade-off khó đảo ngược hoặc ảnh hưởng nhiều service.
- Chấp nhận một constraint/risk dài hạn.
- Thay thế một quyết định đã được chấp nhận trước đó.

## Quy tắc

- Sao chép `ADR-000-template.md` thành `ADR-<sequence>-<short-name>.md`.
- Không sửa lịch sử của ADR đã `Accepted`; tạo ADR mới và đánh dấu ADR cũ là
  `Superseded`.
- Decision phải cụ thể và kiểm chứng được.
- Liên kết tới code, contract, benchmark, test hoặc tài liệu liên quan.
- Ghi cả hậu quả tiêu cực và chi phí migration.

## Decision index

| ADR | Decision | Status | Date | Supersedes |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
