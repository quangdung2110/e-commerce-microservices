# Compatibility và Versioning

Version nằm trong path/package/type:

- HTTP: `/api/v1`
- Protobuf: `ecommerce.product.v1`
- Event: `com.ecommerce.cart.item-added.v1`

SemVer trong `info.version`/release tag dùng để phát hành artifact, nhưng không
thay thế major version trên wire.

## Thay đổi thường compatible

- Thêm endpoint/RPC mới.
- Thêm response field optional.
- Thêm event field optional có default semantic rõ ràng.
- Nới giới hạn mà không thay đổi security/cost contract.

## Thay đổi breaking

- Xóa hoặc đổi tên field/operation.
- Đổi type, unit, meaning hoặc ownership.
- Optional thành required.
- Đổi HTTP status/error code mà client dùng để điều khiển flow.
- Đổi Kafka key hoặc ordering scope.
- Đổi Protobuf field number/type hoặc tái sử dụng number cũ.

Breaking change MUST tạo major version mới và có migration note. CI chặn nó cho Protobuf và OpenAPI
([ADR-008](../decisions/ADR-008-contract-ci-gates.md)); ngoại lệ phải ghi trong `contracts/tools/breaking-waivers.txt` kèm ADR phê duyệt. Internal service
nên hỗ trợ version cũ cho tới khi tất cả consumer trong catalog chuyển xong;
không đặt một thời gian cứng giống nhau cho mọi service.
