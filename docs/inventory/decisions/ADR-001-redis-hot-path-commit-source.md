---
doc_id: inventory-adr-001
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

# ADR-001 — Redis là commit source của đường nóng, PostgreSQL là sổ cái durable

## Status

`Accepted`

Quyết định này đã được implement. Ngày ghi ở trên là ngày ADR được viết lại từ tài liệu
thiết kế cũ, không phải ngày quyết định được đưa ra lần đầu.

## Context

Reserve, Deduct và Release nằm trên đường nóng của checkout. Chúng phải:

- Atomic ở mức `(sku_id, warehouse_id)` — hai đơn hàng đồng thời không được bán cùng
  một đơn vị hàng.
- Có độ trễ thấp, vì mỗi lần đặt hàng gọi ít nhất một lần và một giỏ nhiều item gọi
  nhiều lần.
- Chịu được tình huống nhiều đơn cùng tranh một SKU bán chạy.

Cách làm thông thường là `SELECT ... FOR UPDATE` trên dòng tồn kho trong PostgreSQL.
Với SKU bán chạy, mọi request cùng tranh một dòng, hàng đợi lock dài ra và độ trễ tăng
theo số request đồng thời — đúng lúc hệ thống cần nhanh nhất.

## Decision

**Đường nóng commit ở Redis. PostgreSQL là sổ cái durable được materialize bất đồng bộ.**

Cụ thể:

- Trạng thái tồn kho đang chạy nằm ở Redis hash `inventory:physic:{sku_id:warehouse_id}:stock`.
- Mọi mutation của đường nóng đi qua một Lua script duy nhất; Redis chạy script đơn
  luồng nên script chính là cơ chế tuần tự hóa, thay cho lock database.
- Kết quả trả về caller ngay sau khi script chạy xong; **không** chờ PostgreSQL.
- `physical_inventory`, `inventory_reservations` và `inventory_ledger` được Ledger
  Worker ghi sau, trong một transaction, khi nhận được event mutation từ Kafka.

Không thuộc phạm vi quyết định này: đường quản trị (restock, hàng hoàn, xác nhận xuất
kho) vẫn commit ở PostgreSQL rồi mới đồng bộ Redis.

## Alternatives considered

**Khóa bi quan trên PostgreSQL (`SELECT FOR UPDATE`).** Đơn giản, một nguồn chân lý
duy nhất, không có cửa sổ phân kỳ. Nhưng SKU bán chạy tạo hàng đợi lock, và độ trễ
đường nóng gắn với độ trễ database. Không chọn vì đây chính là kịch bản service phải
phục vụ tốt nhất.

**Khóa lạc quan bằng cột version.** Không giữ lock, nhưng dưới tranh chấp cao tỉ lệ
retry tăng nhanh, và mỗi retry là một vòng round-trip database. Đổi vấn đề hàng đợi
lấy vấn đề retry storm.

**Redis làm cache, PostgreSQL vẫn là commit source.** Đọc nhanh từ Redis rồi ghi vào
PostgreSQL. Không giải quyết được gì: điểm nghẽn nằm ở ghi, không phải đọc.

**Redis làm nơi lưu duy nhất, bỏ PostgreSQL cho tồn kho.** Nhanh nhất, nhưng mất sổ
cái audit và mất khả năng phục hồi khi Redis mất dữ liệu. Không chấp nhận được với dữ
liệu tồn kho.

## Consequences

**Tích cực:**

- Không có lock database trên đường nóng; độ trễ không tăng theo mức tranh chấp.
- Mutation và event được ghi trong cùng một lần chạy script, nên không mất event khi
  crash giữa chừng (chi tiết ở [ADR-002](./ADR-002-redis-stream-outbox.md)).
- PostgreSQL chỉ chịu tải ghi theo lô từ một worker, không phải theo từng request.

**Tiêu cực:**

- **Có một cửa sổ mà PostgreSQL sai còn Redis đúng.** Đây là hệ quả trực tiếp, không
  phải lỗi implementation. Độ rộng cửa sổ bằng độ trễ relay cộng độ trễ ledger worker.
- Warehouse Selection Engine đọc `physical_inventory`, tức là chọn kho dựa trên số liệu
  có thể cũ. Hệ quả thực tế: route plan có thể trỏ vào kho vừa hết hàng.
- Mất Redis là mất khả năng bán hàng, kể cả khi PostgreSQL hoàn toàn khỏe mạnh.
- Không thể fallback sang PostgreSQL khi Redis chết: đọc `physical_inventory` sẽ bỏ qua
  mọi mutation chưa materialize và dẫn tới bán vượt. Fail-closed là lựa chọn duy nhất.
- Hai nguồn dữ liệu có thể lệch nhau vì lý do ngoài dự kiến, và không có cơ chế tự sửa
  — xem [ADR-005](./ADR-005-fail-safe-suspension.md).
- Mọi thao tác vận hành trên Redis đều nguy hiểm: ghi đè stock hash khi consumer còn
  lag sẽ xóa mất mutation chưa materialize.

**Technical debt được chấp nhận:** không có job reconciliation tự động giữa Redis và
PostgreSQL. Đối soát là quy trình thủ công theo runbook.

## Migration impact

- **Code:** đường nóng phải đi qua Lua script, không được ghi Redis bằng lệnh rời rạc.
- **Data:** khi khởi tạo hoặc sau restore, stock hash phải được dựng lại từ
  `physical_inventory` **sau khi** consumer lag về 0.
- **Deployment:** Redis trở thành dependency chặn readiness.
- **Vận hành:** cần dashboard so sánh tổng khả dụng giữa Redis và `physical_inventory`;
  khoảng cách giữa hai đường là chỉ số sức khỏe chính.

## Verification

- Ba Lua script tại
  [`src/main/resources/lua`](../../../java/services/inventory-service/src/main/resources/lua)
  chứa toàn bộ logic mutate và đều `XADD` event trong cùng script.
- Cần bổ sung: metric đo độ trễ materialize, load test đo độ trễ đường nóng dưới tranh
  chấp cao, và một bài test chứng minh hai reserve đồng thời trên cùng stock item không
  bán vượt. Chưa bài nào được ghi nhận là đã chạy.
