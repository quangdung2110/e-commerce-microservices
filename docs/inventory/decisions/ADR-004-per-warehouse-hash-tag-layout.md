---
doc_id: inventory-adr-004
service: inventory
status: accepted
date: 2026-09-05
decision_owners:
  - Inventory Service team
supersedes: null
evidence:
  - java/services/inventory-service/src/main/resources/lua
  - java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql
---

# ADR-004 — Mô hình tồn kho per-warehouse và hash tag Redis

## Status

`Accepted`

## Context

Một SKU có thể nằm ở nhiều kho. Có hai cách mô hình hóa tồn kho:

1. **Per-SKU:** một con số tổng cho mỗi SKU; kho được quyết định sau, lúc chuẩn bị hàng.
2. **Per-warehouse:** một con số cho mỗi cặp `(SKU, kho)`; mọi thao tác phải chỉ định kho.

Cách thứ nhất đơn giản hơn nhưng có một lỗ hổng: giữ chỗ 5 đơn vị "ở đâu đó" không bảo
đảm rằng có kho nào thực sự đủ 5 đơn vị. Tổng có thể là 5 trong khi mỗi kho chỉ có 2 —
đơn hàng được nhận nhưng không kho nào giao được.

Về phía Redis, `ADR-001` và `ADR-002` yêu cầu một Lua script chạm tới bốn key cùng lúc:
stock hash, idempotency key, stream outbox và cờ suspension. Redis Cluster chỉ cho phép
một script truy cập các key **cùng slot**; script chạm key khác slot bị từ chối ở tầng
Redis.

## Decision

**Đơn vị nhất quán là `(sku_id, warehouse_id)`. Mọi key Redis của một đơn vị đó dùng
chung hash tag `{sku_id:warehouse_id}`.**

Cụ thể:

- `physical_inventory` có unique constraint trên `(sku_id, warehouse_id)`; đó là khóa
  nghiệp vụ thật, không phải `id` tự tăng.
- Mọi mutation — reserve, deduct, release — **bắt buộc** mang `warehouse_id`. Không có
  API nào mutate tồn kho ở mức SKU.
- Bốn key Redis mà script chạm tới đều mang hash tag `{sku_id:warehouse_id}`:
  - `inventory:physic:{sku_id:warehouse_id}:stock`
  - `inventory:idemp:{op}:{sku_id:warehouse_id}:order:{order_id}`
  - `inventory:stream:{sku_id:warehouse_id}:events`
  - `inventory:suspended:{sku_id:warehouse_id}`
- Tổng khả dụng per-SKU được **tính** khi cần (lúc chọn kho, lúc phát event), không
  được lưu như một giá trị có thể mutate độc lập.

## Alternatives considered

**Tồn kho per-SKU với việc phân kho lúc chuẩn bị hàng.** Đơn giản hơn nhiều, ít dòng
dữ liệu hơn. Nhưng cho phép nhận đơn mà không kho nào giao được, và việc phân kho trở
thành một bài toán phải giải lại sau khi đã hứa với khách. Không chọn.

**Per-warehouse nhưng dùng key Redis phẳng không hash tag.** Chạy được trên Redis đơn
node. Hỏng ngay khi chuyển sang Cluster, và hỏng theo kiểu khó chẩn đoán: script bị từ
chối với lỗi cross-slot chỉ với một số cặp key nhất định, tùy thuộc cách hash rơi.

**Hash tag chỉ theo `{sku_id}`.** Đưa mọi kho của một SKU vào cùng slot, cho phép script
thao tác nhiều kho cùng lúc. Nhưng tạo hot slot cho SKU bán chạy và làm mất khả năng
phân tán tải theo kho. Không chọn.

## Consequences

**Tích cực:**

- Giữ chỗ luôn gắn với một kho có thật và đủ hàng.
- Phạm vi tranh chấp nhỏ nhất có thể: hai kho khác nhau của cùng SKU không chặn nhau.
- Script Lua luôn hợp lệ trên Redis Cluster.
- Tải Redis phân tán theo `(SKU, kho)` chứ không dồn vào một slot.

**Tiêu cực:**

- Caller **phải** biết kho trước khi giữ chỗ. Đây là lý do route plan tồn tại, và là lý
  do checkout có thêm một bước.
- Không có thao tác atomic nào trải trên nhiều kho hoặc nhiều SKU. Request nhiều item
  vì thế **không phải transaction** — nó là một chuỗi mutation độc lập cộng với logic
  bù trừ ở application. Đây là hệ quả quan trọng nhất của quyết định này.
- Số dòng dữ liệu tăng theo tích số SKU × kho.
- Đổi cách đặt tên key Redis là một cuộc migration dữ liệu, không phải thay đổi cấu
  hình: key cũ và key mới rơi vào slot khác nhau.
- Thêm bất kỳ key nào vào script sau này đều phải mang đúng hash tag, nếu không script
  hỏng trên Cluster mà chạy bình thường trên môi trường dev đơn node.

## Migration impact

- **Code:** mọi API và Activity mutate tồn kho phải nhận `warehouse_id` bắt buộc.
- **Data:** unique constraint `(sku_id, warehouse_id)` là khóa upsert của restock.
- **Deployment:** môi trường dev nên chạy Redis ở chế độ cluster, nếu không lỗi
  cross-slot chỉ lộ ra ở production.
- **Contract:** route plan phải trả `warehouse_id` cụ thể cho từng item; `null` không
  hợp lệ trong plan đã phát hành.

## Verification

- Header của cả ba Lua script tại
  [`src/main/resources/lua`](../../../java/services/inventory-service/src/main/resources/lua)
  ghi rõ bốn key dùng chung hash tag `{sku:wh}`.
- `uq_physical_inventory_sku_warehouse` và `uq_inventory_reservations_active` trong
  [migration V1](../../../java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql)
  đều lấy `(sku, kho)` làm khóa.
- Cần bổ sung: test chạy trên Redis Cluster thật để chứng minh không có lỗi cross-slot.
  Chưa được ghi nhận.
