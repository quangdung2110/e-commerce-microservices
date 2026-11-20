---
doc_id: user-adr-012
service: user
status: accepted
date: 2026-10-09
decision_owners:
  - User Service team
supersedes: ADR-003 (chỉ quy tắc "không xóa address default"; một default, hard delete và snapshot giữ nguyên)
evidence:
  - docs/user/domain.md
  - go/services/user-service/internal/domain/entity/address.go
---

# ADR-012 — Cho xóa address duy nhất còn lại

## Bối cảnh

[ADR-003](./ADR-003-default-address-and-hard-delete.md) cấm xóa address default để checkout
luôn có lựa chọn xác định. Address đầu tiên luôn là default và không có thao tác bỏ default,
nên muốn xóa một address default phải có address thứ hai để chuyển default sang. Hệ quả:
address book đã có địa chỉ thì không bao giờ rỗng lại được; owner không thể xóa dữ liệu
PII của chính mình (chỉ sửa được), trừ khi xóa cả account.

## Quyết định

`USER-BR-004` chỉ áp dụng khi còn address khác: xóa default trong lúc address book có từ
hai address trở lên vẫn là `409 DEFAULT_ADDRESS_CANNOT_BE_DELETED`. Address duy nhất còn
lại (đồng thời là default) xóa được; address book rỗng. Address tạo sau đó lại là default
theo `USER-BR-002`. Số address được đếm trong transaction đã giữ advisory lock theo user
nên create đồng thời không đổi kết quả giữa lúc đếm và xóa.

Phương án "cho phép không có default khi address book không rỗng" mà ADR-003 đã loại vẫn
bị loại: sau thay đổi này address book hoặc rỗng, hoặc có đúng một default.

## Hệ quả

Không có hậu quả cho Order (giữ snapshot riêng) hay Checkout (chọn address theo
`address_id`, và `404 ADDRESS_NOT_FOUND` đã là kết quả nghiệp vụ). Client cần chịu được
address book rỗng sau một lần xóa, vốn đã là trạng thái hợp lệ của account mới.

## Xác minh

Unit test entity (`EnsureDeletable` theo số address), unit và integration test: xóa default
khi còn address khác vẫn `409`; xóa address duy nhất `204`; address kế tiếp thành default.
