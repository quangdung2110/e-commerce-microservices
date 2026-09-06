---
doc_id: inventory-adr-005
service: inventory
status: accepted
date: 2026-09-05
decision_owners:
  - Inventory Service team
supersedes: null
evidence:
  - java/services/inventory-service/src/main/resources/lua/deduct_inventory.lua
---

# ADR-005 — Đóng băng khi phát hiện drift, mở khóa thủ công

## Status

`Accepted`

## Context

[ADR-001](./ADR-001-redis-hot-path-commit-source.md) chấp nhận rằng Redis và PostgreSQL
có thể lệch nhau trong một cửa sổ thời gian. Nhưng có một loại sai lệch khác nguy hiểm
hơn nhiều: **stock hash tự mâu thuẫn với chính nó** — `available` không bằng
`total - reserved`.

Khi điều đó xảy ra, không con số nào trong hash còn đáng tin. Tiếp tục mutate có thể
bán hàng không có, hoặc khóa vĩnh viễn hàng đang có. Nguyên nhân có thể là bug, thao
tác thủ công sai, khôi phục Redis không đầy đủ, hoặc một lỗi hạ tầng nào đó chưa biết.

Thiết kế trước từng có cơ chế tự phục hồi: phát hiện sai lệch thì tự tính lại từ
PostgreSQL. Cơ chế đó đã bị loại bỏ, vì "tính lại từ PostgreSQL" chỉ đúng khi consumer
lag bằng 0 — mà lúc phát hiện sai lệch thì đó chính là điều không thể giả định.

## Decision

**Khi Lua script phát hiện invariant sai, dừng ngay và đóng băng `(SKU, kho)` đó. Chỉ
người mới được mở khóa, sau khi đã đối soát.**

Cụ thể:

- Script Deduct kiểm tra `available == total - reserved` **trước** khi mutate và kiểm
  tra kết quả **sau** khi tính, trả mã drift ở cả hai điểm.
- Khi gặp drift: không mutate gì, không retry, không rollback.
- Application đặt `inventory:suspended:{sku_id:warehouse_id}` với TTL 24 giờ và phát alert.
- **Mọi** Lua script kiểm tra cờ suspension ở dòng đầu tiên, trước mọi kiểm tra khác.
- Mở khóa chỉ qua `POST /v1/inventory/admin/unlock-sku`, có ghi lý do.

## Alternatives considered

**Tự tính lại từ PostgreSQL.** Hấp dẫn vì không cần người can thiệp. Nhưng
`physical_inventory` chỉ đúng khi consumer đã bắt kịp; ghi đè stock hash lúc lag còn
cao sẽ xóa mất mutation chưa materialize và biến một sai lệch thành mất dữ liệu. Không
chọn — đây là lý do cơ chế cũ bị loại bỏ.

**Ghi log rồi chạy tiếp.** Giữ dịch vụ chạy liên tục, nhưng mỗi mutation tiếp theo làm
sai lệch sâu thêm và làm việc đối soát khó hơn. Đổi một sự cố nhìn thấy được lấy một
sự cố im lặng.

**Đóng băng toàn bộ service.** An toàn tuyệt đối, nhưng một SKU hỏng làm chết cả cửa
hàng. Phạm vi ảnh hưởng quá lớn so với phạm vi vấn đề.

**Đóng băng ở PostgreSQL thay vì Redis.** Sẽ bền hơn khi Redis mất dữ liệu, nhưng buộc
Lua script phải truy vấn database — mâu thuẫn với `ADR-001`.

## Consequences

**Tích cực:**

- Không mutation nào chạy trên dữ liệu đã biết là sai.
- Phạm vi ảnh hưởng nhỏ nhất có thể: đúng một `(SKU, kho)`.
- Kiểm tra cờ nằm ở dòng đầu script nên gần như không tốn chi phí.
- Mỗi lần đóng băng là một tín hiệu rõ ràng cho vận hành, không phải một dòng log chìm.

**Tiêu cực:**

- SKU bị đóng băng là SKU không bán được. Không có suy giảm mềm.
- Cần người trực xử lý; không tự lành.
- **TTL 24 giờ vừa là dọn rác vừa là cách hệ thống âm thầm tự mở khóa.** Nếu không ai
  xử lý trong 24 giờ, cờ biến mất và mutation lại chạy trên dữ liệu chưa được đối soát.
  Đây là lỗ hổng thật của thiết kế, không phải chi tiết nhỏ.
- Đặt cờ là thao tác Redis riêng sau khi script trả về; nếu Redis chết đúng lúc đó,
  stock item **không** được bảo vệ.
- Request đang bay lúc cờ được đặt vẫn chạy xong. Cờ chỉ chặn request sau đó.
- Chỉ script Deduct kiểm tra invariant. Reserve và Release không kiểm tra, nên một sai
  lệch có thể tồn tại một thời gian trước khi bị phát hiện.
- Việc mở khóa không được ghi vào `inventory_ledger` (nó không phải biến động tồn kho),
  chỉ vào log ứng dụng — một khoảng trống audit.

**Technical debt được chấp nhận:** không có reconciliation tự động; TTL có thể tự mở
khóa mà không ai biết.

## Migration impact

- **Code:** mọi script mới phải kiểm tra cờ suspension ở dòng đầu.
- **Vận hành:** cần alert kích hoạt ngay ở lần drift đầu tiên, và một alert riêng cho
  cờ tồn tại quá lâu — đặc biệt phải kêu **trước** khi TTL hết hạn.
- **Runbook:** quy trình đối soát phải nêu rõ điều kiện tiên quyết là consumer lag về 0
  trước khi ghi đè bất cứ thứ gì.

## Verification

- [`deduct_inventory.lua`](../../../java/services/inventory-service/src/main/resources/lua/deduct_inventory.lua)
  kiểm tra invariant ở hai điểm và trả mã `-1` với lý do drift.
- Cả ba script đều bắt đầu bằng kiểm tra `EXISTS` trên key suspension.
- Cần bổ sung: alert cảnh báo trước khi TTL hết hạn, một quyết định rõ ràng về việc có
  nên bỏ TTL hay không, và một lần diễn tập runbook đối soát. Chưa hạng mục nào hoàn thành.
