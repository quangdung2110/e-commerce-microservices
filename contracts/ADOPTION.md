# Contract Adoption

File này phân biệt rõ ba trạng thái dễ bị nhầm:

- `proposed`: contract là thiết kế đích, code chưa chắc đã tuân thủ.
- `implemented`: provider đã dùng contract nhưng chưa có đủ kiểm tra consumer.
- `verified`: provider và consumer đều vượt qua contract test.

## Pilot hiện tại

| Contract | Trạng thái | Khoảng cách cần migrate |
|---|---|---|
| Cart HTTP v1 | `implemented` | `go/services/cart-service` phục vụ đủ bốn operation (delta, Money, `application/problem+json`, idempotency bằng Lua, snapshot nội bộ cho Checkout); provider test `internal/api/router` và test script trên Redis (cả miniredis lẫn Redis thật qua `CART_TEST_REDIS_ADDR`). Còn thiếu: test validate response với OpenAPI và consumer test với Checkout thật. |
| Product gRPC v1 | `proposed` | Proto đang bị copy, package/service name cũ khác target, giá dùng `double` |
| Cart events v1 | `implemented` | Cart ghi `CART_ABANDONED` vào Redis Stream outbox rồi relay sang Kafka bằng CloudEvents với `id` xác định; Notification đã consume. Test `internal/workers/relay` kiểm cấu trúc envelope, `id` ổn định, không XACK khi Kafka lỗi và DLQ cho entry hỏng. Còn thiếu: test validate payload với JSON Schema và consumer test với producer thật. |
| Search HTTP v2 | `proposed` | Search Service đã implement v2 và gỡ hẳn v1. Chưa `implemented` vì còn thiếu contract test provider/consumer; frontend vẫn gọi `/api/v1/search/**`, route đó nay không còn backend. |
| Inventory HTTP v1 | `implemented` | `java/services/inventory-service` phục vụ đủ Workflow, Storefront Availability Read, ADMIN và Search Export API (Inventory ADR-023, ADR-027); provider test MockMvc + Testcontainers kiểm identity allowlist, idempotency/replay, catalog `code` và wire shape. Search client đã gửi `X-Service-Identity`; chưa có consumer contract test từ Checkout/Order/BFF/Search hay ADMIN UI (UI còn phải sinh lại type theo ADR-027). |
| Inventory Events v1 | `implemented` | Producer ghi ba CloudEvents fact qua outbox → fenced sequencer → `published_event` → Debezium (`debezium-connector-inventory-outbox.json`); test validate event phát ra với JSON Schema và smoke test Debezium thật đã chạy. Consumer Order chưa có; Search consumer chưa có contract test với producer thật. |
| Order HTTP v1 | `implemented` | `java/services/order-service` phục vụ đủ public Buyer/Admin và internal Checkout/Finance-Ops (Order ADR-020); provider test MockMvc + Testcontainers + Temporal in-memory validate response với schema OpenAPI. Chưa có consumer test từ Checkout (chưa có runtime), frontend hay Finance/Ops. |
| Order Events v1 | `implemented` | Producer ghi `ORDER_PAID`, `ORDER_COMPLETED` và năm `NOTIFICATION_REQUESTED` qua multi-destination outbox + `debezium-connector-order-outbox.json`; test validate payload với JSON Schema. connector đã smoke test với Debezium/Kafka thật. Cart/Review/Notification consumer chưa có contract test với producer thật. |
| Review HTTP v1 | `proposed` | Service chưa có runtime; contract chốt public approved-review read, buyer media/create/edit/report và admin moderation/takedown. |
| Promotion HTTP v1 | `implemented` | `java/services/promotion-service` phục vụ đủ 24 operation; provider test MockMvc (`HttpContractIntegrationTest`) kiểm identity, ETag/If-Match, problem+json và wire shape. `workloadIdentity` là header `X-Service-Identity`; `VoucherEvaluation` thêm `distribution_mode`. Chưa có consumer test từ Checkout/Order. Promotion ADR-018 đã có code và provider test: `POST /internal/v1/promotion-voucher-options` cho khay voucher của Checkout, `context` tùy chọn ở `PUT /api/v1/me/promotion-vouchers/{promotion_id}`, và `user_tier` tùy chọn ở calculate/Reserve (DTO cũ bắt buộc nó nên Checkout và Order bị `400`). |
| Checkout HTTP v1 | `implemented` | `java/services/checkout-service` phục vụ đủ bốn operation; contract test validate response thành công và `CheckoutProblem` với OpenAPI, enum lỗi khớp OpenAPI và bảng `interfaces.md`, mọi fixture provider validate với contract của provider (Cart, User, Inventory, Promotion, Order), test cạnh tranh trên Redis thật và BlockHound. Chưa có E2E qua provider thật; frontend mới sinh type, chưa gọi. |
| Promotion Events v1 | `implemented` | Promotion ghi `product-markdown-snapshot.v1` vào outbox (JSON Schema contract test `MarkdownSnapshotContractTest`, connector `debezium-connector-promotion-outbox.json`) và consume `product-deleted.v1`; Product consumer đã chấp nhận INACTIVE tombstone (migration V2). Chưa có E2E qua Debezium thật. |

| User HTTP v1 | `proposed` | `go/services/user-service` phục vụ đủ chín operation public và `checkout-snapshot` nội bộ; route, status, mã lỗi và wire shape đã được đối chiếu thủ công với OpenAPI và có integration test (Testcontainers) cho từng nhóm. Chưa `implemented` vì không có test tự động validate response với schema OpenAPI, và chưa có consumer test từ Checkout thật (Checkout dùng fixture). Cổng internal xác thực qua mesh + allowlist `X-Service-Identity` (User ADR-008), không còn mTLS ở tầng app. |
| User Events v1 | `proposed` | Producer publish trực tiếp `USER_PROFILE_UPDATED`/`USER_PREFERENCES_UPDATED` (không outbox, có reconciliation hằng ngày); unit test kiểm envelope và payload. Chưa `implemented` vì chưa validate payload với JSON Schema; consumer Notification đã có, Chat chưa xác minh. User cũng consume `complaint-received` của Notification (User ADR-009). |
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
