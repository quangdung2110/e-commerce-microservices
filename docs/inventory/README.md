---
doc_id: inventory-overview
service: inventory
scope: current
status: proposed
owners:
  - Inventory Service team
evidence:
  - java/services/inventory-service
verified_against_commit: null
---

# Inventory Service — Service Overview

> Điểm bắt đầu để hiểu Inventory Service trong 5–10 phút. Tài liệu được viết lại từ
> bộ thiết kế cũ (`inventory_db_design`, `inventory_redis_design`, `inventory_events`,
> `inventory_endpoints`, `inventory_workflow`, `inventory_workers`,
> `smart_routing_engine`) theo cấu trúc chuẩn tại
> [`docs/_templates/service-documentation`](../_templates/service-documentation/README.md).

## 1. Service status

| Khía cạnh | Trạng thái |
|---|---|
| Service | `running` — đã có implementation tại [`java/services/inventory-service`](../../java/services/inventory-service) |
| Runtime | Java (theo [`contracts/catalog/services.yaml`](../../contracts/catalog/services.yaml)) |
| Tài liệu | `proposed` — nội dung phản ánh thiết kế đã chốt, **chưa** được đối chiếu với code |
| Contract adoption | `not_started` — HTTP v1, internal export và AsyncAPI stock v1 đều ở trạng thái `proposed` |
| Scope | `current` — mô tả cả contract legacy đang chạy lẫn contract đích v1 |

Trạng thái triển khai và độ tin cậy của tài liệu là hai chiều khác nhau: service đã
chạy nhưng không tài liệu nào trong thư mục này được đánh dấu `verified`. Mọi khẳng
định về hành vi runtime cần đối chiếu code trước khi dùng làm cơ sở vận hành.

## 2. Mục tiêu

Inventory Service trả lời một câu hỏi duy nhất cho toàn hệ thống: **một SKU còn bán
được bao nhiêu, ở kho nào, và ai đang giữ chỗ.** Service bảo đảm hai đơn hàng đồng
thời không bán cùng một đơn vị hàng, cung cấp kế hoạch kho để checkout biết hàng sẽ
xuất từ đâu, và giữ một sổ cái bất biến giải thích được mọi biến động tồn kho.

Kết quả nghiệp vụ: checkout không bao giờ xác nhận đơn vượt quá hàng thật có; đơn bị
hủy hoặc quá hạn thanh toán trả lại tồn kho; hàng hoàn về kho được ghi nhận đủ trước
khi tiền được hoàn.

## 3. Boundary

**Inventory chịu trách nhiệm:** kho hàng (`warehouses`), tồn kho vật lý theo
`(sku_id, warehouse_id)`, reservation của đơn hàng, sổ cái biến động tồn kho, thuật
toán chọn kho, và snapshot tồn kho khả dụng per-SKU công bố cho Search.

**Inventory không chịu trách nhiệm:**

| Chức năng gần biên | Owner thật | Vai trò của Inventory |
|---|---|---|
| Catalog, SKU, giá | Product | Chỉ lưu `sku_id`/`product_id` denormalized, không validate SKU tồn tại trong catalog |
| Trạng thái đơn hàng và item | Order | Phát signal `INVENTORY_SHIP_TO_CUSTOMER`; không đọc/ghi `order_items.status` |
| Điều phối Saga, timer hết hạn giữ chỗ | Order (Temporal) | Cung cấp Activity Reserve/Deduct/Release; **không** tự quét reservation quá hạn |
| Quyết định hoàn tiền | Order + Payment | Chỉ phát signal "đã nhận đủ hàng hoàn" |
| Vận chuyển | Không còn service riêng | Admin xác nhận xuất kho qua HTTP API |
| Hiển thị tồn kho trên UI | Search | Nhận stock snapshot v1 trực tiếp từ Inventory, không qua Product |

**Transaction boundary:** một transaction PostgreSQL của Inventory chỉ chứa dữ liệu
Inventory sở hữu cộng outbox row của chính nó. Không service nào được ghi trực tiếp
vào database Inventory, và Inventory không đọc database của service khác.

## 4. Data ownership

| Kho lưu trữ | Dữ liệu | Vai trò |
|---|---|---|
| PostgreSQL | `warehouses`, `physical_inventory`, `inventory_reservations`, `inventory_ledger`, `inventory_outbox` | System of record durable |
| Redis | Stock hash per `(sku, kho)`, idempotency key, stream outbox, cờ suspension, cache route plan | Commit source của hot path; xem [ADR-001](./decisions/ADR-001-redis-hot-path-commit-source.md) |
| Kafka | `t.inventory.events` | Kênh phát, không phải database |

`sku_id` và `product_id` là dữ liệu ngoại lai được denormalize từ request restock đầu
tiên; Inventory không phải nguồn chân lý cho chúng và không tự lookup Product lúc chạy.

Consumer được cấp contract đọc tồn kho là Search. Cơ chế đang chạy là event
`INVENTORY_STOCK_CHANGED`; contract đích là stock snapshot v1 kèm export API và
**chưa được implement** — xem [Data §5](./data.md#5-khoảng-cách-giữa-thiết-kế-đích-và-schema-hiện-tại).
Product **không** consume stock event và không phải đường trung chuyển availability —
xem [Product boundary](../product/README.md#boundary).

## 5. Capabilities

| Capability | Kết quả nghiệp vụ |
|---|---|
| Lập kế hoạch kho | Trả một route plan có `warehouse_id` cụ thể cho từng item, có hạn dùng |
| Giữ chỗ tồn kho | Chuyển một lượng hàng từ khả dụng sang đã giữ chỗ, atomic per `(SKU, kho)` |
| Xác nhận bán | Trừ hàng thật sau khi thanh toán thành công |
| Giải phóng giữ chỗ | Trả hàng về khả dụng khi đơn hủy hoặc hết hạn thanh toán |
| Nhập kho | Tạo hoặc cộng tồn kho vật lý; là entry point khởi tạo duy nhất |
| Nhận hàng hoàn | Cộng hàng về kho và xác nhận đã nhận đủ tập item của một yêu cầu hoàn |
| Báo xuất kho | Phát signal để Order chuyển item sang đã giao |
| Công bố tồn kho | Phát tổng khả dụng per-SKU ra ngoài mỗi khi tồn kho đổi |
| Khóa/mở khóa an toàn | Đóng băng một `(SKU, kho)` khi phát hiện sai lệch số liệu, chỉ mở bằng tay |

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Checkout | HTTP | Lập route plan cho preview | Required | [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml) |
| Inbound | Order (Temporal worker) | Temporal SDK | Reserve, Deduct, Release | Required | [Interfaces §3](./interfaces.md) |
| Inbound | Order | HTTP nội bộ | Đăng ký refund expectation | Required | [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml) |
| Inbound | Admin qua Gateway | HTTP | Restock, ship-to-customer, unlock SKU | Required | [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml) |
| Inbound | Search | HTTP nội bộ (service identity) | Bootstrap/recovery snapshot tồn kho | **Target, chưa implement** | [Internal OpenAPI v1](../../contracts/http/inventory/internal/v1/openapi.yaml) |
| Outbound | Search | Kafka | Tổng khả dụng per-SKU | Required | Đang dùng `INVENTORY_STOCK_CHANGED` (chưa có contract); đích là [AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml) |
| Outbound | Order | Kafka | `INVENTORY_RETURN_RECEIVED_ALL`, `INVENTORY_SHIP_TO_CUSTOMER` | Required | Chưa có contract — xem [Known gaps](#9-known-gaps) |
| Outbound | PostgreSQL | Database | Ledger, reservation, projection, outbox | Required | [Data](./data.md) |
| Outbound | Redis | Cache/Stream | Hot path commit, idempotency, route plan | Required | [Data](./data.md) |
| Outbound | Kafka + Debezium | Events/CDC | Phát event từ outbox và stream | Required | [Interfaces](./interfaces.md) |

## 7. Critical invariants

| ID | Invariant |
|---|---|
| `INV-INV-001` | Với mọi `(sku_id, warehouse_id)`: `available + reserved == total`, và cả ba đều `>= 0` |
| `INV-INV-002` | Trong PostgreSQL: `actual_quantity >= reserved_quantity >= 0` |
| `INV-INV-003` | Mỗi `(operation, sku_id, warehouse_id, order_id)` chỉ được áp dụng đúng một lần |
| `INV-INV-004` | Mỗi `event_id` tạo tối đa một dòng `inventory_ledger`; ledger là append-only |
| `INV-INV-005` | `INVENTORY_RETURN_RECEIVED_ALL` phát đúng một lần cho mỗi `(order_id, refund_request_id)` |
| `INV-INV-006` | Ledger chỉ ghi biến động `actual_quantity`; giữ chỗ, hủy giữ chỗ và xuất kho không làm đổi `balance_after` |
| `INV-INV-007` | Khi phát hiện sai lệch invariant, `(SKU, kho)` bị đóng băng và không mutation nào được áp dụng cho tới khi có can thiệp thủ công |
| `INV-INV-008` *(target)* | `stock_revision` của một SKU tăng đơn điệu đúng một lần mỗi lần state thay đổi; consumer chỉ áp dụng revision lớn hơn. **Chưa có trong schema hiện tại** — hệ quả là event tồn kho đang phát không có ordering guard nào |

Điều kiện áp dụng, nơi enforcement và lỗi tương ứng nằm ở [Domain Model](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Component, processing model, consistency và concurrency | Khi cần hiểu vì sao Redis đứng trước PostgreSQL |
| [Domain](./domain.md) | Thuật ngữ, rule, state machine và domain error | Khi thay đổi business logic |
| [Interfaces](./interfaces.md) | HTTP, Temporal Activity và event semantics | Khi tích hợp với service |
| [Workflows](./workflows.md) | 10 luồng end-to-end kèm failure path | Khi sửa một use case |
| [Data](./data.md) | Ownership, schema, cache, outbox và lifecycle | Khi sửa schema hoặc dữ liệu |
| [Operations](./operations.md) | SLI, alert, failure mode, runbook và worker | Khi deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy, build và test local | Khi phát triển service |
| [Decisions](./decisions/README.md) | 10 ADR ghi lại các quyết định kiến trúc | Khi cần hiểu lý do của thiết kế |
| [Flyway migrations](../../java/services/inventory-service/src/main/resources/db/migration) | Schema chuẩn của PostgreSQL | Khi cần schema chính xác |

## 9. Known gaps

- **Stock snapshot v1 là thiết kế đích chưa được implement.** Không có bảng
  `sku_stock_state`, không có `stock_revision`, không có endpoint export, và ràng buộc
  `event_type` của bảng outbox hiện **cấm** insert event snapshot v1. Hai contract
  [AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml) và
  [internal export OpenAPI](../../contracts/http/inventory/internal/v1/openapi.yaml) vì
  vậy chưa có gì ở phía provider — chi tiết tại
  [Data §5](./data.md#5-khoảng-cách-giữa-thiết-kế-đích-và-schema-hiện-tại).
- **Event tồn kho đang chạy không có ordering guard.** `INVENTORY_STOCK_CHANGED` không
  mang revision, nên consumer không có cách nào bỏ event cũ đến muộn. Đây là lý do
  tồn tại của [ADR-007](./decisions/ADR-007-stock-snapshot-v1-for-search.md).
- **Không event nào trên `t.inventory.events` có contract machine-readable.**
  `INVENTORY_STOCK_MUTATION`, `INVENTORY_STOCK_CHANGED`,
  `INVENTORY_RETURN_RECEIVED_ALL` và `INVENTORY_SHIP_TO_CUSTOMER` chỉ được mô tả bằng
  văn bản.
- **Cửa sổ phân kỳ Redis ↔ PostgreSQL.** Hot path commit ở Redis rồi mới materialize
  sang PostgreSQL qua Kafka. Khi relay hoặc ledger worker tụt lại, `physical_inventory`
  stale trong khi Redis đã đúng. Không có reconciliation tự động — xem
  [ADR-001](./decisions/ADR-001-redis-hot-path-commit-source.md) và [Operations](./operations.md).
- **HTTP API lệch quy ước chung của dự án.** Body dùng camelCase (Product và Auth dùng
  snake_case), lỗi trả về dạng `{error, message, details, timestamp}` chứ không phải
  RFC 9457 Problem Details, và path dùng `/v1/inventory/...` thay vì `/api/v1/...`.
  [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml) mô tả đúng hành vi hiện
  tại; thống nhất ba điểm này là breaking change chưa được lên lịch.
- **Tài liệu thiết kế cũ gọi `city_code` là `province_code`.** Schema và code dùng
  `city_code`/`cityCode`. Tên trong tài liệu mới theo code.
- **Không có contract test.** Trạng thái `verified` chưa đạt được cho bất kỳ interface nào.
- **Đối soát code chỉ ở mức schema, contract và Lua script.** Migration, DTO, controller
  và Lua script đã được đọc khi viết tài liệu này; tầng application, worker và consumer
  thì chưa, nên `verified_against_commit` vẫn là `null` cho mọi file.
