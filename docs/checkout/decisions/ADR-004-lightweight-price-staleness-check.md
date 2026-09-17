---
doc_id: checkout-adr-004
service: checkout
status: accepted
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/workflows.md
  - docs/checkout/data.md
---

# ADR-004 — Lightweight Price Staleness Check bằng `pricing_hash`

## Trạng thái

`Accepted` theo thiết kế cũ. Chưa có implementation evidence.

## Bối cảnh

Snapshot báo giá nằm trong Redis 20 phút. Khoảng thời gian đó đủ để giá markdown
hết hạn, admin thay đổi giá, coupon hết hạn — snapshot trở thành “hàng thiu”.
Nếu platform sửa giá gốc mà Submit vẫn commit theo snapshot, nền tảng chịu thua
lỗ tài chính. Tuy nhiên quét lại toàn bộ hóa đơn (Inventory, address, stock) ở
Submit rất tốn kém và đi ngược mục tiêu Submit siêu tốc.

## Quyết định

- Preview tạo `pricing_hash = "sha256:" + lowercase_hex(SHA-256(UTF8(JCS(input))))`
  và lưu vào session. `JCS` là RFC 8785 canonical JSON; `input` chính xác là
  `{items:[{sku_id,unit_price_minor}],promotion_campaign_revision,
  total_discount_minor}`, items sort theo byte UTF-8 của unique `sku_id`.
- Submit, **sau khi giữ lock và trước khi chạy Lua**, thực hiện kiểm tra nhẹ:
  1. Batch fetch giá hiện tại của các SKU trong session qua Product gRPC
     (`original_price`, `promotional_price`, `best_price`); Product tự xử lý cache
     miss nội bộ.
  2. Lấy `promotion_campaign_revision` hiện hành (hard check).
     `promotion_budget_version` chỉ dùng telemetry/diagnostic, không tham gia hash
     gate. Revision là optimistic concurrency/cache fence, không phải mutable
     definition version; published definition thay đổi bằng identity mới nên
     selection cũ phải evaluate lại, không reinterpret theo cùng ID.
  3. Tính lại hash và so với `pricing_hash`.
  4. Khớp → tiếp tục Lua. Không khớp → nhả lock, `409 PRICE_CHANGED` với
     `action=RECALL_PREVIEW`; **không** rollback vì session vẫn `PREVIEW_READY`.
- Submit **bắt buộc** check lại phiên bản Promotion, không chỉ giá sản phẩm, để
  tránh lệch tiền cuối cùng giữa Preview và Commit.
- Các re-validate nặng (address, stock, reserve budget) được ủy quyền cho Temporal
  Saga của Order; Checkout dùng `pricing_summary` từ session, **không tính lại chi
  tiết**.
- Chi phí mục tiêu: lookup cache hit ~2–4ms, hash ~0.2ms, tổng < 8ms.

## Phương án đã cân nhắc

- **Không kiểm tra, tin snapshot 20 phút:** nhanh nhất nhưng rủi ro tài chính khi
  giá đổi; không chọn.
- **Re-run toàn bộ Preview ở Submit:** chính xác nhưng tốn kém (Inventory, User,
  Promotion) và làm chậm Submit; không chọn.
- **Hash nhẹ trên giá + revision (chọn).**

## Hệ quả

- Tích cực: phát hiện giá/khuyến mãi đổi với < 8ms; session không bị hỏng khi
  lệch; user chỉ cần làm mới trang.
- Tiêu cực: chỉ phát hiện thay đổi nằm trong input hash; exhausted budget và stock
  vẫn do Reserve trong Saga bắt (race ngắn giữa Preview và Submit là chấp nhận
  được). Đổi công thức hash giữa version gây `PRICE_CHANGED` hàng loạt.
- Constraint: canonical field là `total_voucher_discount`
  (`total_discount_amount`/`total_platform_discount` deprecated).

## Ảnh hưởng migration

Chưa có code. Order cross-check `pricing_hash` theo canonical input/encoding ở
Order internal OpenAPI v1; contract test phải dùng fixture byte/hash chung.

## Xác minh

Khi có code: unit test hash deterministic với sorted input; test đổi giá Product →
`PRICE_CHANGED`; đổi `promotion_campaign_revision` → `PRICE_CHANGED`; đổi
`promotion_budget_version` → không đổi hash; latency check < 8ms.
