# ADR — Refund Voucher Policy

**Status:** ACCEPTED
**Date:** 2026-06-17
**Deciders:** Architecture Team
**Supersedes:** `docs/order/note.txt` §5 (Voucher refund rule mới — dùng `SHOP` terminology cũ)

> **⚠️ SUPERSEDED by Phase 14 (Remove Shipping Service & Free-Ship Voucher):**
> Voucher type `SHIPPING` (FREE_SHIPPING) đã bị loại bỏ hoàn toàn khỏi hệ thống. Toàn bộ rule về SHIPPING allocation dưới đây không còn áp dụng. Chỉ còn PLATFORM allocation được phân bổ. Pricing formula đã đơn giản hóa còn `final_amount = total_amount - total_discount` (không còn phí ship, không còn freeship discount).
>
> Nội dung dưới đây được GIỮ NGUYÊN cho mục đích audit lịch sử Phase 8.

---

## 1. Context

Hệ thống E-Commerce có 2 role: `ADMIN` (do Platform) và `CUSTOMER` (người mua). Không có `SHOP`/`SELLER`.

Có 3 nguồn tài liệu trước đó mô tả khác nhau về Voucher Refund rule:

| Nguồn | FULL rule | PARTIAL rule |
|---|---|---|
| `docs/order/note.txt` §5 | Hoàn toàn bộ `SHOP, PLATFORM, SHIPPING` | Chỉ hoàn `SHOP` khi scope đủ. `PLATFORM, SHIPPING = 0` |
| `docs/order/order_workflow.md` §8.7 | (không rõ — dùng "platform voucher") | Chỉ hoàn `platform` khi scope đủ. `PLATFORM, SHIPPING = 0` |
| `docs/promotion/promotion_events.md` §3.3 | "Allocation `PLATFORM/SHIPPING` partial = 0" | `RESTORE_ONLY_IF_FULL_platform_COVERED` cho `platform` |

Ba nguồn mâu thuẫn ở các điểm:
1. `note.txt` dùng `SHOP`/`PLATFORM`/`SHIPPING` — sai vì không có SHOP.
2. `order_workflow.md` dùng `platform` (lowercase) — không nhất quán enum.
3. `promotion_events.md` dùng `platform`/`PLATFORM` lẫn lộn — không có SHOP nhưng bảng thuật ngữ lại có.

## 2. Decision

Chốt rule refund voucher theo mô hình **no-shop, admin-customer**:

### 2.1. Voucher Types (canonical)

Chỉ tồn tại 1 loại voucher (sau Phase 14):

| Voucher Type | Vai trò | Mapping `promotions.discount_type` |
|---|---|---|
| `PLATFORM` | Giảm giá sản phẩm toàn đơn (PERCENTAGE hoặc FIXED_AMOUNT) | `PERCENTAGE`, `FIXED_AMOUNT` |

> Vì hệ thống chỉ có 2 role ADMIN/CUSTOMER (không có SHOP), `deleted_owner_type` enum chuẩn = **`PLATFORM`** (uppercase). Mọi reference đến `admin` (lowercase) hoặc `shop` trong code/config cũ được coi là typo. **`SHIPPING` voucher và `FREE_SHIPPING` discount_type đã bị xóa tại Phase 14.**

### 2.2. Refund Rule cho `refund_type = FULL`

Khi khách trả toàn bộ đơn hàng:

- **Hoàn toàn bộ PLATFORM allocation** đã redeem.
- Không có giới hạn scope.

### 2.3. Refund Rule cho `refund_type = PARTIAL`

Khi khách trả một phần đơn hàng:

- **Chỉ hoàn PLATFORM allocation** khi refund scope bao phủ toàn bộ item của platform đó trong order (rule `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`).

### 2.4. Wallet Validity (voucher restore policy)

Khi Promotion restore voucher sau refund:

| Tình huống | Hành động |
|---|---|
| Voucher còn hạn (`expired_at > NOW()`) và cho phép tái sử dụng | `USED → SAVED` |
| Voucher đã hết hạn (`expired_at <= NOW()`) | `EXPIRED` (hoặc giữ `USED` nếu không có row ví để cập nhật) |
| Voucher không cho phép tái sử dụng | Giữ `USED` |
| Voucher unavailable (row ví không tồn tại) | Ghi `restore_outcome = VOUCHER_UNAVAILABLE`, không rollback tiền refund |

### 2.5. Non-Blocking Invariant

> **Quan trọng:** Dù restore voucher thành công/thất bại ở bất kỳ kết quả nào (`RESTORED`/`RETAINED_USED`/`EXPIRED_AT_RESTORE_TIME`/`SKIPPED_SCOPE_RULE`/`VOUCHER_UNAVAILABLE`), giao dịch hoàn tiền ở Payment Service KHÔNG bị rollback.

Lý do: tiền đã chuyển thành công cho user, voucher restore chỉ là quà tặng bonus. Nếu rollback tiền vì lý do voucher restore fail, sẽ phát sinh chargeback từ cổng thanh toán → risk cao hơn.

## 3. Contract

### 3.1. Order `OrderRefundWorkflow` phải tính `promotion_refund_allocations[]` deterministic

Với mỗi `(order_id, promotion_id)` đã áp dụng:

- **`refund_type = FULL`**: allocation = full `discount_amount` của promotion đó.
- **`refund_type = PARTIAL`**:
  - Nếu refund scope bao phủ **toàn bộ item** của platform đó trong order: allocation = full `discount_amount` (chỉ cho `PLATFORM`).
  - Ngược lại: allocation = 0.

Promotion Service KHÔNG tự suy luận allocation; Order phải publish `promotion_refund_allocations[]` chính xác trong `ORDER_REFUNDED` event.

### 3.2. Promotion Service xử lý allocation

Với mỗi entry trong `promotion_refund_allocations[]` (chỉ những entry có allocation > 0):

1. Gọi Lua Script 5 (`refund_budget`) theo từng `promotion_id` với `idempotency_key = REFUND_FULL|PARTIAL:{order_id}:{promotion_id}:{refund_request_id}`.
2. Ghi `promotion_event_idempotency(event_id = idempotency_key, action_type = REFUND_COUPON, action_scope_key = refund_request_id)`.
3. Update `promotion_budgets.used_* -= applied_refund_*`.
4. Cập nhật ví user theo §2.4.

### 3.3. Source of Truth

| Field | Source of Truth | Notes |
|---|---|---|
| `refund_type` (FULL/PARTIAL) | Order `OrderRefundWorkflow` (từ request) | Promotion KHÔNG tự quyết |
| `promotion_refund_allocations[]` | Order `OrderRefundWorkflow` (compute deterministic) | Promotion chỉ execute |
| `wallet restore decision` | Promotion Service (xét `expired_at`) | Order không can thiệp |
| `payment_status` transition | Order `OrderRefundWorkflow` | Sau khi Payment confirm |

## 4. Consequences

### Positive
- Một rule duy nhất, không còn SHOP, không còn platform (lowercase) inconsistency.
- Tất cả allocation là deterministic từ Order side; Promotion chỉ là executor.
- Non-blocking invariant tránh chargeback risk.

### Negative
- Phải tính `promotion_refund_allocations[]` kỹ ở Order workflow — bug ở Order sẽ leak budget ở Promotion hoặc trả thiếu cho user.

## 5. References

- [Service Contract Matrix §0.3](../_contracts/CONTRACT_MATRIX.md#03-voucher-convention)
- [Service Contract Matrix §2.5 Event ORDER_REFUNDED](../_contracts/CONTRACT_MATRIX.md#25-events-do-promotion-service-produce)
- `docs/promotion/promotion_workflow.md` §5 (Refund Flow) — đã sync theo Phase 14 (không còn SHIPPING)
- `docs/promotion/promotion_db_design.md` §1.6 (coupon_usages funding invariant) — đã align
- `docs/promotion/promotion_events.md` §3.3 ORDER_REFUNDED payload — đã sync Phase 14
- `docs/order/order_workflow.md` §8.7 (Voucher refund rule) — đã sync Phase 14
- `docs/order/order_events.md` §3.6 ORDER_REFUNDED payload — đã align

## 6. Sync checklist (sau khi ADR được chấp thuận)

- [x] `docs/_contracts/CONTRACT_MATRIX.md` §0.3 (Voucher Convention) — Phase 14: chỉ còn PLATFORM
- [x] `docs/order/note.txt` §5 — Phase 14: đã sửa dùng PLATFORM only
- [x] `docs/order/order_workflow.md` §8.7 — Phase 14: đã đồng bộ rule
- [x] `docs/promotion/promotion_events.md` §3.3 — Phase 14: đã chuẩn hoá enum
- [x] `docs/promotion/promotion_workflow.md` §5 — Phase 14: đã đồng bộ + link về ADR
- [x] `docs/promotion/voucher_discovery_api.md` — Phase 14: đã sửa typo `admin` → `PLATFORM`; đã bỏ Freeship Rule section
- [x] `docs/promotion/promotion_db_design.md` §2.1 — Phase 14: SLOT 3 Freeship đã xóa