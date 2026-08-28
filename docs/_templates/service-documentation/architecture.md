---
doc_id: <service-id>-architecture
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-service-entrypoint>
  - <path-to-deployment-config>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Architecture

> File này trả lời: “Service được chia thành những thành phần nào và chúng phối
> hợp ra sao?”

## 1. Architecture context

Mô tả:

- Vị trí của service trong toàn hệ thống.
- Các upstream gọi service.
- Các downstream mà service phụ thuộc.
- Trust boundary và network boundary quan trọng.

## 2. Component diagram

Vẽ sơ đồ khi nó giúp hiểu rõ hơn văn bản. Sơ đồ cần thể hiện những thành phần
thực sự tồn tại, chẳng hạn:

- HTTP/gRPC controller.
- Application/domain service.
- Repository.
- Kafka producer/consumer.
- Background worker hoặc scheduler.
- Database, Redis và external provider.

Mỗi component trong sơ đồ phải có phần giải thích tương ứng bên dưới.

## 3. Trách nhiệm từng component

Với mỗi component, mô tả:

- Component làm gì.
- Component không được làm gì.
- Dependency của component.
- Dependency direction bắt buộc.
- Entry point hoặc source path làm evidence.

## 4. Processing model

Giải thích:

- Phần nào xử lý synchronous, phần nào asynchronous.
- Transaction bắt đầu và kết thúc ở đâu.
- Outbox, inbox, CDC hoặc queue được sử dụng thế nào.
- Side effect bên ngoài được thực hiện trước hay sau commit.

## 5. Consistency model

Nêu rõ:

- Dữ liệu hoặc workflow nào cần strong consistency.
- Phần nào chấp nhận eventual consistency.
- Dữ liệu nào có thể stale và stale trong bao lâu.
- Cách phát hiện và phục hồi khi đồng bộ thất bại.

## 6. Concurrency model

Mô tả:

- Lock, optimistic version, compare-and-set, Redis Lua hoặc single writer.
- Các race condition quan trọng.
- Quy tắc ordering.
- Cách chống lost update, duplicate processing và double spending.

## 7. Deployment topology

Mô tả:

- Những process/container nào được deploy.
- API server, consumer và scheduler chạy chung hay riêng.
- Runtime dependencies.
- Cách scale từng component.
- Thành phần nào yêu cầu singleton hoặc distributed lock.

## 8. Architecture constraints

Liệt kê những giới hạn phải tuân thủ, ví dụ:

- Không query trực tiếp database của service khác.
- Không phát event trước khi transaction dữ liệu commit.
- Không đặt business logic trong controller hoặc message adapter.

## 9. Trade-offs và ADR liên quan

Mô tả những gì kiến trúc đang đánh đổi và liên kết tới ADR tương ứng. Các quyết
định như chọn Redis, Temporal, Kafka hay một database cụ thể phải được ghi thành
ADR, không chỉ xuất hiện như một nhận xét trong file này.
