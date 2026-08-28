---
doc_id: <service-id>-development
service: <service-id>
scope: current
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-build-file>
  - <path-to-test-config>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Development Guide

> File này phục vụ việc phát triển và kiểm thử service. Nó không thay thế tài
> liệu production architecture hoặc operations.

## 1. Prerequisites

Liệt kê phiên bản runtime, compiler, package manager, container runtime và công
cụ generate/lint bắt buộc.

## 2. Chạy local

Cung cấp các bước có thể copy và chạy để:

- Cài dependency.
- Build service.
- Khởi động service.
- Kiểm tra health endpoint.
- Dừng và cleanup tài nguyên local an toàn.

## 3. Local dependencies

Mô tả dependency cần chạy, port, cách khởi tạo và dependency nào có thể mock.
Liên kết tới Docker Compose hoặc script chính thức.

## 4. Local configuration

Mô tả:

- File/profile cấu hình local.
- Environment variable bắt buộc.
- Cách cung cấp development secret an toàn.
- Giá trị nào tuyệt đối không được commit.

## 5. Build, lint và format

Cung cấp command chuẩn và giải thích output thành công. Những command này nên
trùng với command được CI sử dụng.

## 6. Tests

Mô tả cách chạy:

- Unit test.
- Integration test.
- Contract/provider/consumer test.
- End-to-end test nếu có.
- Test theo package/module cụ thể.

Nêu dependency, fixture và tiêu chí pass của từng loại test.

## 7. Contract code generation

Mô tả cách generate model/stub từ OpenAPI/Protobuf/AsyncAPI, output path, tool
version và cách kiểm tra generated code không bị drift.

## 8. Database migration

Mô tả cách:

- Tạo migration.
- Chạy migration local.
- Rollback khi được hỗ trợ.
- Kiểm tra compatibility.
- Seed dữ liệu test.

## 9. Test data và fixtures

Mô tả vị trí fixture, cách tạo dữ liệu và quy tắc không sử dụng dữ liệu production/PII.

## 10. Debugging

Mô tả debugger, log level, trace local, cách inspect database/message/cache và
các thao tác an toàn khi debug.

## 11. Các lỗi local phổ biến

Liệt kê symptom, nguyên nhân thường gặp và cách kiểm tra/khắc phục.

## 12. Definition of Done

Một thay đổi service chỉ hoàn thành khi phù hợp với phạm vi thay đổi và đã:

- Build, lint và test thành công.
- Cập nhật machine-readable contract nếu interface thay đổi.
- Cập nhật migration/data documentation nếu schema thay đổi.
- Cập nhật workflow/domain documentation nếu behavior thay đổi.
- Thêm hoặc cập nhật ADR nếu có quyết định kiến trúc.
- Kiểm tra backward compatibility.
- Cập nhật evidence và trạng thái tài liệu.
