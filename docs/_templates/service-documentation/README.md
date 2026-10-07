---
doc_id: <service-id>-overview
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-service-code>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Service Overview

> Đây là điểm bắt đầu để developer hoặc AI hiểu service trong 5–10 phút.
> Thay toàn bộ placeholder `<...>` bằng thông tin thực tế và xóa các hướng dẫn
> không còn cần thiết sau khi hoàn thành tài liệu.

## 1. Service status

Mô tả đầy đủ:

- Service đang `planned`, `developing`, `running` hay `retired`.
- Runtime, ngôn ngữ và framework đang sử dụng.
- Đường dẫn tới implementation.
- Tài liệu đang ở mức `proposed`, `implemented` hay `verified`.
- Contract adoption đang ở mức `not_started`, `pilot`, `partial` hay `complete`.

Không dùng một field duy nhất để biểu diễn đồng thời trạng thái triển khai của
service và độ tin cậy của tài liệu.

## 2. Mục tiêu

Giải thích:

- Service giải quyết vấn đề nghiệp vụ gì.
- Vì sao service cần tồn tại.
- Kết quả nghiệp vụ mà service tạo ra cho hệ thống hoặc người dùng.

## 3. Boundary

Nêu rõ:

- Service chịu trách nhiệm gì.
- Service không chịu trách nhiệm gì.
- Chức năng gần với service nhưng thuộc quyền sở hữu của service khác.
- Ranh giới transaction và bounded context.

## 4. Data ownership

Liệt kê:

- Dữ liệu nào service là system of record.
- Dữ liệu nào chỉ được cache, replicate hoặc materialize từ service khác.
- Service nào được phép đọc dữ liệu này qua contract.
- Khẳng định service khác có được ghi trực tiếp vào database hay không.

## 5. Capabilities

Liệt kê các năng lực nghiệp vụ service cung cấp. Mỗi capability cần có tên ổn
định và mô tả kết quả nghiệp vụ, không chỉ nêu tên endpoint hoặc class.

Ví dụ với Cart Service: thêm sản phẩm, đọc giỏ hàng, xóa item và clear cart sau
khi đơn hàng được xác nhận.

## 6. Dependencies

Mô tả bằng bảng:

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound/Outbound | `<service-id>` | HTTP/gRPC/Kafka | `<purpose>` | Required/Optional | `<contract-link>` |

Bảng phải cho biết service gọi ai, ai gọi service và giao tiếp bằng protocol nào.

## 7. Critical invariants

Liệt kê các quy tắc tuyệt đối không được vi phạm. Mỗi invariant cần có identifier
ổn định và link tới phần giải thích chi tiết trong `domain.md`.

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Thành phần và cách service được tổ chức | Khi cần hiểu cấu trúc hoặc thay đổi kiến trúc |
| [Domain](./domain.md) | Mô hình và luật nghiệp vụ | Khi thay đổi business logic |
| [Interfaces](./interfaces.md) | HTTP, gRPC và event semantics | Khi tích hợp với service |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Khi sửa một use case |
| [Data](./data.md) | Ownership, persistence và cache | Khi sửa schema hoặc dữ liệu |
| [Operations](./operations.md) | Vận hành production | Khi deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy, build và test local | Khi phát triển service |
| [Decisions](./decisions/README.md) | Architecture Decision Records | Khi cần hiểu lý do của thiết kế |

## 9. Known gaps

Nêu rõ:

- Chức năng nào chưa implement.
- Contract nào còn legacy hoặc chưa có machine-readable specification.
- Tài liệu nào chưa được `verified`.
- Khoảng cách giữa current implementation và target design.
- Giới hạn hoặc technical debt đã biết.

`README.md` không chứa chi tiết schema, payload hoặc mô tả thuật toán dài. Hãy
liên kết tới tài liệu chuyên biệt và nguồn sự thật tương ứng.
