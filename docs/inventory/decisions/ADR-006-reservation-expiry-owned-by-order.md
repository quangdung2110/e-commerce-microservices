---
doc_id: inventory-adr-006
service: inventory
status: accepted
date: 2026-09-05
decision_owners:
  - Inventory Service team
supersedes: null
evidence:
  - java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql
---

# ADR-006 — Timer hết hạn giữ chỗ thuộc Order, Inventory không quét

## Status

`Accepted`

## Context

Giữ chỗ tồn kho phải có hạn. Nếu khách không thanh toán, hàng phải được trả về khả dụng
thay vì bị khóa mãi mãi.

Có hai nơi có thể chịu trách nhiệm: Inventory tự quét bảng reservation tìm dòng quá hạn
rồi hủy, hoặc Order — nơi đang chạy một Temporal workflow cho mỗi đơn hàng — đặt một
timer và gọi ngược lại Inventory khi timer nổ.

Điểm mấu chốt: **Order đã biết đơn hàng đang ở bước nào.** Inventory thì không. Một
reservation quá `expires_at` có thể là đơn đã chết thật, hoặc là đơn mà thanh toán vừa
thành công cách đây một giây và bước deduct chưa kịp chạy. Inventory không có cách nào
phân biệt hai trường hợp đó.

## Decision

**Order Temporal workflow sở hữu timer. Inventory không có worker nào quét reservation
quá hạn.**

Cụ thể:

- Sau khi reserve thành công, workflow của Order đặt một timer (mặc định 30 phút).
- Timer nổ trong khi workflow còn ở trạng thái trước thanh toán → Order gọi
  `RollbackInventoryActivity` với `change_type = RESERVATION_EXPIRED`.
- Inventory ghi `expires_at` lên reservation, nhưng giá trị đó chỉ để tra cứu và điều
  tra, **không** kích hoạt hành động nào.
- Index `idx_inventory_reservations_status_expiry` tồn tại để phục vụ dashboard, không
  phải để một job nào đó quét.

## Alternatives considered

**Inventory tự quét và hủy.** Không phụ thuộc Order, tự dọn dẹp được. Nhưng Inventory
không biết trạng thái đơn hàng, nên sẽ có lúc hủy giữ chỗ của một đơn vừa thanh toán
xong. Hậu quả là khách trả tiền rồi mà hàng không còn — chính là kịch bản
`INSUFFICIENT_RESERVED` mà `ADR-005` xếp vào mức nghiêm trọng nhất.

**Cả hai cùng làm, Inventory là lưới an toàn.** Nghe hợp lý, nhưng lưới an toàn vẫn
mang đúng vấn đề trên: nó vẫn có thể hủy nhầm đơn đang trong quá trình thanh toán.
Muốn an toàn thì phải hỏi Order — mà nếu đã hỏi được Order thì để Order quyết định
luôn còn đơn giản hơn.

**Dùng TTL Redis trên chính giữ chỗ.** Redis tự xóa key khi hết hạn, không cần worker.
Nhưng TTL của Redis xóa cả key chứ không hoàn tác một phần mutation, và không sinh
event nào để ghi sổ. Giữ chỗ sẽ biến mất mà sổ cái không biết.

## Consequences

**Tích cực:**

- Chỉ một nơi quyết định số phận của một đơn hàng, và nơi đó biết đủ ngữ cảnh.
- Không có race giữa hết hạn và thanh toán thành công.
- Ít đi một worker phải vận hành.
- Ledger phân biệt được "khách chủ động hủy" với "hết hạn thanh toán" nhờ hai
  `reference_type` khác nhau, dù tác động tồn kho giống hệt.

**Tiêu cực:**

- **Inventory phụ thuộc vào tính đúng đắn của Order.** Nếu workflow của Order chết,
  mất, hoặc bị hủy sai cách, giữ chỗ của nó bị treo vô hạn và không gì trong Inventory
  dọn được.
- Không có mạng an toàn nào cho giữ chỗ mồ côi. Chúng chỉ lộ ra qua truy vấn dashboard
  và phải được xử lý bằng tay.
- `expires_at` trong database trông như một cơ chế đang hoạt động nhưng thực ra không
  làm gì. Đây là mồi bẫy cho người đọc schema lần đầu — lý do cột này có comment giải
  thích ngay trong migration.
- Giá trị 30 phút nằm trong cấu hình của Order, không phải Inventory. Người vận hành
  Inventory không đổi được nó.

**Constraint mới:** reservation ở trạng thái `RESERVED` quá lâu là dấu hiệu Saga hỏng,
không phải dữ liệu cần archive. Job archive chỉ được đụng vào dòng `CANCELLED` và
`DEDUCTED`.

## Migration impact

- **Code:** không thêm scheduler quét expiry vào Inventory.
- **Contract:** `RollbackInventoryActivity` phải nhận `change_type` để phân biệt hai lý do.
- **Vận hành:** cần một truy vấn dashboard đếm reservation `RESERVED` quá hạn; con số
  này khác 0 là dấu hiệu vấn đề ở Order, không phải ở Inventory.

## Verification

- Comment trên cột `expires_at` trong
  [migration V1](../../../java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql)
  ghi rõ: "Order Temporal Saga owns the timer; Inventory does NOT scan expiry."
- Index `idx_inventory_reservations_status_expiry` được đánh dấu là dùng cho debug, không
  trigger scan.
- Cần bổ sung: dashboard đếm giữ chỗ mồ côi và một alert khi con số đó tăng. Chưa được
  ghi nhận.
