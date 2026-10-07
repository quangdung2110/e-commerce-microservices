---
doc_id: user-adr-003
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/domain.md
  - docs/user/data.md
---

# ADR-003 — Một address mặc định và hard delete address

## Bối cảnh

Address book cần checkout behavior mang tính xác định. Đơn hàng lịch sử phải giữ
shipping information kể cả khi user xóa address sau đó.

## Quyết định

Mỗi profile có tối đa một default address, được enforcement bằng partial unique
constraint kết hợp transactional mutation. Address đầu tiên thành default. Không
thể xóa address đang là default; owner phải chọn address khác làm default trước.
User address deletion là hard delete; Order Service phải sở hữu address snapshot
đủ province/district/ward tại checkout.

## Các phương án đã cân nhắc

- Cho phép không có default khi address book không rỗng: đẩy sự mơ hồ khi chọn
  sang checkout và caller.
- Soft delete address: giữ PII không cần thiết và làm mọi query phức tạp.
- Chỉ lưu live User address reference trong Order: làm lịch sử order có thể thay
  đổi và không phản ánh trung thực address giao hàng đã dùng.

## Hệ quả

Default mutation đồng thời cần locking/optimistic control ngoài partial index.
Hard delete khiến recovery không thể thực hiện ngoài backup procedure; retention
và authorization test là thiết yếu. Order snapshot cross-service là một ràng buộc
tích hợp cứng.

## Ảnh hưởng migration

Cần FK, partial unique index và Order checkout contract persist snapshot thay vì
live reference. Không cần replacement query khi xóa default bị từ chối.

## Xác minh

Chứng minh bằng migration, concurrent integration test và Order checkout contract
test rằng address bị xóa không bao giờ thay đổi order đã tồn tại.
