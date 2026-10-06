---
doc_id: checkout-adr-005
service: checkout
status: superseded
superseded_by: checkout-adr-012
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/workflows.md
  - docs/checkout/architecture.md
---

# ADR-005 — Soft allocation và không lock budget ở Preview; Reserve trong Saga là quyết định cuối

## Trạng thái

`Superseded` bởi [ADR-012](./ADR-012-contract-aligned-fail-closed-orchestration.md) ngày 2026-09-20; nội dung dưới đây là lịch sử, không phải contract hiện hành. Trước đó: `Accepted` theo thiết kế cũ, chưa có implementation evidence.

## Bối cảnh

Preview được gọi rất nhiều lần (thử mã, đổi kho, đổi địa chỉ) và có thể bị bot
spam. Nếu Preview khóa tồn kho, hàng bị giam ảo do spam request. Nếu Preview lock
budget khuyến mãi, kẻ xấu có thể tự động mở hàng loạt checkout session, áp voucher
Flash Sale rồi bỏ trống, làm cạn kiệt ngân sách.

## Quyết định

- **Preview dùng Soft Allocation:** Inventory sourcing mô phỏng, không khóa kho;
  Hard Allocation (lock inventory) chỉ xảy ra trong Temporal Saga sau Submit.
  Inventory là owner của routing cho mỗi warehouse; Checkout gọi per warehouse
  group song song và cache kết quả theo `items_hash + address_hash` 20 phút.
- **Preview không lock budget Promotion:** gọi
  `POST /internal/v1/promotion-discount-calculations` ở chế độ validate only. Lock
  budget chỉ xảy ra trong Saga (`ReservePromotionActivity`) ngay sau Submit.
- **Reserve là nguồn quyết định cuối:** dù Discovery đã có budget guard read-time
  (Option A) hạ mã cạn budget thành `BUDGET_EXHAUSTED`, exhausted budget vẫn có
  thể xảy ra trong race ngắn giữa Preview và Submit; Submit bắt buộc giữ Reserve
  làm quyết định cuối. Nếu budget guard degraded (fail-open), correctness vẫn giữ
  ở Submit.
- Khi reserve fail, Checkout không tự trừ số học mà gọi lại Promotion evaluate
  (xem ADR-006).

## Phương án đã cân nhắc

- **Hard allocation/lock budget ở Preview:** giá và stock chắc chắn hơn nhưng
  giam hàng ảo và cạn budget do spam; không chọn.
- **Reserve ở Checkout ngay trước khi gọi Order:** Checkout phải compensate khi
  Order fail; trùng vai với Saga; không chọn.
- **Soft allocation + Reserve trong Saga (chọn).**

## Hệ quả

- Tích cực: Preview rẻ và an toàn trước spam; compensation tập trung ở Saga.
- Tiêu cực: kết quả Preview có thể không còn đúng lúc Submit (stock/budget); cần
  nhánh recovery `PROMOTION_RECALCULATION_NEEDED` và `PRICE_CHANGED`.
- Constraint: Checkout không gọi reserve/deduct/release của Inventory hay
  reservations của Promotion.

## Ảnh hưởng migration

Chưa có code. Order Saga phải trả lỗi reserve có cấu trúc
(`failed_promotion_id`, `failed_reason`, `reserve_mode`) để Checkout dựng đề xuất.

## Xác minh

Khi có code: Preview không tạo hold ở Inventory và không tạo reservation ở
Promotion; Saga reserve fail → Checkout trả 409 recalculation; Discovery degraded
nhưng Submit vẫn bắt exhausted budget.
