---
doc_id: product-detail-bff-workflows
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Workflows

## WF-PDP-001 — Read Product Detail

1. Gateway xác thực/định tuyến request public tới BFF.
2. BFF gọi Product internal buyer-read theo slug.
3. BFF load projection theo tất cả `sku_id` trong catalog snapshot.
4. Với mỗi SKU active, map snapshot mới đủ freshness thành `IN_STOCK`/`OUT_OF_STOCK`;
   thiếu hoặc stale thành `UNKNOWN`.
5. Trả response. Không gọi Search, không gọi Inventory và không mutate state.

## WF-PDP-002 — Apply stock snapshot

1. Consume event type allowlist trên `t.inventory.stock.events.v1`.
2. Validate `product_id`, `sku_id`, `available_quantity`, `stock_revision`, `observed_at`.
3. Upsert `pdp_sku_availability` nếu revision lớn hơn.
4. Commit Kafka offset sau database commit.

Event trùng/cũ là no-op; event tới trước Product là hợp lệ. Lỗi database tạm thời không
commit offset. Payload lỗi là contract violation, ghi metric rồi xử lý theo retry/DLQ policy.

## WF-PDP-003 — Bootstrap hoặc rebuild availability

1. Gọi Inventory export, giữ `replay_from_offsets` của export session.
2. Import toàn bộ snapshot theo `sku_id` với guard revision.
3. Seek inclusive topic stock từ offsets đó và catch-up đến lag bằng 0.
4. Chỉ sau validation projection mới coi freshness là healthy.

Không bootstrap từ legacy `INVENTORY_STOCK_CHANGED`; event này không có revision hay
recovery contract.
