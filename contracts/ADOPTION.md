# Contract Adoption

File này phân biệt rõ ba trạng thái dễ bị nhầm:

- `proposed`: contract là thiết kế đích, code chưa chắc đã tuân thủ.
- `implemented`: provider đã dùng contract nhưng chưa có đủ kiểm tra consumer.
- `verified`: provider và consumer đều vượt qua contract test.

## Pilot hiện tại

| Contract | Trạng thái | Khoảng cách cần migrate |
|---|---|---|
| Cart HTTP v1 | `proposed` | Code cũ dùng nhiều error shape và giá `float64`; target dùng Problem Details và Money |
| Product gRPC v1 | `proposed` | Proto đang bị copy, package/service name cũ khác target, giá dùng `double` |
| Cart events v1 | `proposed` | Code cũ dùng custom envelope; target dùng CloudEvents và JSON Schema |
| Search HTTP v2 | `proposed` | Search Service đã implement v2 và gỡ hẳn v1. Chưa `implemented` vì còn thiếu contract test provider/consumer; frontend vẫn gọi `/api/v1/search/**`, route đó nay không còn backend. |

## Quy tắc chuyển trạng thái

### `proposed` → `implemented`

- Provider generate model/stub từ contract hoặc có test chứng minh response phù hợp.
- Không còn bản contract copy trong service.
- Example trong `contracts/examples` được producer serialize thành công.

### `implemented` → `verified`

- Consumer deserialize được example/fixture.
- Có test cho retry, duplicate và unknown optional field.
- CI đã có breaking-change check so với `main`.

## Những file legacy chưa được xóa

Trong giai đoạn pilot, `docs/_contracts/CONTRACT_MATRIX.md` và các `.proto` nằm
trong service vẫn được giữ để code cũ build được. Chúng được xem là tài liệu
legacy, không phải template cho contract mới. Chỉ xóa sau khi consumer đã
migrate và build/test thành công.
