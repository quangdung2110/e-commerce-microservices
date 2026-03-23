# Search Service - Events Consumed (Updated for Extreme Scale)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Events Structure (Source of Truth)](../product/product_events.md)
*   [Product Service - Workers (Outbox Producer)](../product/product_workers.md)

---

Tài liệu này định nghĩa tất cả các luồng sự kiện mà Search Service **tiêu thụ (consume)** từ các service khác. Search Service là **Read Model thuần túy** — nó **không produce event nào**. Mọi dữ liệu đổ về đều có nguồn gốc từ [Product Service](../product/product_events.md) thông qua Kafka.

> **Nguyên tắc:** Search Service là "bức gương phản chiếu" của Product Service. Nó không tạo ra dữ liệu mới, chỉ nhận → transform → index vào Elasticsearch để phục vụ End-User search.

## 1. Bản Đồ Kafka Topics

| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
|---|---|---|---|---|
| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_PRICE_UPDATED`, `PRODUCT_SKU_STATUS_CHANGED`, `PRODUCT_DELETED` | Topic chính — mọi dữ liệu sản phẩm |

> **Ghi chú:** Chỉ có duy nhất 1 topic cần subscribe. Mọi event liên quan đến sản phẩm (tạo, sửa, đổi trạng thái, đổi giá, xoá) đều chảy qua `t.product.events`. Xem danh sách đầy đủ tại [Product Events](../product/product_events.md#1-bản-đồ-kafka-topics).

## 2. Chi Tiết Xử Lý Từng Event Type
**Logic xử lý:**
### 2.1. PRODUCT_CREATED
**Nguồn:** [Product Events - 3.1](../product/product_events.md#31-product_created)

### 2.1. PRODUCT_CREATED
**Nguồn:** [Product Events - 3.1](../product/product_events.md#31-product_created)

1. Parse payload → Extract product + mảng SKUs data.
2. **Enrichment:** Tra cứu `category_path` và `category_name` từ [Category Cache](search_caching_schema.md#12-category-tree-cache) dựa vào `category_id`.
3. **Denormalization (Flatten):** 
   - Không sử dụng `nested` SKUs.
   - Gộp `attributes` thành mảng `searchable_attributes` tĩnh (VD: `["attr_loaida_dau"]`).
4. **Index (Bulk Insert):** Vì Search Service đã chuyển sang kiến trúc **SKU-First**, một event tạo sản phẩm (có N SKU) sẽ phải được Worker bẻ thành lệnh gửi N document độc lập xuống Elasticsearch (Bulk request `PUT products_write/_doc/{sku_id}`).

### 2.2. PRODUCT_UPDATED
**Nguồn:** [Product Events - 3.2](../product/product_events.md#32-product_updated)

**Logic xử lý:**
1. **Version Check (Critical):** Do mỗi SKU là 1 document, nếu đổi toàn bộ data Product (Ví dụ đổi Category), Worker phải push 1 lệnh `_update_by_query` để đè các trường thông tin Product (category, name) lên những SKU thuộc `product_id` đó.
2. Với mỗi document con, phải dùng Upsert và chỉ apply lệnh đè document khi `event.version > current_doc.version` của SKU đó.
3. 🔴 **BẮT BUỘC (Vá lỗi Đóng Băng Giá):** Vì Event `PRODUCT_UPDATED` của Product Service MẶC ĐỊNH mang theo sự phản ánh giá do Admin chủ động đổi (không phát kèm `PRODUCT_PRICE_UPDATED`), Worker xử lý `PRODUCT_UPDATED` CŨNG PHẢI thực hiện rà soát và map lại các trường `original_price`, `promotional_price`, và tính lại `best_price` y hệt như logic ở event cập nhật giá. Nếu bỏ quên bước này, giá sẽ bị đóng băng vĩnh viễn trên UI tìm kiếm.

**Logic xử lý — phụ thuộc vào `new_status`:**

| new_status | Hành động trên Elasticsearch |
|---|---|
| `ACTIVE` | Update `product_status = ACTIVE`. Sản phẩm xuất hiện trong kết quả search public |
| `HIDDEN` | Update `product_status = HIDDEN`. Sản phẩm biến mất khỏi search public |
| `BANNED` | Update `product_status = BANNED`. Ẩn khỏi search public |
| `DELETED` | Update `product_status = DELETED`. (Sử dụng lệnh **UPSERT** / Tombstone Pattern để triệt tiêu Zombie Product). |

> **Tombstone Pattern (Bia Mộ) cho vấn đề Zombie Product:** Do tính chất bất đồng bộ của mạng Kafka, Event `PRODUCT_DELETED` có thể đến SỚM HƠN cả Event `PRODUCT_CREATED`. Nếu dùng lệnh `update` bình thường, ES sẽ báo "Document not found" và bỏ qua lệnh xóa này. Khi event Create đến sau, nó lại tạo ra một "Zombie Product". Để chặn việc này, Worker phải sử dụng **UPSERT**. Nếu doc không tồn tại, nó sẽ tạo một document rỗng với status `DELETED` và ghim `version` lại.

```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.product_status = params.status; ctx._source.version = params.version;",
    "params": { "status": "DELETED", "version": 5 }
  },
  "upsert": {
    "sku_id": "{sku_id}",
    "product_status": "DELETED",
    "version": 5
  }
}
```
*(Ghi chú: Lệnh Update_By_Query cho tất cả SKU thuộc `product_id` tương ứng cần được triển khai nếu thao tác diễn ra ở cấp độ Product).*

### 2.4. PRODUCT_PRICE_UPDATED
**Nguồn:** [Product Events - 3.4](../product/product_events.md#34-product_price_updated)

**Logic xử lý:**
1. Parse payload → `sku_id`, `original_price`, `promotional_price`, `change_source`, `version`.
2. **Loại bỏ Painless Script:** Worker tự tính toán `best_price` = `MIN(original_price, promotional_price)` và `has_promotion` ngay trên RAM của Ứng dụng.
3. Bắn lệnh Partial Update trực tiếp, siêu nhẹ xuống ES.

```json
POST products_write/_update/{sku_id}
{
  "doc": {
    "original_price": 33990000,
    "promotional_price": 28891500,
    "best_price": 28891500,
    "has_promotion": true,
    "version": 6
  },
  "doc_as_upsert": true
}
```

> **Giải phóng Compute CPU cho Elasticsearch:** So với thiết kế cũ dùng Painless Script để loop qua array `nested`, thiết kế SKU-first cho phép Consumer (App) tính sẵn kết quả và ES chỉ việc ghi đè Disk I/O siêu tốc. Không còn rủi ro 100% CPU do Script Compute trong kỳ Mega Sale.

### 2.5. PRODUCT_SKU_STATUS_CHANGED (Bao gồm Inventory Sync)
**Nguồn:** [Product Events - 3.5](../product/product_events.md#35-product_sku_status_changed)

**Logic xử lý:**
1. Parse payload → `sku_id`, `new_status`, `version`. (Loại bỏ hoàn toàn field vô bổ như `total_quantity`).
2. Map `is_in_stock`: `true` nếu `new_status == 'ACTIVE'` (tồn kho >0 mặc định sẽ chuyển SKU sang ACTIVE tại Product Service).
3. Push lệnh Update siêu gọn nhẹ:

```json
POST products_write/_update/{sku_id}
{
  "doc": {
    "sku_status": "OUT_OF_STOCK",
    "is_in_stock": false,
    "version": 8
  },
  "doc_as_upsert": true
}
```

### 2.6. CATEGORY_UPDATED (Cập nhật tên danh mục)
**Logic xử lý:** Khi diễn ra việc đổi tên, Product Service sẽ phát Broadcast Event.
1. **Update By Query:** Do danh mục được Denormalized ở Elasticsearch, cần gọi script Update diện rộng thay vì từng sản phẩm lẻ:
```json
POST products/_update_by_query
{
  "query": { "term": { "category_id": "cat-smartphone" } },
  "script": { 
    "source": "if (ctx._source.category_version != null && params.version <= ctx._source.category_version) { ctx.op = 'none'; return; } ctx._source.category_name = params.new_name; ctx._source.category_version = params.version;",
    "params": { "new_name": "Điện thoại di động", "version": <event_version> } 
  }
}
```
> **Sửa lỗi Race Condition (Missing Version Check):** Lệnh `_update_by_query` ở trên phải bao gồm Version Check (Guard). Nếu để chạy thô, trong quá trình Elasticsearch mất hàng phút để update diện rộng, các Event mới của từng Product chui vào sẽ bị ghi đè thầm lặng bởi tên Category cũ (từ thời điểm bắt đầu quét batch nhưng batch vừa mới tới phần Update sản phẩm đó). Lỗi này sẽ dẫn đến kết quả Data Corrupted vĩnh viễn (Search thì ra chữ cũ, vào xem chi tiết thì ra chữ mới). Khắc phục bằng việc cài cắm `version` vào trong Param, và tự update version đó vào một field riêng `category_version`.

### 2.7. PRODUCT_DELETED
**Nguồn:** [Product Events - 3.6](../product/product_events.md#36-product_deleted)

**Logic xử lý:**
1. **Script Update (Soft Delete):** Xóa mềm document khỏi index thay vì xóa cứng (`DELETE`) để lưu trữ Version nhằm đối chiếu cản rác, tránh hiện tượng "Zombie Product" (Sản phẩm sống lại).
```json
POST products/_update/{product_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.version = params.version; ctx._source.status = 'DELETED';",
    "params": { "version": 5 }
  }
}
```
2. Invalidate cache: `DEL search:product:{product_id}` và đồng thời phải gọi `DEL search:categories:tree` (bắt buộc vì số lượng product hiển thị trong Category Tree đã bị thay đổi do xoá sản phẩm).

## 3. Chiến Lược Idempotency & Ordering

### 3.1. Chống Duplicate (Idempotency)
Elasticsearch `PUT _doc/{id}` và `POST _update/{id}` đều là **idempotent theo bản chất**. Nếu cùng event được deliver lại, kết quả vẫn như nhau (document bị overwrite hoặc bỏ qua nếu check version không thoả mãn).

### 3.2. Chống Out-of-Order (Event Ordering)
Product Service sử dụng `aggregate_id` (Product ID) làm Kafka Partition Key → đảm bảo tất cả events của cùng 1 product vào **cùng 1 partition** → **strict ordering trong partition**.

Tuy nhiên, vẫn có edge case khi consumer rebalance hoặc retry. Field `version` trong event payload là **lá chắn cuối cùng**:

```
IF event.version <= current_indexed_doc.version THEN
    SKIP event (stale data, đã có version mới hơn indexed rồi)
ELSE
    PROCESS event
END
```

> **Lưu ý Quan trọng:** Mọi event thay đổi bất kể là Update full hay cập nhật cục bộ (`STATE_CHANGED`, `PRICE_UPDATED`, `SKU_STATUS_CHANGED`, `DELETED`) ĐỀU phải đính kèm `version` và check logic này trong Painless Script. Nó là chìa khoá duy nhất ngăn Data Drift.

### 3.3. Xử Lý Lỗi (Error Handling)
- **Elasticsearch unreachable:** Event được nack → Kafka retry.
- **Document not found** (cho partial update): Log warning, skip. Document sẽ được tạo đầy đủ khi `PRODUCT_CREATED`/`PRODUCT_UPDATED` event đến.
- **Liên tiếp thất bại:** 5 retries → push event lên Dead Letter Topic `t.search.dlq` → Alert SysAdmin.
