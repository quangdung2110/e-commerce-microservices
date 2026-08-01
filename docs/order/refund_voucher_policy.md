# ADR — Refund Voucher Policy

**Status:** ACCEPTED
**Date:** 2026-06-17
**Deciders:** Architecture Team


> **⚠️ SUPERSEDED by Phase 14 (Remove Shipping Service & Free-Ship Voucher):**
> Voucher type `SHIPPING` (FREE_SHIPPING) đã bị loại bỏ hoàn toàn khỏi hệ thống. Chỉ còn PLATFORM allocation được phân bổ. Pricing formula đã đơn giản hóa còn `final_amount = total_amount - total_discount`.

> **Phase 20 (Event Payload Simplification):** `promotion_refund_allocations[]` trong `ORDER_REFUNDED` event payload đã được thu gọn từ 7 field xuống còn **3 field**: `promotion_id`, `refunded_discount_amount`, `admin_funding_refund`. Chi tiết xem §2.1 dưới đây và [CHANGELOG Phase 20](../_contracts/CHANGELOG.md#phase-20--event-payload-simplification-order--payment--inventory--promotion--cart). Quyết định chính sách (Rule FULL/PARTIAL, Wallet Validity, Non-Blocking Invariant) **không đổi**.

> **Phase 23 (Remove Voucher Restore on Refund):** Promotion Service **không còn restore voucher** `USED → SAVED` khi xử lý `ORDER_REFUNDED`. Chỉ hoàn ngân sách (`promotion_budgets`) và cập nhật sổ cái (`coupon_usages`). Voucher giữ nguyên trạng thái `USED`. Xem chi tiết tại [CHANGELOG Phase 23](../_contracts/CHANGELOG.md#phase-23--remove-voucher-restore-on-refund).


---

## 1. Decision

Hệ thống E-Commerce có 2 role: `ADMIN` (do Platform) và `CUSTOMER` (người mua). Chỉ 1 loại voucher: `PLATFORM` (PERCENTAGE / FIXED_AMOUNT).

### 1.1. Refund Rule

- **`refund_type = FULL`:** Hoàn toàn bộ PLATFORM allocation đã redeem.
- **`refund_type = PARTIAL`:** Chỉ hoàn PLATFORM allocation khi refund scope bao phủ toàn bộ item của platform đó trong order (rule `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`).

### 1.2. Wallet Validity (voucher restore policy)

> **Phase 23:** Promotion **không còn** restore `USED → SAVED` khi refund. Voucher luôn giữ `USED`.

| Tình huống | Hành động |
|---|---|
| Voucher còn hạn (`expired_at > NOW()`) | Giữ `USED` |
| Voucher đã hết hạn (`expired_at <= NOW()`) | Giữ `USED` |
| Voucher unavailable (row ví không tồn tại) | Ghi `restore_outcome = VOUCHER_UNAVAILABLE`, không rollback tiền refund |

### 1.3. Non-Blocking Invariant

> **Quan trọng:** Dù kết quả xử lý budget/voucher ra sao (`RETAINED_USED`/`VOUCHER_UNAVAILABLE`), giao dịch hoàn tiền ở Payment Service KHÔNG bị rollback.

Lý do: tiền đã chuyển thành công cho user, việc hoàn budget promotion là tác vụ phụ trợ. Nếu rollback tiền vì lý do budget refund fail, sẽ phát sinh chargeback từ Stripe (cổng thanh toán) → risk cao hơn.

**Trade-off:** Bug ở Order workflow (tính sai `promotion_refund_allocations[]`) sẽ leak budget ở Promotion hoặc trả thiếu cho user. Mitigation: deterministic compute từ Order side, không để Promotion tự suy luận (xem §2).

---

## 2. Contract

### 2.1. Order `OrderRefundWorkflow` phải tính `promotion_refund_allocations[]` deterministic

Với mỗi `(order_id, promotion_id)` đã áp dụng:

- **`refund_type = FULL`:** allocation = full `discount_amount` của promotion đó.
- **`refund_type = PARTIAL`:**
  - Nếu refund scope bao phủ **toàn bộ item** của platform đó trong order: allocation = full `discount_amount` (chỉ cho `PLATFORM`).
  - Ngược lại: allocation = 0.

Promotion Service KHÔNG tự suy luận allocation; Order phải publish `promotion_refund_allocations[]` chính xác trong `ORDER_REFUNDED` event (xem canonical [Order Events §3.6](order_events.md#36-order_refunded)).

### 2.2. Promotion Service xử lý allocation

Với mỗi entry trong `promotion_refund_allocations[]` (chỉ những entry có `refunded_discount_amount > 0`):

1. Gọi Lua Script 5 (`refund_budget`) theo từng `promotion_id` với `idempotency_key = REFUND_FULL|PARTIAL:{order_id}:{promotion_id}:{refund_request_id}`.
2. Ghi `promotion_event_idempotency(event_id = idempotency_key, action_type = REFUND_COUPON, action_scope_key = refund_request_id)`.
3. Update `promotion_budgets.used_* -= refunded_discount_amount`.
4. Giữ nguyên trạng thái ví `USED`, không restore về `SAVED`.

> **Phase 23 note:** Step 4 không còn restore voucher. `voucher_restore_mode` trước đây là internal policy dựa trên wallet validity; nay đã được thay thế bằng policy "luôn giữ `USED`". Xem [CHANGELOG Phase 23](../_contracts/CHANGELOG.md#phase-23--remove-voucher-restore-on-refund).

### 2.3. Source of Truth

| Field | Source of Truth | Notes |
|---|---|---|
| `refund_type` (FULL/PARTIAL) | Order `OrderRefundWorkflow` (từ request) | Promotion KHÔNG tự quyết |
| `promotion_refund_allocations[]` | Order `OrderRefundWorkflow` (compute deterministic) | Promotion chỉ execute |
| `wallet restore decision` | Promotion Service (luôn giữ `USED`, không restore) | Order không can thiệp |
| `payment_status` transition | Order `OrderRefundWorkflow` | Sau khi Payment confirm |

---
