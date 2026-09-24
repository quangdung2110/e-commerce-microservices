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

1. Gateway định tuyến request public (anonymous, rate profile `public-read`) tới BFF.
   BFF đặt deadline PDP 1 000 ms tính từ lúc nhận request. Slug không khớp pattern
   thì trả `404` ngay.
2. BFF gọi Product internal buyer-read theo slug với timeout 400 ms.
3. Product trả `404` thì BFF trả `404` ngay, không gọi Inventory hoặc Review. Product
   timeout/lỗi/circuit open/bulkhead đầy thì BFF trả `503`.
4. Với Product catalog-active, BFF lấy các `sku_id` active. Nếu deadline còn lại từ
   100 ms trở lên, BFF đồng thời gọi một Inventory batch availability read và Review
   internal PDP read, mỗi call với timeout `min(300 ms, deadline còn lại − 50 ms)`;
   dependency có circuit mở hoặc bulkhead đầy thì không được gọi. Nếu còn dưới 100 ms,
   BFF bỏ cả hai call.
5. Với mỗi SKU active, BFF map kết quả Inventory `IN_STOCK`/`OUT_OF_STOCK`; item thiếu
   hoặc `UNKNOWN` được map thành `UNKNOWN`. Call Inventory không cho kết quả hợp lệ hoặc
   không được gọi thì mọi SKU là `UNKNOWN`.
6. Review trả thành công thì map `AVAILABLE`; aggregate 0/preview rỗng là hợp lệ.
   Review không cho kết quả hợp lệ hoặc không được gọi thì map `UNAVAILABLE` và không
   tạo rating/count giả.
7. Trả response, kèm `Cache-Control: no-store` nếu có SKU `UNKNOWN` hoặc Review
   `UNAVAILABLE`. Không gọi Search, không gửi Inventory command và không mutate state.

Inventory batch read là advisory UI query: BFF không retry ở ứng dụng; mesh cũng không
retry route này. Order Reserve vẫn là quyết định cuối cùng chống oversell. Nếu client
hoặc gateway hủy request ở bất kỳ bước nào, BFF hủy các call đang chạy. Bảng phân loại
kết quả đầy đủ nằm ở [operations.md](./operations.md#health-và-degradation).

## WF-PDP-002 — Degrade và recovery boundary

1. Nếu Inventory trả một item `UNKNOWN`, BFF giữ nguyên item đó; không dùng cache cũ để
   nâng thành `IN_STOCK` hoặc `OUT_OF_STOCK`.
2. Nếu Inventory trả `5xx`, timeout hoặc response sai contract, BFF ghi metric theo
   dependency outcome, tính lỗi vào circuit breaker và trả catalog cùng `UNKNOWN`;
   không có Kafka offset, replay hoặc rebuild trong BFF. `400`/`401`/`403` cũng trả
   `UNKNOWN` nhưng không tính vào circuit breaker và phát alert cấu hình.
3. Khi tỷ lệ lỗi hoặc call chậm vượt ngưỡng, circuit Inventory mở 10 giây: BFF không gọi
   Inventory và mọi SKU là `UNKNOWN`. Sau đó half-open cho 5 call thử; thành công thì
   đóng circuit.
4. Inventory on-call xử lý read-model lag/recompute/cache trong Inventory boundary.
   Khi Inventory API phục hồi và circuit đóng, request PDP tiếp theo tự nhận state mới;
   vì response degrade mang `no-store`, CDN không giữ `UNKNOWN` sau khi phục hồi.
5. Nếu Review lỗi, BFF trả `UNAVAILABLE` theo cùng nguyên tắc isolated degradation và
   circuit breaker riêng của Review.

Không bootstrap từ `INVENTORY_STOCK_CHANGED`, `stock-snapshot.v1`, Search export hay
bất kỳ topic Inventory nào. Những contract đó không phải input của BFF.
