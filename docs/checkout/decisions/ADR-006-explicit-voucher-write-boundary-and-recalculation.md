---
doc_id: checkout-adr-006
service: checkout
status: accepted
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/workflows.md
  - docs/checkout/interfaces.md
  - contracts/http/promotion/v1/openapi.yaml
---

# ADR-006 — Explicit voucher write boundary và conflict recovery qua Promotion re-evaluate

## Trạng thái

`Accepted` theo thiết kế cũ. Chưa có implementation evidence.

## Bối cảnh

Voucher Sàn có thể được user chọn từ tray (mã đã lưu hoặc mã public chưa lưu). Nếu
Submit tự động ghi voucher vào ví, luồng thanh toán có hidden write khó audit, DB
ví bị bloat khi user mở checkout nhiều lần, và Discovery API phải trộn mã chưa lưu
tại runtime. Ngoài ra khi `ReservePromotion` trong Saga fail all-or-nothing (ví dụ
hết budget), việc Checkout tự trừ discount cục bộ sẽ bỏ qua hệ quả dây chuyền
(mã khác không còn đủ điều kiện do tổng tiền đổi) và tạo tổng tiền sai.

## Quyết định

**Write boundary**

- `POST /v1/checkout/submit` là luồng commit (read/validate/reserve qua Saga),
  **không được auto-save voucher** vào ví user và không ghi `user_coupon_wallets`.
- Mọi ghi mới vào ví phải đến từ explicit action ngoài Submit: “Lưu & Chọn”
  (`PUT /api/v1/me/promotion-vouchers/{promotion_id}`), claim
  (`POST /api/v1/me/promotion-voucher-claims`) hoặc campaign worker của Promotion.
- Policy tray cố định: tab “Voucher của tôi” gọi
  `POST /api/v1/me/promotion-voucher-suggestions` (chỉ `SAVED`); tab “Khám phá”
  gọi `POST /api/v1/promotion-discovery` (ví + public `ACTIVE` chưa lưu); sau Save
  & Apply thành công, Frontend gọi lại Preview để nhận token mới.
- Contract Promotion chỉ còn một slot `PLATFORM_VOUCHER`; Checkout chỉ gửi lựa
  chọn voucher PLATFORM; validate xung đột/stacking do Promotion xử lý.

**Conflict recovery**

- Khi Order trả lỗi nghiệp vụ từ ReservePromotion, Checkout thực hiện 4 bước bắt
  buộc: (a) nhận `failed_promotion_id` + `failed_reason` + `reserve_mode`; (b)
  loại mã đó và gọi lại `POST /internal/v1/promotion-discount-calculations` với
  cart context hiện tại + tập mã còn lại; (c) dựng `dropped_promotions[]` (chỉ mã
  bị loại, theo thứ tự, kèm `reason`/`user_message`) và `proposal_checkout_data`
  (`new_total_amount`, `surviving_promotions[]`); (d) trả
  `409 PROMOTION_RECALCULATION_NEEDED` + `action=RECALL_PREVIEW`.
- **No Local Arithmetic Fallback:** tuyệt đối không tự trừ/cộng discount ở
  Checkout.
- Response 409 được cache theo token; token vẫn `SUBMITTED`; client bắt buộc
  Preview lại với `surviving_promotions[]` để lấy token mới trước khi Submit tiếp.
- Frontend hiển thị popup minh bạch, render đủ toàn bộ lý do trong
  `dropped_promotions[]` theo đúng thứ tự.

## Phương án đã cân nhắc

- **Auto-save voucher khi Submit:** tiện cho user nhưng hidden write, DB bloat,
  Discovery phức tạp; không chọn.
- **Checkout tự trừ discount của mã fail:** nhanh nhưng sai khi có hệ quả dây
  chuyền; không chọn.
- **Rollback token về `PREVIEW_READY` để user submit lại ngay:** vi phạm
  one-time-use và cần rollback trạng thái; không chọn.
- **Explicit write + re-evaluate + token mới (chọn).**

## Hệ quả

- Tích cực: Submit chỉ tập trung reserve/confirm, dễ audit; Discovery chỉ đọc ví
  đã lưu + evaluate rule; tổng tiền đề xuất luôn do Promotion tính.
- Tiêu cực: thêm một lời gọi Promotion trong nhánh lỗi; user phải qua thêm một
  bước Preview; nếu Promotion lỗi lúc re-evaluate, Checkout không có fallback.
- Constraint: `dropped_promotions[]` không chứa mã sống; `surviving_promotions[]`
  là input chuẩn cho Preview.

## Ảnh hưởng migration

Chưa có code. Frontend phải implement popup và luồng Preview → Submit lại; Order
Saga phải trả lỗi có cấu trúc.

## Xác minh

Khi có code: Submit không tạo bản ghi ví; reserve fail → gọi Promotion calculation
với tập mã còn lại; 409 có `dropped_promotions[]`/`proposal_checkout_data`; retry
cùng token trả cùng 409; Submit lại token cũ bị từ chối.
