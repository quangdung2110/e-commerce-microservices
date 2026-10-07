# ADR-001: Contract strategy cho monorepo Go/Java

- Status: Accepted
- Date: 2026-08-25

## Context

Repo có service Go, Java và nhiều service mới dừng ở documentation. Contract cũ
được lưu chủ yếu trong Markdown, trong khi Protobuf bị copy giữa provider và
consumer. Các service cũng dùng error/event envelope khác nhau.

## Decision

1. Tạo `contracts/` ở root làm component trung lập ngôn ngữ.
2. OpenAPI là nguồn chuẩn HTTP, Protobuf là nguồn chuẩn gRPC, AsyncAPI + JSON
   Schema là nguồn chuẩn Kafka.
3. Dùng CloudEvents cho event envelope và RFC 9457 cho HTTP error.
4. Generated code không phải source of truth và không được sửa tay.
5. Internal implementation pattern được giữ trong `docs/architecture` hoặc docs
   của service, không trộn vào wire contract.
6. Migrate theo pilot, không tuyên bố implementation conformant trước khi test.

## Consequences

- Go và Java generate từ một contract nên giảm drift.
- CI cần thêm lint và breaking-change check.
- Migration đầu tiên có breaking change vì wire cũ chưa thống nhất.
- Một số Markdown legacy vẫn tồn tại tạm thời và phải gắn trạng thái rõ ràng.
