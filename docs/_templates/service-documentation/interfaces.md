---
doc_id: <service-id>-interfaces
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-openapi-protobuf-asyncapi>
  - <path-to-interface-adapters>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Interfaces

> File này trả lời: “Service nhận và gửi thông tin gì?” Machine-readable
> contract là nguồn chuẩn cho payload; file này giải thích semantics và hành vi.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumers | Contract | Status |
|---|---|---|---|---|---|---|
| `<id>` | Inbound/Outbound | HTTP/gRPC/Kafka | `<provider>` | `<consumers>` | `<link>` | proposed/implemented/verified |

## 2. HTTP APIs

Với mỗi nhóm API, mô tả:

- Mục đích nghiệp vụ.
- Caller.
- Authentication và authorization.
- Preconditions.
- Postconditions.
- Idempotency key và replay behavior.
- Error semantics.
- Rate/size limit quan trọng.
- Link tới OpenAPI.
- Link tới controller và test làm evidence.

Không copy toàn bộ request/response schema từ OpenAPI sang Markdown.

## 3. gRPC APIs

Với mỗi service hoặc RPC, mô tả:

- Provider và consumer.
- Mục đích nghiệp vụ.
- Authentication nếu có.
- Deadline.
- Retry policy.
- Idempotency.
- gRPC error mapping.
- Compatibility rule.
- Link tới file `.proto`.

## 4. Events phát ra

Với mỗi event, mô tả:

- Event name và version.
- Ý nghĩa nghiệp vụ.
- Điều kiện và thời điểm phát event.
- Topic.
- Partition/ordering key.
- Delivery guarantee.
- Consumer dự kiến.
- Link tới AsyncAPI/JSON Schema.
- Cơ chế outbox hoặc producer reliability.

## 5. Events tiêu thụ

Với mỗi event, mô tả:

- Producer.
- Topic và consumer group.
- Hành động khi nhận event.
- Idempotency/deduplication key.
- Ordering requirement.
- Retry và dead-letter policy.
- Cách xử lý duplicate, unknown field và unsupported version.
- State change hoặc event phát sinh tiếp theo.

## 6. Compatibility

Mô tả:

- Quy tắc backward/forward compatibility.
- Versioning strategy.
- Deprecation process.
- Thời gian hỗ trợ contract cũ.
- Breaking-change check trong CI.
- Cách provider và consumer chứng minh trạng thái `verified`.
