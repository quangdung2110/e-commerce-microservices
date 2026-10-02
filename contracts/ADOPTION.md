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
| Inventory HTTP v1 | `proposed` | Target có Workflow, Storefront Availability Read, ADMIN và Search Export API; code Java legacy không được dùng lại, chưa có implementation mới (runtime đã chốt Java/Spring Boot + PostgreSQL theo Inventory ADR-023). |
| Inventory Events v1 | `proposed` | Target có ba CloudEvents fact; Search nhận per-SKU availability sau readiness, Order chỉ nhận physical dispatch và inspection completion (Inventory ADR-020); sequence do sequencer gán theo thứ tự commit (ADR-022); producer/consumer chưa có contract test. |
| Order HTTP v1 | `proposed` | Public Buyer/Admin và internal Checkout/Finance-Ops contract đã chốt; chưa có Order runtime, provider test hoặc consumer test. |
| Order Events v1 | `proposed` | Chỉ phát `ORDER_PAID` cho Cart và `ORDER_COMPLETED` cho Review. Cart consumer đã đọc CloudEvents v1; Review consumer đã đọc completed event, nhưng chưa có Order producer/provider test. |
| Review HTTP v1 | `proposed` | Service chưa có runtime; contract chốt public approved-review read, buyer media/create/edit/report và admin moderation/takedown. |
| Promotion HTTP v1 | `implemented` | `java/services/promotion-service` phục vụ đủ 24 operation; provider test MockMvc (`HttpContractIntegrationTest`) kiểm identity, ETag/If-Match, problem+json và wire shape. `workloadIdentity` là header `X-Service-Identity`; `VoucherEvaluation` thêm `distribution_mode`. Chưa có consumer test từ Checkout/Order. |
| Promotion Events v1 | `implemented` | Promotion ghi `product-markdown-snapshot.v1` vào outbox (JSON Schema contract test `MarkdownSnapshotContractTest`, connector `debezium-connector-promotion-outbox.json`) và consume `product-deleted.v1`; Product consumer đã chấp nhận INACTIVE tombstone (migration V2). Chưa có E2E qua Debezium thật. |

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
