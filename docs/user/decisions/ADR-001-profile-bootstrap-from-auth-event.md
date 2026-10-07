---
doc_id: user-adr-001
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/workflows.md
  - contracts/events/auth/v1/asyncapi.yaml
---

# ADR-001 — Khởi tạo profile từ event Auth verification

## Bối cảnh

Auth sở hữu identity account, còn User sở hữu profile. Việc tạo profile không được
khiến Auth verification phải chờ database User sẵn sàng, nhưng mỗi account đã xác
minh cần có đúng một cặp profile/preferences khả dụng.

## Quyết định

User consume `USER_VERIFIED_SUCCESS` từ Auth và tạo bất đồng bộ profile rỗng cùng
preferences mặc định. Identifier dùng chung là `user_id`; User không sao chép
email hoặc credential. Insert idempotent và không ghi đè dữ liệu profile mà owner
đã sửa.

## Các phương án đã cân nhắc

- Lời gọi synchronous từ Auth sang User: xác nhận tạo ngay, nhưng ghép availability/
  latency của Auth với User và làm registration dễ lỗi hơn.
- Gateway/client tạo profile: có thể thiếu hoặc giả mạo bootstrap và giao quyền sở
  hữu lifecycle cho caller không phù hợp.

## Hệ quả

Verification vẫn có thể hoàn tất khi User không sẵn sàng, nhưng có một cửa sổ
eventual consistency ngắn trước khi profile đọc được. Cần xử lý Kafka duplicate,
lag và deletion sai thứ tự; sức khỏe consumer/reconciliation trở thành yêu cầu
vận hành.

## Ảnh hưởng migration

Cần adoption Auth event, schema profile/preferences unique của User và consumer
idempotency. Không được có quan hệ database trực tiếp giữa các service.

## Xác minh

Tạo AsyncAPI consumer contract test, integration test cho event duplicate/sai thứ
tự và dashboard/alert consumer lag trước khi xem đây là đã implement.
