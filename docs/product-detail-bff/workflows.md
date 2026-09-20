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
3. Product trả `404` thì BFF trả `404` ngay, không gọi Inventory hoặc Review. Product
   lỗi hạ tầng thì BFF trả `503`.
4. Với Product catalog-active, BFF lấy các `sku_id` active rồi đồng thời gọi một
   Inventory batch availability read và Review internal PDP read.
5. Với mỗi SKU active, BFF map kết quả Inventory `IN_STOCK`/`OUT_OF_STOCK`; item thiếu,
   `UNKNOWN`, timeout hay `503` được map thành `UNKNOWN`.
6. Review trả thành công thì map `AVAILABLE`; aggregate 0/preview rỗng là hợp lệ.
   Review timeout/lỗi thì map `UNAVAILABLE` và không tạo rating/count giả.
7. Trả response. Không gọi Search, không gửi Inventory command và không mutate state.

Inventory batch read là advisory UI query: BFF không retry lặp lại trong buyer hot path
khi dependency đã lỗi. Circuit breaker mở thì bước 4 bỏ qua Inventory và mọi SKU là
`UNKNOWN` trong request đó. Order Reserve vẫn là quyết định cuối cùng chống oversell.

## WF-PDP-002 — Degrade và recovery boundary

1. Nếu Inventory trả một item `UNKNOWN`, BFF giữ nguyên item đó; không dùng cache cũ để
   nâng thành `IN_STOCK` hoặc `OUT_OF_STOCK`.
2. Nếu Inventory return `503` hoặc timeout, BFF ghi metric theo dependency outcome và
   trả catalog cùng `UNKNOWN`; không có Kafka offset, replay hoặc rebuild trong BFF.
3. Inventory on-call xử lý read-model lag/recompute/cache trong Inventory boundary.
   Khi Inventory API phục hồi, request PDP tiếp theo tự nhận state mới.
4. Nếu Review lỗi, BFF trả `UNAVAILABLE` theo cùng nguyên tắc isolated degradation.

Không bootstrap từ `INVENTORY_STOCK_CHANGED`, `stock-snapshot.v1`, Search export hay
bất kỳ topic Inventory nào. Những contract đó không phải input của BFF.
