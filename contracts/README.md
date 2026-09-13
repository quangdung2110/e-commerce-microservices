# E-Commerce Contracts

Thư mục này là nguồn chuẩn cho dữ liệu đi qua ranh giới giữa các service.
Contract được viết độc lập với Go và Java để hai hệ sinh thái có thể dùng cùng
một định nghĩa.

> Trạng thái hiện tại: **foundation + pilot**. Các contract, bao gồm Promotion HTTP v1,
> là đích migration (`proposed`) trừ khi `ADOPTION.md` ghi trạng thái cao hơn; chưa mặc
> định khẳng định code cũ đã tương thích.
> Xem [ADOPTION.md](ADOPTION.md) trước khi thay đổi implementation.

## 1. Contract là gì?

Contract trả lời bốn câu hỏi:

1. Service nào sở hữu dữ liệu hoặc capability?
2. Dữ liệu nào đi qua HTTP, gRPC hoặc Kafka?
3. Khi lỗi, retry hoặc nhận message trùng thì chuyện gì xảy ra?
4. Thay đổi nào còn tương thích với consumer cũ?

Contract không mô tả chi tiết cách service lưu database, dùng Redis, tổ chức
package hay triển khai Outbox.

## 2. Bắt đầu từ đâu?

| Nhu cầu | Đọc file/thư mục |
|---|---|
| Xem service nào đã có code | [`catalog/services.yaml`](catalog/services.yaml) |
| Xem service giao tiếp với nhau | [`catalog/interactions.yaml`](catalog/interactions.yaml) |
| Viết contract mới | [`conventions/README.md`](conventions/README.md) |
| HTTP API | [`http/`](http/) |
| gRPC API | [`grpc/`](grpc/) |
| Kafka event | [`events/`](events/) |
| Kiểm tra contract | [`tools/README.md`](tools/README.md) |
| Xem mức độ adoption | [`ADOPTION.md`](ADOPTION.md) |

## 3. Nguồn chuẩn theo loại giao tiếp

| Giao tiếp | Nguồn chuẩn | Tài liệu cho người đọc |
|---|---|---|
| HTTP | OpenAPI 3.1 | `README.md` cạnh spec |
| gRPC | Protobuf | comment trong proto + `README.md` |
| Kafka | AsyncAPI + JSON Schema | `README.md` và example JSON |
| Quy ước chung | Schema dùng lại + Markdown | `conventions/` |

Nếu Markdown và machine-readable spec mâu thuẫn, spec là nguồn chuẩn cho dữ
liệu trên wire. Markdown vẫn là nguồn giải thích cho mục đích, trade-off và
failure mode.

## 4. Workflow thay đổi contract

1. Tìm owner và consumer trong `catalog/`.
2. Sửa spec trước, không sửa generated code.
3. Thêm hoặc sửa example/fixture.
4. Chạy `pwsh ./contracts/tools/validate-contracts.ps1`.
5. Đánh giá compatibility theo `conventions/compatibility.md`.
6. Cập nhật `CHANGELOG.md` nếu hành vi quan sát được thay đổi.
7. Migrate provider và consumer; chỉ chuyển trạng thái sang `verified` sau khi
   contract test đã chạy qua.

## 5. Nguyên tắc ngắn gọn

- Provider sở hữu contract, consumer phải được review khi contract thay đổi.
- Một contract chỉ có một bản nguồn; service không copy `.proto` hoặc schema.
- Generated code là output, không phải source of truth.
- Breaking change tạo version mới (`v2`), không sửa âm thầm `v1`.
- Event consumer phải chịu được message trùng và field mới chưa biết.
- Contract chỉ hứa những gì provider có thể kiểm thử.
