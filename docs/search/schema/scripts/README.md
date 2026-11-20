# Search write scripts

Các file `.painless` trong thư mục này là đặc tả chuẩn của write path. Chúng
phải được nạp nguyên văn thành inline script hoặc stored script cùng version với
mapping trong thư mục cha; không được tự sửa bản copy trong consumer/job.

| Script | Lệnh Elasticsearch | Upsert | Revision guard |
|---|---|---|---|
| `catalog_upsert.painless` | `_update` từng `sku_id` | `scripted_upsert: true`, `upsert: {}` | `catalog_revision` |
| `catalog_tombstone.painless` | `_update` từng `sku_id` | Không | `catalog_revision` |
| `reindex_tombstone_copy.painless` | `_update` từng `sku_id`/`category_id`, chỉ vào candidate reindex | Có, chỉ tombstone nguồn đã xác nhận | `catalog_revision`/`category_revision`; giữ `deleted_at`, cùng/older tombstone là no-op |
| `availability_patch.painless` | `_update` từng `sku_id` | Không | `availability_revision` |
| `review_metrics_patch.painless` | `_update_by_query` theo `product_id` | Không | `review_revision` |
| `category_upsert.painless` | `_update` từng `category_id` | `scripted_upsert: true`, `upsert: {}` | `category_revision` |
| `category_tombstone.painless` | `_update` từng `category_id` | Không | `category_revision` |
| `category_product_count.painless` | `_update` từng `category_id` | Không | Không; job ghi giá trị tuyệt đối |

Mọi timestamp phải là ISO-8601 UTC có millisecond. Revision là số nguyên `long`
đơn điệu và mọi guard dùng phép so sánh `>` chặt. Lệnh update không upsert trả
`document_missing_exception` khi document không tồn tại. Với Inventory availability,
đây là vi phạm contract readiness: caller ghi metric, không ACK và không upsert.
Review vẫn có thể coi missing document là no-op vì không có readiness handshake.

`catalog_upsert` và `category_upsert` chỉ cho phép các field source tương ứng.
Caller phải truyền snapshot đã validate; field ngoài allowlist làm script fail để
`dynamic: strict` không bị biến thành một lỗi projection âm thầm.
