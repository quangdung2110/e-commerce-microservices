---
doc_id: inventory-adr-007
service: inventory
status: superseded
date: 2026-09-05
decision_owners:
  - Inventory Service team
  - Search Service team
  - Storefront team
supersedes: null
superseded_by: ADR-016-per-sku-search-availability
evidence:
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/http/inventory/exports/v1/openapi.yaml
  - docs/search/decisions/ADR-009-stock-projection-ownership.md
  - docs/product-detail-bff/decisions/ADR-001-availability-projection-over-live-query.md
---

# ADR-007 — Stock snapshot v1 có revision cho Search và Product Detail BFF

## Status

`Superseded` by [ADR-016](./ADR-016-per-sku-search-availability.md). This file is
historical evidence and is not implementation authority.

Đây là ADR duy nhất trong thư mục này **chưa được implement**. Contract đã tồn tại
nhưng phía provider chưa có gì — chi tiết ở phần Verification.

## Context

Search cần biết một SKU còn hàng hay không để browse/lọc, còn Product Detail BFF cần
state đó để hiển thị availability theo variant. Cơ chế đang chạy là event
`INVENTORY_STOCK_CHANGED` trên `t.inventory.events`, mang `product_id`, `sku_id`,
`available_quantity` và `change_type`.

Payload đó có ba vấn đề:

1. **Không có ordering guard.** Không trường nào cho biết state này mới hay cũ. Nếu một
   event đến muộn hoặc bị phát lại sau một event mới hơn, consumer không có cách nào biết
   để bỏ qua. Kafka giữ thứ tự trong một partition, nhưng thứ tự Kafka không sống sót
   qua replay, qua đổi số partition, hay qua việc consumer xử lý lại từ offset cũ.
2. **Không có đường bootstrap.** Search dựng index mới, hoặc Product Detail BFF phục
   hồi availability projection, thì
   không có cách nào lấy trạng thái đầy đủ ngoài việc đọc lại toàn bộ topic — mà topic
   có retention hữu hạn.
3. **Không có contract machine-readable.** Không có gì để lint, để test, hay để phát
   hiện breaking change trong CI.

Ngoài ra, thiết kế cũ từng để Product làm trung gian chuyển tồn kho tới Search. Điều đó
đã bị loại bỏ: Product không sở hữu stock và không nên là replica của nó — xem
[Product boundary](../../product/README.md#boundary).

## Decision

**Phát một snapshot đầy đủ per-SKU, có `stock_revision` tăng đơn điệu, trên một topic
riêng, kèm một endpoint export để bootstrap.**

Cụ thể:

- Một bảng `sku_stock_state` giữ trạng thái công bố per-SKU: `sku_id`, `product_id`,
  `available_quantity`, `stock_revision`, `observed_at`.
- Mọi mutation làm đổi tổng khả dụng của một SKU phải, **trong cùng transaction**,
  cập nhật `sku_stock_state` với revision kế tiếp và ghi outbox row tương ứng.
- Event `com.ecommerce.inventory.stock-snapshot.v1` trên topic
  `t.inventory.stock.events.v1`, key `sku_id`, mang **toàn bộ** state chứ không phải delta.
- Consumer dedupe theo CloudEvents `id` và chỉ áp dụng revision **lớn hơn** state đang có.
- `GET /internal/v1/inventory/stock-snapshots` export theo keyset `sku_id`, trả kèm
  Kafka offset chụp trước khi export bắt đầu; Search hoặc Product Detail BFF import rồi
  replay từ offset đó.
- Snapshot **không** mang tồn kho per-warehouse và không mang reservation.
- `INVENTORY_STOCK_CHANGED` chạy song song trong giai đoạn chuyển tiếp rồi bị gỡ.

Không thuộc phạm vi: thay đổi cách Inventory tính tồn kho, hay cho Search/Product Detail
BFF biết hàng nằm ở kho nào.

## Alternatives considered

**Thêm `stock_revision` vào `INVENTORY_STOCK_CHANGED` hiện có.** Ít việc hơn nhiều,
không cần topic mới. Nhưng thêm một trường bắt buộc mà consumer phải hiểu là breaking
change trên một topic đang có consumer khác; và vẫn không giải quyết được vấn đề
bootstrap.

**Dùng `updated_at` hoặc timestamp phát event làm ordering guard.** Không cần bảng mới.
Nhưng timestamp không đơn điệu qua nhiều instance, có thể trùng nhau ở độ phân giải
thấp, và nhảy lùi khi đồng hồ được chỉnh. Revision là số nguyên do database cấp, không
có những vấn đề đó.

**Để Search truy vấn Inventory qua API mỗi khi cần.** Đơn giản, luôn tươi. Nhưng đặt
Inventory vào đường nóng của mọi truy vấn tìm kiếm và tạo phụ thuộc đồng bộ giữa hai
service. Không chọn.

**Giữ Product làm trung gian.** Đã bị loại bỏ vì làm Product thành stock replica, vi
phạm boundary của cả hai service.

**Để Product Detail BFF truy vấn Inventory batch API trên mỗi PDP request.** Dữ liệu có
thể tươi hơn nhưng Inventory trở thành synchronous dependency của traffic đọc và cách
này vẫn không thay thế Reserve. Không chọn; rationale chi tiết nằm ở
[`Product Detail BFF ADR-001`](../../product-detail-bff/decisions/ADR-001-availability-projection-over-live-query.md).

## Consequences

**Tích cực:**

- Consumer bỏ được state cũ một cách xác định, không phụ thuộc thứ tự Kafka.
- Snapshot đầy đủ nghĩa là mất một event vẫn hội tụ khi nhận event sau.
- Có đường bootstrap không phụ thuộc retention của topic.
- Contract có AsyncAPI và OpenAPI, lint và test được.
- Topic riêng nghĩa là consumer cũ không phải hiểu event mới.

**Tiêu cực:**

- Thêm một bảng, một topic và một endpoint phải vận hành.
- Mọi transaction mutate tồn kho phải làm thêm một phép tổng hợp và một lần upsert —
  chi phí ghi tăng.
- Hai topic chạy song song trong suốt giai đoạn chuyển tiếp; event bị phát hai lần dưới
  hai hình dạng.
- Export phải giữ được tính nhất quán giữa dữ liệu trả về và offset đi kèm; làm sai chỗ
  này thì bootstrap tạo ra index thiếu hoặc thừa.
- `stock_revision` là trạng thái không bao giờ được reset. Restore database về mốc cũ
  sẽ làm revision lùi, và consumer đã thấy revision cao hơn sẽ **bỏ qua** mọi event
  sau đó — một kịch bản phục hồi cần được xử lý riêng.

## Migration impact

- **Data:** cần migration tạo `sku_stock_state`, migration nới
  `ck_inventory_outbox_event_type_enum`, và một lần backfill state cho mọi SKU đã có
  `physical_inventory` (bắt đầu từ revision 1).
- **Code:** Ledger Worker và cả ba đường ghi ở tầng quản trị phải cập nhật state trong
  cùng transaction; cần thêm controller cho endpoint export.
- **Contract:** hai contract đã tồn tại và không cần đổi.
- **Consumer:** Search phải chuyển sang topic mới, làm một lần full reindex từ export;
  Product Detail BFF phải bootstrap availability projection và replay cùng contract,
  rồi mới ngừng đọc topic cũ.
- **Rollout:** ghi cả hai loại event trước; chỉ ngừng `INVENTORY_STOCK_CHANGED` sau khi
  xác nhận không còn consumer — hiện **chưa có** danh sách consumer được xác minh, và
  đây là điều kiện chặn.

## Verification

Trạng thái hiện tại của phía provider:

| Hạng mục | Trạng thái |
|---|---|
| [AsyncAPI stock v1](../../../contracts/events/inventory/v1/asyncapi.yaml) | Đã có |
| [Current replacement Export OpenAPI](../../../contracts/http/inventory/exports/v1/openapi.yaml) | ADR-016 contract; historical endpoint removed |
| Bảng `sku_stock_state` | **Chưa có migration nào tạo** |
| Nới `ck_inventory_outbox_event_type_enum` | **Chưa** — ràng buộc hiện tại cấm insert event snapshot v1 |
| Endpoint export | **Chưa có controller** |
| Backfill | Chưa chạy |

Nghĩa là hai contract trên hiện mô tả một hành vi không tồn tại. ADR này chỉ chuyển
sang `Accepted` khi mọi hàng ở cột trái đã xong và có một lần full reindex thành công
từ export API kèm replay từ offset trả về.
