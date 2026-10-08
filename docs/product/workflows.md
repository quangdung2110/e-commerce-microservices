---
doc_id: product-workflows
service: product
scope: current
status: implemented
owners:
  - Product Service team
---

# Product Service — Workflows

## Workflow index

| ID | Workflow | Trigger |
|---|---|---|
| `WF-PRODUCT-001` | Tạo hoặc cập nhật Product | Admin HTTP command |
| `WF-PRODUCT-002` | Lifecycle và hard delete | Admin HTTP command |
| `WF-PRODUCT-003` | Category và attribute | Admin HTTP command |
| `WF-PRODUCT-004` | gRPC catalog point-read | Cart/Checkout gRPC query |
| `WF-PRODUCT-005` | Markdown snapshot | Promotion Kafka event |
| `WF-PRODUCT-006` | Outbox và Debezium | Transactional outbox/CDC |
| `WF-PRODUCT-007` | Internal buyer Product Detail read | Product Detail BFF mTLS query |

## WF-PRODUCT-001 — Tạo hoặc cập nhật Product

1. Validate category lá, attribute và input SKU.
2. Mở transaction; lock Product nếu đã tồn tại, sau đó lock SKU bị chạm.
3. Insert/update Product, SKU và media; nếu base price đổi khi markdown active thì tính
   lại `selling_price_vnd`. Media mutation resolve lại effective thumbnail: media gắn SKU
   chỉ ảnh hưởng SKU đó; media cấp Product ảnh hưởng mọi SKU, vì `media[]` của mỗi snapshot
   gồm cả media cấp Product, dù chỉ SKU đang fallback Product-level đổi thumbnail
   ([ADR-020](./decisions/ADR-020-media-fan-out-follows-snapshot-content.md)).
4. Tăng `catalog_revision` đúng một lần, build SKU snapshot cho từng SKU bị ảnh hưởng
   (gồm `thumbnail_storage_key` đã resolve) và insert CloudEvents outbox cùng revision.
5. Commit, rồi trả response.

Không ghi Redis/cache và không gọi Inventory trong luồng này.

## WF-PRODUCT-002 — Lifecycle và hard delete

Admin activate khi Product có ít nhất một SKU `ACTIVE`; hide/show không kiểm tra stock.
Khi xóa SKU, application từ chối SKU cuối cùng. Khi xóa Product hoặc SKU, transaction
chụp các ID liên quan, insert tombstone outbox trước, rồi hard delete row; FK cascade
dọn media/projection liên quan. Promotion chỉ consume Product tombstone, persist terminal
catalog-revision watermark, invalidate rule và không gửi markdown INACTIVE ngược lại
Product đã xóa.

## WF-PRODUCT-003 — Category và attribute

Category chỉ xóa khi không có con và không có Product. Application validate cycle và
category lá trong transaction (category đang chứa Product không nhận thêm category con),
tăng `category_revision` đúng một lần và phát snapshot/tombstone Category kèm `path`, `is_leaf`
và `facet_definitions` ([ADR-019](./decisions/ADR-019-category-facet-definitions.md)).
Đổi tên attribute đang được category map tăng `category_revision` và phát lại snapshot
`CATEGORY_ATTRIBUTE_CHANGED` cho từng category đó trong cùng transaction, category được lock theo
thứ tự ID; đổi attribute chưa map hoặc đổi option không phát event.
Rename, đổi slug hoặc đổi parent phát thêm `CATALOG_REINDEX_REQUESTED`;
Search coalesce rồi full reindex bằng export snapshot kèm replay offsets, không có
worker Product fan-out subtree ([ADR-014](./decisions/ADR-014-catalog-snapshot-replay-offsets.md)).

## WF-PRODUCT-004 — gRPC catalog point-read

`GetSku`/`GetBatchSkus` query PostgreSQL, join Product/SKU/media cần thiết và trả
catalog saleability cùng giá công bố. Không dùng cache, không trả stock. Caller Checkout
lấy allocation preview tại Inventory sau khi đã xác thực catalog/giá; Order thực hiện Reserve.

## WF-PRODUCT-005 — Markdown snapshot

Consumer nhận snapshot Promotion, lock Product, so `source_revision`, rồi upsert
projection và reprice tất cả SKU trong cùng transaction. `ACTIVE` chứa full
rule/discount/interval; `INACTIVE` là tombstone chỉ có reason, làm projection clear rule
configuration và SKU trở về base price. Nếu revision cũ/trùng hoặc Product không còn tồn
tại, ack no-op. Sau commit, outbox phát snapshot catalog với `catalog_revision` mới.

Promotion ghi ACTIVE full payload hoặc INACTIVE tombstone qua outbox tại logical campaign boundary;
Redis schedule + DB fallback có thể tạo bounded delivery delay nhưng không kích hoạt sớm.
Product không có safety schedule: consumer lag, retry exhaustion hoặc DLQ có thể làm giá
công bố stale cho tới khi Promotion/operations replay snapshot mới nhất. Snapshot `ACTIVE`
đến muộn sau `ends_at` vẫn ghi nhận `source_revision`, nhưng được lưu `INACTIVE` và không
được làm giá cũ có hiệu lực lại.

## WF-PRODUCT-006 — Outbox và Debezium

Debezium đọc WAL của `product_outbox_events` và phát CloudEvents structured JSON với
`partition_key` đã lưu. Consumer downstream dedupe theo CloudEvents `id`, guard SKU bằng
`catalog_revision` và Category bằng `category_revision`.

Product không có polling publisher hay retention job. Data retention của outbox là
responsibility Platform/DB lifecycle; trước khi có policy và công cụ vận hành được phê
duyệt, row outbox được giữ lại. Quy tắc background processing nằm tại
[ADR-016](./decisions/ADR-016-state-discriminated-markdown-snapshot.md); mapping
Kafka key hiện hành nằm tại
[ADR-012](./decisions/ADR-012-outbox-partition-key-by-product-aggregate.md).

## WF-PRODUCT-007 — Internal buyer Product Detail read

Product Detail BFF xác thực mTLS/service identity rồi query Product theo slug. Product
kiểm tra Product `ACTIVE`, lọc SKU catalog-inactive, resolve category path, tên/nhãn
thuộc tính và trục biến thể, cắt media còn 20 cấp Product và 10 mỗi SKU, resolve thumbnail
trên toàn bộ media, rồi trả `BuyerProductDetail` cùng giá công bố
([ADR-018](./decisions/ADR-018-resolved-buyer-product-detail-projection.md)). Không đọc Inventory, không ghi cache, không thay đổi
state và không trả bất kỳ field stock nào. Availability theo SKU là trách nhiệm read
composition của BFF qua Inventory Availability Read API, được ghi ở
[`docs/product-detail-bff`](../product-detail-bff/README.md).
