---
doc_id: search-adr-029
service: search
status: accepted
date: 2026-10-19
supersedes: null
evidence:
  - java/services/search-service/src/main/java/com/ecommerce/search/service/ProductSearchService.java
  - java/services/search-service/src/main/java/com/ecommerce/search/worker/job/FullReindexJob.java
  - java/services/search-service/src/test/java/com/ecommerce/search/integration/ProductCardLifecycleIT.java
  - docs/performance/search-required-read-model-benchmark-2026-10-19.md
---

# ADR-029 — `product_cards` bắt buộc và chuyển thế hệ cùng SKU/Category

## Context

ADR-027 đã có đường đọc Product projection, nhưng mặc định tắt và tự quay về ADR-020 khi chưa
khớp thế hệ. Mỗi trang grouping gom toàn bộ Product khớp về JVM rồi sort/cắt trang. Benchmark
16/10 chứng minh chi phí này làm storefront không chịu được catalog lớn. Fallback còn được
kích hoạt đúng lúc bootstrap hoặc full reindex đang dùng tài nguyên để dựng lại projection.

## Decision

1. Request mới của `GET /api/v2/search/products` bắt buộc dùng `product_cards`; cờ projector
   mặc định **true**. Không có projection khớp thì trả `503 ELASTICSEARCH_UNAVAILABLE`,
   `retryable=true`, `Retry-After: 2`. Không chạy grouping để cứu request mới. Không đổi schema
   HTTP hay error catalog. Cursor v2 đã phát vẫn được phục vụ bằng grouping trong thời gian
   chuyển tiếp; cursor v3 tiếp tục PIT của nó, độc lập với readiness hiện tại.
2. Ở stage CUTOVER, FullReindexJob dựng `product_cards` từ **physical SKU index đích đã được
   verify và refresh**, trước khi chuyển bất kỳ alias nào. Dữ liệu SKU đích không có writer
   trong lúc dựng; tail replay sau cutover tiếp tục như trước. Vị trí change feed được ghi
   vào `_meta.changes_through` của index cards riêng trước khi publish, đủ sớm để bao phủ tail.
   Checkpoint run lưu `product_cards_index` trước thao tác swap. Cùng một `_aliases` request
   chuyển `products`, `products_write`, `categories`, `categories_write`, `product_cards`.
3. Live projector và cutover dùng chung lease `product-cards-projector`. Projector nhả lease
   sau mỗi pass; reindex chờ với kiểm tra deadline/cancellation của run. Trong lúc dựng cards
   đích, cards hiện hành vẫn phục vụ một snapshot cũ; live incremental propagation tạm dừng
   trong khoảng đó. Đây không phải cam kết freshness cứng. Lease được gia hạn giữa các trang
   scan/bulk; mất lease ngăn publish. Mọi write/progress pin physical index, không ghi qua alias.
   Renew/release của lease dùng seq_no/primary_term CAS, không cho owner cũ ghi/xóa lease mới.
4. Readiness kiểm cặp alias trong một cluster-state lookup, tránh mismatch giả do hai lần
   đọc cắt ngang một swap nguyên tử. Không cho traffic mới vào instance trước khi có read
   model hợp lệ. Liveness không phụ thuộc projection.
5. Không xóa cards cũ ngay sau publish. IndexCleanupJob áp dụng retirement retention 24h
   mặc định và ít nhất bằng cấu hình PIT keep-alive. Cleanup không xóa cards có search context
   còn mở (kể cả PIT được gia hạn lâu hơn retention), và bỏ qua cards khi projector lease đang sống.
   Index của run chưa xong cũng được bảo vệ.
   Bootstrap bỏ dở có thể để lại index riêng; cleanup cho nó bắt đầu retention khi phát hiện.
   Rollback cũng dựng cards từ SKU đích rollback rồi chuyển cả năm alias dưới cùng lease;
   không tạo cửa sổ quay lại grouping. Disk precondition tính cả bản sao cards, ước lượng
   bằng SKU store nếu lần đầu chưa có cards alias.

## Failure/recovery

- Build/scan/bulk lỗi: không chuyển alias, xóa generation riêng nếu chắc chắn chưa serving.
- Mất lease hoặc quá deadline: không publish; owner tiếp quản chạy lại CUTOVER. Generation
  riêng cũ có thể được dựng lại; không coi một build dở là projection hợp lệ.
- Swap đã commit nhưng response bị mất: discard kiểm alias trước khi xóa; resume thấy SKU
  alias đã chuyển thì hoàn tất bookkeeping, không xóa index đang phục vụ.
- Đổi alias SKU thủ công ngoài FullReindexJob/rollback: projection có thể mismatch; request mới
  trả 503 tới khi projector sửa được. Không có cam kết phục vụ liên tục cho thao tác ngoài luồng.
- Kafka/Inventory tail failure vẫn được ghi `tail_replay=INCOMPLETE`, không được che thành thành
  công của cả pipeline bởi benchmark chỉ đo cutover.

## Trade-offs and rollout

Thêm một bản sao SKU ở dạng nested và giữ các generation cũ trong retention; cân dung lượng
đĩa cho cả build và rollback. Query vẫn tốn chi phí lọc/count/facet tại ES; không tuyên bố
chi phí hằng số. Độ trễ projection và khác biệt relevance đã nêu ở ADR-027 vẫn áp dụng.
Chuẩn bị cards nằm trong stage CUTOVER, nên kéo dài đoạn không cho admin hủy sau hàng rào
VERIFY hiện hành; run vẫn có deadline và dừng khi phát hiện mất lease. Không thay contract hủy run.

Rollout: build projection trước khi nhận traffic, cùng cursor secret trên các replica,
theo dõi readiness, error/latency, projector failure và disk. Tắt cờ dừng projector và làm
request mới trả 503; **không còn là rollback về grouping**. Rollback deployment/data phải
được thực hiện tường minh. Harness cũ so `grouping,cards` chỉ còn dùng với jar lịch sử;
benchmark policy hiện hành dùng `scripts/ci/search-read-model-bench.ps1`.

Hoàn tất nâng cấp/drain các projector ADR-027 trước khi chạy reindex/rollback theo giao thức
mới: bản cũ giữ lease qua nhiều pass thay vì nhả sau mỗi pass, có thể làm cutover mới chờ tới
khi pod cũ dừng/lease hết hạn. Benchmark này không kiểm chứng rolling deployment trộn hai phiên bản.

## Verification

Unit: không fallback, giữ cursor v2/v3, private build không publish, lỗi build/mất lease không
swap, checkpoint và paired swap của FullReindexJob/rollback. Integration trên ES ICU thật:
projection mới trước/sau alias swap, cursor PIT cũ sống qua swap, missing projection trả
retryable 503, fencing, và toàn bộ reindex/export/replay/rollback suite.

Benchmark mới và giới hạn phép đo nằm trong báo cáo performance liên kết ở trên.
