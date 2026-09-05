# Search write scripts

Các file `.painless` trong thư mục này là đặc tả chuẩn của write path. Chúng
phải được nạp nguyên văn thành inline script hoặc stored script cùng version với
mapping trong thư mục cha; không được tự sửa bản copy trong consumer/job.

| Script | Lệnh Elasticsearch | Upsert | Revision guard |
|---|---|---|---|
| `catalog_upsert.painless` | `_update` từng `sku_id` | `scripted_upsert: true`, `upsert: {}` | `catalog_revision` |
| `catalog_tombstone.painless` | `_update` từng `sku_id` | Không | `catalog_revision` |
| `stock_patch.painless` | `_update` từng `sku_id` | Không | `stock_revision` |
| `review_metrics_patch.painless` | `_update_by_query` theo `product_id` | Không | `review_revision` |
| `category_upsert.painless` | `_update` từng `category_id` | `scripted_upsert: true`, `upsert: {}` | `category_revision` |
| `category_tombstone.painless` | `_update` từng `category_id` | Không | `category_revision` |
| `category_product_count.painless` | `_update` từng `category_id` | Không | Không; job ghi giá trị tuyệt đối |

Mọi timestamp phải là ISO-8601 UTC có millisecond. Revision là số nguyên `long`
đơn điệu và mọi guard dùng phép so sánh `>` chặt. Lệnh update không upsert trả
`document_missing_exception` khi document không tồn tại; caller phân loại đó là
no-op bình thường, ghi metric thích hợp rồi ACK event.

`catalog_upsert` và `category_upsert` chỉ cho phép các field source tương ứng.
Caller phải truyền snapshot đã validate; field ngoài allowlist làm script fail để
`dynamic: strict` không bị biến thành một lỗi projection âm thầm.
