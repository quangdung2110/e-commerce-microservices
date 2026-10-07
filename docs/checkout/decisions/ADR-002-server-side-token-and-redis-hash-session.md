---
doc_id: checkout-adr-002
service: checkout
status: superseded
superseded_by: checkout-adr-011
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/data.md
  - docs/checkout/domain.md
---

# ADR-002 — Checkout token UUID v4 server-side và Redis Hash session, không dùng JWT

## Trạng thái

`Superseded` bởi [ADR-011](./ADR-011-token-keyed-session-and-recovery.md) ngày 2026-09-20; nội dung dưới đây là lịch sử, không phải contract hiện hành. Trước đó: `Accepted` theo thiết kế cũ, chưa có implementation evidence.

## Bối cảnh

Sau Preview, Checkout cần “niêm phong” bảng báo giá để khi user bấm Submit, số
tiền commit đúng bằng số tiền đã xem, và để Order Service không phải tính lại toàn
bộ phép cộng trừ. Token này phải revoke được ngay khi Submit thành công (one-time
use) và không được để client suy diễn hay tái sử dụng.

## Quyết định

- **Không dùng JWT** làm checkout token: JWT stateless mã hóa payload bên trong,
  khó revoke ngay lập tức và khó enforce one-time-use.
- Backend sinh **UUID v4** thuần túy làm Reference Token trỏ về Redis session;
  lưu ở field `token` của Hash `checkout:session:{user_id}` (token là field,
  không nằm ở key path). Validate bằng cách so khớp token trong request với token
  đã lưu.
- **Một session/user:** key theo `user_id`; Preview với `checkout_token = null`
  khi đã có session → `409`.
- Mọi thay đổi (đổi mã, đổi địa chỉ, cart snapshot mới) → Preview lại → token
  **mới** ghi đè session.
- Session là **Redis Hash**, TTL 20 phút (dài hơn Payment timeout 15 phút), vì:
  selective update (`HSET` một field khi đổi address/voucher), atomic read
  (`HGETALL` một round-trip), selective read (`HGET` một field).
- Session lưu snapshot server-side: `pricing_summary`, `pricing_hash`,
  `items_hash`, `cart_snapshot`, `promotion_campaign_revision`,
  `promotion_budget_version`, voucher selection, `delivery_address_snapshot`.
  Client chỉ gửi identifier; không tin bất kỳ số tiền nào client gửi
  (Zero-Trust Client).

## Phương án đã cân nhắc

- **JWT chứa pricing snapshot:** không cần Redis đọc ở Submit nhưng không revoke
  được, không one-time-use, payload lộ ra client; không chọn.
- **Session String JSON:** đơn giản nhưng mỗi thay đổi phải serialize lại toàn bộ;
  không chọn.
- **UUID v4 + Redis Hash (chọn).**

## Hệ quả

- Tích cực: revoke tức thì bằng `HSET status`; replay bị chặn; dữ liệu tính toán
  không rời khỏi backend; update per-field atomic.
- Tiêu cực: mọi Submit phải chạm Redis; mất Redis = mất session; hai tab cùng user
  dùng chung một token (tab submit trước thắng).
- Constraint: token không mang ý nghĩa gì ngoài tham chiếu; không được đưa token
  vào key path.

## Ảnh hưởng migration

Chưa có code. Frontend phải giữ và gửi lại `checkout_token` ở Submit; sau mỗi
Preview phải dùng token mới nhất.

## Xác minh

Khi có code: test token mismatch → `409`; token mới sau mỗi Preview; `409` khi
Preview `null` với session hiện hành; TTL 20 phút; `HSET` selective update.
