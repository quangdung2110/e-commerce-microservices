---
doc_id: inventory-allocation-policy
service: inventory
scope: target
status: proposed
owners:
  - Inventory Service team
reviewers:
  - Checkout Service team
  - Order Service team
  - Warehouse operations
evidence:
  - docs/inventory/brainstorm/design-charter.md
  - docs/inventory/brainstorm/context-map.md
  - docs/inventory/brainstorm/domain-foundation.md
  - docs/inventory/brainstorm/lifecycle-workflows.md
verified_against_commit: null
---

# Inventory Service — Allocation Policy (Phase 4)

> Working document cho policy chọn warehouse. Không thiết kế endpoint, plan storage,
> cache hay thuật toán database; các chi tiết đó thuộc Phase 6–7.

## 1. Decision trace bắt buộc

| Input đã chốt | Hệ quả policy |
|---|---|
| `INV-CH-001`, `INV-DOM-001` | Chỉ authoritative `available_to_sell` quyết định eligibility; cache/Search không có quyền chọn kho hoặc accept reserve. |
| `INV-CH-002`, `INV-DOM-002` | Mỗi order line phải được một warehouse duy nhất đáp ứng đủ toàn quantity; không split quantity qua kho. |
| `INV-CH-003`, `INV-CH-010`, `INV-DOM-003` | Preview không là lời hứa. Reserve failure là `REJECT_ALL`, không partial fulfillment hoặc auto-reroute. |
| `INV-CH-011`, `INV-CH-012` | Primary store unavailable trả `503 INVENTORY_UNAVAILABLE`; không phát plan từ cache/stale state. |
| `INV-CH-013`, `INV-CTX-001` | Warehouse `inactive`/`outage` bị loại ở allocation ban đầu; Inventory sở hữu trạng thái warehouse. |
| `INV-DOM-008`, `INV-WF-001` | Các line khác nhau có thể ở các warehouse khác nhau và dispatch độc lập; không cần tối ưu gom toàn order về cùng kho. |
| `INV-CTX-009` | Admin là role quản lý warehouse master; mọi thay đổi policy phải audited. |

## 2. Quyết định Phase 4 đã chốt

### `INV-ALLOC-001` — Eligibility tối giản

Một warehouse chỉ eligible cho một order line khi đồng thời thỏa cả ba điều kiện từ
authoritative Inventory state:

```text
is_active = true
operational_state ≠ OUTAGE
available_to_sell ≥ requested_quantity
```

Release đầu không xét warehouse capability, capacity, service zone/địa chỉ khách, cost,
SLA, age of stock, load balancing, stock transfer hoặc synchronous Product lookup.

### `INV-ALLOC-002` — Static priority và deterministic tie-break

Mỗi warehouse có `fulfillment_priority` trong warehouse master do Admin Inventory quản lý;
số nhỏ hơn được ưu tiên hơn. Với từng line, sau khi lọc eligibility, engine sắp xếp:

```text
fulfillment_priority ASC
warehouse_id ASC
```

Warehouse đầu tiên là allocation được đề xuất. Không tối ưu gom tất cả line về cùng một
warehouse ở release đầu; mỗi line được rank độc lập theo policy này.

Ví dụ SKU-X quantity 2:

| Warehouse | `is_active` / state | `available_to_sell` | `fulfillment_priority` | Kết quả |
|---|---|---:|---:|---|
| A | `true` / normal | 10 | 2 | Eligible nhưng rank sau B. |
| B | `true` / normal | 5 | 1 | **Được chọn**. |
| C | `true` / outage | 100 | 0 | Bị loại trước ranking. |

### `INV-ALLOC-003` — Preview advisory, reserve revalidate exact plan

AllocationPreview trả một plan advisory gồm warehouse được chọn cho từng line và lý do
ranking. ReserveOrder tham chiếu exact allocation plan đó; Inventory chỉ revalidate các
warehouse/quantity đã chọn bằng authoritative state trong atomic reserve operation.

Nếu bất kỳ allocation đã preview không còn eligible khi reserve, Inventory trả
`REJECT_ALL`. Nó không âm thầm chọn warehouse rank tiếp theo. Muốn thử allocation mới,
Checkout/Order phải bắt đầu một interaction/preview mới có chủ đích.

### `INV-ALLOC-004` — Warehouse status chỉ chặn allocation mới

`inactive` hoặc `outage` làm warehouse không eligible cho AllocationPreview/ReserveOrder
mới. Nó không tự đảo, release hoặc reroute một allocation đã tạo trước đó.

| Thời điểm warehouse đổi status | Behavior đã chốt |
|---|---|
| Sau preview, trước reserve | Exact plan stale; ReserveOrder revalidate thất bại thì `REJECT_ALL`, không chọn kho khác. |
| Sau reserve, trước payment commit | Reservation được grandfather; không auto-release/reroute. `CommitPayment` vẫn xét root state và `expires_at`, không xét lại warehouse status. |
| Sau payment commit, trước dispatch | Allocation giữ `COMMITTED`; Order quyết định chờ khôi phục hoặc hủy/exception. Inventory không tự phán quyết. |
| Warehouse Admin xác nhận physical dispatch | `RecordPhysicalDispatch` vẫn hợp lệ khi line `COMMITTED` tại đúng warehouse, bất kể status hiện tại; đây là fact vật lý không được phủ nhận bởi cờ vận hành có thể stale. |

Status kho vì vậy là **new-allocation eligibility control**, không phải cơ chế xóa hoặc
viết lại commitment/fact vật lý đã tồn tại. Nếu kho outage thực tế không thể xuất hàng,
Admin không ghi dispatch; Order dùng lifecycle/exception đã chốt để quyết định tiếp theo.

### `INV-ALLOC-005` — Admin-managed fulfillment priority

Warehouse master có field bắt buộc `fulfillment_priority` là integer dương; số nhỏ hơn
nghĩa là ưu tiên allocation cao hơn. Priority không cần unique vì `warehouse_id ASC` là
deterministic tie-break.

Chỉ `ADMIN` được tạo/cập nhật priority. Mỗi update phải có `warehouse_id`, old/new value,
`reason_code`, actor, `command_id`, timestamp và audit record; retry cùng command ID trả
result cũ. Priority bắt buộc có khi tạo warehouse và chỉ ảnh hưởng AllocationPreview tạo
sau khi cập nhật. Nó không thay đổi preview plan, reservation hay committed allocation đã có.

Ví dụ multi-line:

| Line | Warehouse eligible | Priority | Allocation |
|---|---|---:|---|
| SKU-A ×2 | A, B | A=1; B=2 | A |
| SKU-B ×1 | B, C | B=2; C=2 | B, vì `warehouse_id` thấp hơn C |

Order trên có thể dùng Kho A cho SKU-A và Kho B cho SKU-B; policy không cố gom toàn bộ
order về một warehouse.

## 3. Catalogue outcome và explainability

| Outcome semantic | Điều kiện | Hành vi caller |
|---|---|---|
| Preview feasible | Mọi line có một warehouse eligible theo ranking. | Hiển thị plan advisory; ReserveOrder phải revalidate. |
| Preview no feasible allocation | Có ít nhất một line không có warehouse eligible. | Hiển thị out-of-stock/allocation-unavailable; không tạo reservation. |
| `503 INVENTORY_UNAVAILABLE` | Primary authoritative store unavailable. | Retry sau recovery; không xem là out of stock. |
| `REJECT_ALL` at reserve | Plan preview stale hoặc quantity/state thay đổi trước reserve. | Kết thúc stock attempt; không auto-reroute/partial fulfillment. |

Plan/audit explanation tối thiểu phải nêu selected `warehouse_id`, `fulfillment_priority`
và lý do loại candidate khác (`INACTIVE`, `OUTAGE`, `INSUFFICIENT_AVAILABLE`, `LOWER_RANK`).

## 4. Definition of done cho Phase 4

- [x] Hard eligibility filter release đầu đã chốt; các criterion phức tạp được loại rõ.
- [x] Ranking theo static `fulfillment_priority` và deterministic tie-break đã chốt.
- [x] Per-line allocation, không order-consolidation, preview advisory và no-auto-reroute đã chốt.
- [x] Outcome catalogue và explainability baseline đã chốt.
- [x] Policy khi warehouse trở thành `inactive`/`outage` sau preview/reserve/commit đã chốt.
- [x] Warehouse master command/audit cho `fulfillment_priority` và ví dụ multi-line được hoàn tất.

**Phase 4 complete — 2026-09-07.** Policy này là input bắt buộc cho Phase 6–7; không được
thêm routing criterion, auto-reroute hay thay đổi meaning của warehouse status nếu chưa có
decision thay thế được owner review.
