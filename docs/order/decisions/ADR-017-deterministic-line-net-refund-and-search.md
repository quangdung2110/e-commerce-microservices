---
doc_id: order-adr-017
service: order
status: accepted
date: 2026-10-05
decision_owners:
  - Order Service team
supersedes: null
evidence:
  - docs/order/data.md
  - docs/order/workflows.md
  - contracts/http/order/v1/openapi.yaml
---
# ADR-017 — Deterministic line-net refund và order-card search

## Trạng thái

`Accepted` — 2026-10-05. Refine amount rule của ADR-010 và query detail của ADR-004.

## Quyết định

Checkout phân bổ `total_discount_minor` xuống mọi line theo tỷ trọng gross và largest
remainder với tie-break bằng stable line ID. Mỗi line lưu immutable
`discount_share_minor` và `net_amount_minor`; selected-line refund bằng tổng net amount
snapshot, không dùng gross-cap formula phụ thuộc tập chọn.

Text search dùng application-normalized `snapshot_name_search` (pinned Unicode NFKD,
remove combining marks, lowercase) và GIN `pg_trgm`; không dùng mutable/runtime
`unaccent` expression. Card chọn name/image từ cùng primary line có order ổn định,
`item_count` đếm line và remaining bằng max(count-1,0). Buyer tab chuyển sang
`TO_RECEIVE` ngay khi có line `DISPATCHED` hoặc `DELIVERED`.

Redis mirror key scope gồm principal/action/hash(idempotency key); durable uniqueness
giữ raw scoped identity/digest. Client secret chỉ thuộc Payment và không được Order lưu.

## Xác minh

Property tests kiểm tra tổng discount, net không âm và mọi subset refund; SQL tests kiểm
tra accent normalization parity, deterministic primary line, counts, tab và keyset cursor.
