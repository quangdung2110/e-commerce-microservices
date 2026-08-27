---
doc_id: auth-development
service: auth
scope: current
status: proposed
owners:
  - Auth Service team
evidence: []
verified_against_commit: null
---

# Auth Service — Development Guide

> Đây là khung vận hành phát triển. Do refactor này không xem code hoặc build
> configuration, không command, version hoặc path nào được bịa ra.

## 1. Prerequisites and local dependencies

Khi có nguồn chính thức, tài liệu cần nêu runtime/compiler/package manager,
container runtime, PostgreSQL, Redis, Kafka, port local và dependency nào được
mock. Liên kết tới compose/script chính thức thay vì sao chép cấu hình ở đây.

## 2. Local run and configuration

Phần hoàn chỉnh phải gồm command copy-and-run để cài dependency, build, khởi động,
kiểm tra health endpoint và cleanup an toàn. Nó cũng phải liệt kê profile local,
biến môi trường bắt buộc, cách cấp development secret và các giá trị cấm commit.
Các thông tin này hiện `TBD`, không suy ra từ thiết kế.

## 3. Build, quality and tests

Command build, lint, format và test phải trùng CI. Bộ test cần bao phủ unit,
integration với PostgreSQL/Redis/Kafka, contract và workflow: refresh replay,
lockout/tự mở khóa, revoke, đổi email, OTP one-time use, key overlap, duplicate event và dependency
failure. Fixture không được chứa production data hoặc PII.

## 4. Schema và contract development

Schema ban đầu nằm tại
[`schema/0001_initial_auth_schema.sql`](./schema/0001_initial_auth_schema.sql)
và admin bootstrap dùng
[`schema/seed-admin.sql.example`](./schema/seed-admin.sql.example). Khi bổ sung
migration runner, mọi thay đổi schema phải có bước chạy local, compatibility check,
rollback (nếu hỗ trợ) và seed test. HTTP contract nằm tại
[`contracts/http/auth/v1/openapi.yaml`](../../contracts/http/auth/v1/openapi.yaml).
OpenAPI/AsyncAPI/JSON Schema phải có tool version, generate/check command và CI
check chống drift/breaking change.

## 5. Debugging and Definition of Done

Debug local phải dùng log/trace đã redaction và thao tác an toàn khi inspect
database, cache hoặc message. Một thay đổi hoàn thành khi build/lint/test pass,
contract/schema/workflow liên quan được cập nhật, compatibility được kiểm tra,
ADR được thêm khi có quyết định kiến trúc, và trạng thái/evidence tài liệu được
cập nhật. Hướng dẫn thực thi cụ thể chờ xác minh từ implementation.
