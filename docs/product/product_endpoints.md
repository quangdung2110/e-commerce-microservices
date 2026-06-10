# Product Service - Danh Sách API Endpoints

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---

Tài liệu này tổng hợp toàn bộ API endpoints của Product Service, bao gồm: method, endpoint, mô tả, authentication, request payload, và chi tiết từng bước hoạt động.

## Quy Ước Chung

*   **Base Path**: `/api/v1/products` (Product APIs), `/api/v1/categories` (Category APIs), `/api/v1/internal/products` (Internal APIs)
*   **Authentication**: Admin APIs dùng JWT token xác thực qua Gateway. Internal APIs dùng service-to-service authentication.
*   **Base Path cho Internal**: `/api/v1/internal/products`
*   **gRPC Base Path**: Internal service-to-service communication qua gRPC (Protocol Buffers)

---

## Nhóm 1: Product Management (Admin)

### 1.1. Tạo Sản Phẩm Bản Nháp
**Method:** `POST`
**Endpoint:** `/api/v1/products`
**Auth:** Admin (JWT)
**Mô tả:** Tạo mới một sản phẩm ở trạng thái DRAFT.

**Request Body:**
```json
{
  "category_id": "cat-dien-thoai",
  "name": "iPhone 15 Pro Max 256GB",
  "description": "Mô tả sản phẩm...",
  "attributes_json": {
    "material": "Titanium",
    "origin": "Vietnam"
  },
  "media": [
    {
      "media_type": "IMAGE",
      "url": "https://cdn.example.com/ip15-main.jpg",
      "thumbnail_url": "https://cdn.example.com/ip15-main-thumb.jpg",
      "sort_order": 1
    }
  ],
  "skus": [
    {
      "sku_code": "IP15PM-256-BLK",
      "original_price": 34990000.00,
      "variant_attributes_json": {"color": "Đen", "storage": "256GB"},
      "media": [
        {
          "media_type": "IMAGE",
          "url": "https://cdn.example.com/ip15-black.jpg",
          "sort_order": 1
        }
      ]
    }
  ]
}
```

**Chi tiết hoạt động:**
1. **Validation** — Kiểm tra `category_id` tồn tại trong PostgreSQL. Kiểm tra payload attributes đủ các trường `is_required = true` theo Category. **Guard giá SKU:** Tất cả SKU phải có `original_price > 0`, nếu vi phạm → Return 400.
2. **Write Transaction** — Mở `@Transactional`, `INSERT INTO products` (status: DRAFT), `INSERT INTO product_skus` (status: ACTIVE), `INSERT INTO product_outbox_events` (`PRODUCT_CREATED`). Commit.
3. **Cache Warm** — (Tùy chọn) Ghi `product:sku:{id}` vào Redis.
4. **Response** — HTTP 201 với JSON chứa `product_id`, danh sách SKU.
5. **CQRS** — Debezium CDC đọc WAL, phát event sang Kafka `t.product.events` cho Search Service.

---

### 1.2. Lấy Chi Tiết Sản Phẩm (Admin)
**Method:** `GET`
**Endpoint:** `/api/v1/products/{id}`
**Auth:** Admin (JWT)
**Mô tả:** Lấy thông tin chi tiết một sản phẩm (bao gồm tất cả SKU, media, attributes) phục vụ Admin Dashboard.

**Chi tiết hoạt động:**
1. **Query Database** — Truy vấn PostgreSQL bảng `products` JOIN `product_skus` JOIN `product_media` theo `product_id`.
2. **Response** — HTTP 200 với JSON đầy đủ product + skus[] + media.

---

### 1.3. List Sản Phẩm (Admin)
**Method:** `GET`
**Endpoint:** `/api/v1/products`
**Auth:** Admin (JWT)
**Mô tả:** List sản phẩm với phân trang và bộ lọc.

**Query Parameters:**
- `page` (int, default: 0), `size` (int, default: 20)
- `status` (string, optional): DRAFT | ACTIVE | HIDDEN
- `category_id` (string, optional)
- `search` (string, optional): tìm theo name
- `sort` (string, default: created_at DESC)

**Chi tiết hoạt động:**
1. **Query Database** — Truy vấn PostgreSQL `products` với điều kiện lọc và phân trang.
2. **Response** — HTTP 200 với JSON array sản phẩm + pagination metadata.

---

### 1.4. Cập Nhật Sản Phẩm
**Method:** `PUT`
**Endpoint:** `/api/v1/products/{id}`
**Auth:** Admin (JWT)
**Mô tả:** Cập nhật thông tin cấp sản phẩm (tên, mô tả, danh mục, thuộc tính, hình ảnh).

**Request Body:**
```json
{
  "name": "iPhone 15 Pro Max 256GB (Updated)",
  "description": "Mô tả cập nhật...",
  "category_id": "cat-dien-thoai",
  "attributes_json": {
    "material": "Titanium Grade 5",
    "origin": "Vietnam"
  },
  "media": [
    {
      "media_type": "IMAGE",
      "url": "https://cdn.example.com/ip15-main-updated.jpg",
      "thumbnail_url": "https://cdn.example.com/ip15-main-updated-thumb.jpg",
      "sort_order": 1
    }
  ]
}
```

**Chi tiết hoạt động:**
1. **Lock Product** — `SELECT ... FOR NO KEY UPDATE` để lock Product cha trước.
2. **Validate** — Kiểm tra `category_id` tồn tại (nếu thay đổi). Kiểm tra payload attributes đủ các trường `is_required = true` theo Category.
3. **Update Product** — Trong `@Transactional`, cập nhật các trường `products`: `name`, `description`, `category_id`, `attributes_json`, `media`. Không xử lý SKU tại endpoint này.
4. **Snapshot & Outbox** — Materialize snapshot Product. Query danh sách `sku_ids[]` thuộc product trong cùng transaction. Insert `PRODUCT_UPDATED` vào outbox với `sku_ids[]` để Search Service có thể query theo `sku_id` cập nhật document.
5. **In-band Cache Refresh** — Ngay sau commit và trước HTTP response, overwrite `product:sku:{sku_id}` trong Redis bằng dữ liệu mới nhất (JSON.SET với TTL 3600s) cho từng SKU của product.
6. **Response** — HTTP 200 với JSON cập nhật.
7. **CQRS** — Debezium CDC phát event sang Kafka.

---

### 1.5. Xóa Mềm Sản Phẩm
**Method:** `DELETE`
**Endpoint:** `/api/v1/products/{id}`
**Auth:** Admin (JWT)
**Mô tả:** Xóa mềm sản phẩm. Chỉ cho phép khi `status` là `DRAFT` hoặc `HIDDEN`. Không cho phép xóa `ACTIVE`.

**Chi tiết hoạt động:**
1. **Validate Status** — Nếu `status = ACTIVE` → Return 400 Bad Request.
2. **Soft Delete Transaction** — `@Transactional`, `UPDATE products SET is_deleted = true`. Lấy danh sách `sku_ids[]` trong cùng transaction. Xóa orphan markdown: `DELETE FROM product_markdown_applied WHERE product_id = ?`. Reset `promotional_price = NULL` cho tất cả SKU. Insert `PRODUCT_DELETED` vào outbox.
3. **Cache Invalidate** — Overwrite `product:sku:{sku_id}` với `is_deleted = true`. Xóa key markdown: `DEL product:markdown:active:{sku_id}` cho tất cả SKU.
4. **Response** — HTTP 204 No Content.
5. **CQRS** — Debezium CDC phát event, Search Service xóa khỏi Elasticsearch.

---

### 1.6. Chuyển Trạng Thái Sản Phẩm
**Method:** `PATCH`
**Endpoint:** `/api/v1/products/{id}/status`
**Auth:** Admin (JWT)
**Mô tả:** Chuyển trạng thái sản phẩm qua FSM. Commands: SUBMIT (DRAFT→ACTIVE), HIDE (ACTIVE/HIDDEN→HIDDEN), SHOW (HIDDEN→ACTIVE).

**Request Body:**
```json
{
  "command": "SUBMIT",
  "changed_by": "admin-uuid-007",
  "changed_by_type": "ADMIN",
  "reason": null
}
```

**Chi tiết hoạt động:**
1. **Lock Product** — `SELECT 1 FROM products WHERE product_id = :id FOR NO KEY UPDATE` (dòng đầu tiên trong transaction).
2. **FSM Guard Check:**
   - `SUBMIT`: Kiểm tra ít nhất 1 SKU có `original_price > 0`. Nếu không → Return 400.
   - `SHOW`: Đếm `SUM(display_quantity)` từ `product_skus`. Chỉ chuyển ACTIVE nếu tổng tồn > 0.
3. **State Transition:**
   - `HIDE`: Set `admin_hidden = TRUE`.
   - `SHOW`: Reset `admin_hidden = FALSE`.
   - `SUBMIT`: Giữ nguyên `admin_hidden = FALSE`.
4. **Outbox** — Insert `PRODUCT_STATUS_CHANGED` với `sku_ids[]` (query trong transaction), `changed_by`, `changed_by_type`, `reason`.
5. **In-band Cache Refresh** — Overwrite `product:sku:{sku_id}` ngay sau commit.
6. **Response** — HTTP 200 với trạng thái mới.
7. **CQRS** — Debezium CDC phát event. Promotion Service lắng nghe `PRODUCT_STATUS_CHANGED` (DRAFT→ACTIVE) để áp markdown nếu có.

---

### 1.7. Thêm SKU vào Sản Phẩm
**Method:** `POST`
**Endpoint:** `/api/v1/products/{id}/skus`
**Auth:** Admin (JWT)
**Mô tả:** Thêm một SKU mới vào sản phẩm đã có.

**Request Body:**
```json
{
  "sku_code": "IP15PM-256-WHT",
  "original_price": 34990000.00,
  "variant_attributes_json": {"color": "Trắng", "storage": "256GB"},
  "media": []
}
```

**Chi tiết hoạt động:**
1. **Lock Product** — `SELECT ... FOR NO KEY UPDATE`.
2. **Validate** — Kiểm tra `product_id` tồn tại và không bị `is_deleted`. Kiểm tra `sku_code` chưa tồn tại.
3. **Insert SKU** — `INSERT INTO product_skus` (status: ACTIVE).
4. **Outbox** — Insert `SKU_CREATED` vào outbox với full snapshot SKU. `partition_key = sku_id` (vì `aggregate_type = ProductSku`). Payload chứa đầy đủ thông tin SKU để Search Service index document mới mà không cần gọi lại API.
5. **In-band Cache Refresh** — Overwrite `product:sku:{sku_id}`.
6. **Response** — HTTP 201 với SKU vừa tạo.

---

### 1.8. Xóa Mềm SKU
**Method:** `DELETE`
**Endpoint:** `/api/v1/products/{id}/skus/{sku_id}`
**Auth:** Admin (JWT)
**Mô tả:** Xóa mềm một SKU cụ thể khỏi sản phẩm.

**Chi tiết hoạt động:**
1. **Lock Product + SKU** — `SELECT ... FOR NO KEY UPDATE` (Product), `SELECT ... FOR UPDATE` (SKU).
2. **Soft Delete** — `UPDATE product_skus SET is_deleted = true, deleted_at = NOW()`. Xóa orphan markdown: `DELETE FROM product_markdown_applied WHERE sku_id = ?`. Reset `promotional_price = NULL`.
3. **Outbox** — Insert `SKU_DELETED` vào outbox.
4. **Cache Invalidate** — Overwrite `product:sku:{sku_id}` với `is_deleted = true`. Xóa key markdown.
5. **Response** — HTTP 204 No Content.

---

## Nhóm 2: Category Management (Admin)

### 2.1. Lấy Cây Danh Mục
**Method:** `GET`
**Endpoint:** `/api/v1/categories/tree`
**Auth:** Admin (JWT)
**Mô tả:** Lấy toàn bộ cây danh mục (hierarchical tree) để hiển thị trong Admin Dashboard.

**Chi tiết hoạt động:**
1. **Query Database** — Truy vấn bảng `categories`, sắp xếp theo `level`, `parent_id`.
2. **Build Tree** — Duyệt recursive hoặc dùng CTE để build cây phân cấp.
3. **Response** — HTTP 200 với JSON tree (category_id, name, slug, parent_id, level, children[]).

---

### 2.2. Lấy Chi Tiết Danh Mục
**Method:** `GET`
**Endpoint:** `/api/v1/categories/{id}`
**Auth:** Admin (JWT)
**Mô tả:** Lấy thông tin chi tiết một danh mục.

**Chi tiết hoạt động:**
1. **Query Database** — Truy vấn bảng `categories` theo `category_id`.
2. **Response** — HTTP 200 với JSON category + attributes (category_attributes).

---

### 2.3. List Danh Mục
**Method:** `GET`
**Endpoint:** `/api/v1/categories`
**Auth:** Admin (JWT)
**Mô tả:** List tất cả danh mục (flat list).

**Query Parameters:**
- `page`, `size` (phân trang)
- `level` (lọc theo cấp độ)
- `parent_id` (lọc theo danh mục cha)

---

### 2.4. Tạo Danh Mục
**Method:** `POST`
**Endpoint:** `/api/v1/categories`
**Auth:** Admin (JWT)
**Mô tả:** Tạo danh mục mới.

**Request Body:**
```json
{
  "parent_id": "cat-dien-tu",
  "name": "Điện thoại thông minh",
  "slug": "dien-thoai-thong-minh",
  "level": 2,
  "is_leaf": true
}
```

**Chi tiết hoạt động:**
1. **Validate** — Kiểm tra `parent_id` tồn tại (nếu có). Kiểm tra không tạo circular dependency.
2. **Insert** — `INSERT INTO categories`. Tính toán `path` (full breadcrumb path từ root đến category) trong cùng transaction. Insert Outbox `CATEGORY_CREATED` với `path`.
3. **Response** — HTTP 201 với category mới.

---

### 2.5. Cập Nhật Danh Mục
**Method:** `PUT`
**Endpoint:** `/api/v1/categories/{id}`
**Auth:** Admin (JWT)
**Mô tả:** Cập nhật tên, slug, metadata của danh mục.

**Chi tiết hoạt động:**
1. **Update** — `UPDATE categories`. Insert Outbox `CATEGORY_UPDATED` (với `old_name`, `new_name`, `new_path`).
2. **Response** — HTTP 200.
3. **CQRS** — Search Service nhận event, dùng Update By Query trên Elasticsearch để cập nhật tên trên toàn bộ sản phẩm thuộc danh mục.

---

### 2.6. Xóa Danh Mục
**Method:** `DELETE`
**Endpoint:** `/api/v1/categories/{id}`
**Auth:** Admin (JWT)
**Mô tả:** Xóa danh mục. Kiểm tra không có sản phẩm nào thuộc danh mục này.

**Chi tiết hoạt động:**
1. **Validate** — Kiểm tra không có sản phẩm active thuộc `category_id`.
2. **Delete** — `DELETE FROM categories`. Insert Outbox `CATEGORY_DELETED` với `descendant_category_ids`.
3. **Write Model Update** — Product Event Handler nhận `CATEGORY_DELETED`, chuyển sản phẩm liên quan sang `HIDDEN` tại PostgreSQL (không phát thêm `PRODUCT_STATUS_CHANGED`).
4. **Response** — HTTP 204.
5. **CQRS** — Search Service nhận `CATEGORY_DELETED`, nullify category metadata và update `product_status = HIDDEN` trên Elasticsearch từ chính event này.

---

## Nhóm 3: Internal APIs (Service-to-Service)

### 3.1. Point-Read 1 SKU (gRPC)
**Protocol:** gRPC (Protocol Buffers)
**Service:** `product.v1.ProductService`
**RPC Method:** `GetProductSku`
**Auth:** Internal (mTLS / Service Account)
**Mô tả:** Đọc nhanh thông tin 1 SKU cho Cart, Order, Promotion Service.

**Response Fields:**
`sku_id`, `product_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `sku_status`, `product_status`, `is_available`, `display_quantity`, `variant_attributes`, `main_image_url`

**Chi tiết hoạt động:**
1. **Redis GET** — `JSON.GET product:sku:{sku_id}`.
2. **PER (Probabilistic Early Expiration):** Nếu TTL còn < 5%, 1 request tự nguyện ở lại background query DB và ghi đè cache.
3. **Cache Miss:** Cross-pod distributed lock `SETNX product:lock:fetch:{sku_id} 1 EX 2`. Trúng lock → query DB (product_skus JOIN products), `JSON.SET` với TTL 3600s, giải phóng lock. Trượt lock → sleep 50ms + retry.
4. **Response** — Protobuf response.

---

### 3.2. Point-Read Batch SKU (gRPC)
**Protocol:** gRPC
**RPC Method:** `GetBatchProductSkus`
**Auth:** Internal (mTLS / Service Account)
**Mô tả:** Đọc nhanh N SKU cho Cart Service (hiển thị giỏ hàng). Phải bọc Circuit Breaker phía Cart.

**Chi tiết hoạt động:**
1. **Redis MGET** — `JSON.MGET product:sku:{id1} product:sku:{id2} ...` trong 1 round-trip.
2. **Identify Misses** — Collect SKU miss (null) vào `missedSkus`.
3. **Batch DB Query** — Nếu `missedSkus` không rỗng: 1 câu truy vấn `SELECT ... FROM product_skus ps JOIN products p ON ps.product_id = p.product_id WHERE ps.sku_id IN (...)` (1 round-trip).
4. **Cache Warm** — Redis Pipelining: tất cả `JSON.SET product:sku:{sku_id}` trong 1 round-trip duy nhất.
5. **Response** — Protobuf với danh sách SKU (hit + miss đã fetch).

---

### 3.3. Category Tree Snapshot (Internal)
**Method:** `GET`
**Endpoint:** `/api/v1/internal/products/categories/tree`
**Auth:** Internal (service-to-service)
**Mô tả:** Search Category Tree Sync Worker dùng để đồng bộ dữ liệu category vào Search Service.

**Response Fields:** `category_id`, `name`, `slug`, `parent_id`, `level`, `path`

**Chi tiết hoạt động:**
1. **Query Database** — Snapshot nhất quán tại thời điểm đọc.
2. **Response** — Read-only, không trigger side-effect.

---

### 3.4. Product Export for Reindex (Internal)
**Method:** `GET`
**Endpoint:** `/api/v1/internal/products/export`
**Auth:** Internal (service-to-service)
**Mô tả:** Search Full Reindex Job quét dữ liệu theo trang.

**Query Parameters:** `page`, `size`, `updated_since` (watermark for catch-up)

**Response Fields per Record:** `skus[]` (sku_id, sku_code, original_price, promotional_price, best_price, has_promotion, sku_status, variant_attributes_json), `attributes_json`, `created_at`, `updated_at`

**Chi tiết hoạt động:**
1. **Pagination Query** — `ORDER BY product_id ASC, sku_id ASC` (stable sort cho safe replay).
2. **Response** — Read-only, sort ổn định, hỗ trợ watermark.

---

### 3.5. Product Snapshot by ID (Internal)
**Method:** `GET`
**Endpoint:** `/api/v1/internal/products/{product_id}/snapshot`
**Auth:** Internal (service-to-service)
**Mô tả:** Search Retry Replayer dùng để rebuild document khi gặp `document_missing_exception`.

**Response Fields:** `skus[]` (sku_id, sku_code, original_price, promotional_price, best_price, has_promotion, sku_status, variant_attributes_json, updated_at), `attributes_json`

**Chi tiết hoạt động:**
1. **Query Database** — Full snapshot Product + SKUs tại thời điểm đọc.
2. **Response** — Read-only, không phát side-effect nghiệp vụ.

---

## Tổng Hợp Bảng Endpoints

| Method | Endpoint | Mô tả | Auth | Đọc/Ghi |
|---|---|---|---|---|
| POST | `/api/v1/products` | Tạo sản phẩm DRAFT | Admin | Write |
| GET | `/api/v1/products` | List sản phẩm (Admin) | Admin | Read |
| GET | `/api/v1/products/{id}` | Chi tiết sản phẩm | Admin | Read |
| PUT | `/api/v1/products/{id}` | Cập nhật sản phẩm | Admin | Write |
| DELETE | `/api/v1/products/{id}` | Xóa mềm sản phẩm | Admin | Write |
| PATCH | `/api/v1/products/{id}/status` | Chuyển trạng thái FSM | Admin | Write |
| POST | `/api/v1/products/{id}/skus` | Thêm SKU | Admin | Write |
| DELETE | `/api/v1/products/{id}/skus/{sku_id}` | Xóa mềm SKU | Admin | Write |
| GET | `/api/v1/categories` | List danh mục | Admin | Read |
| GET | `/api/v1/categories/tree` | Cây danh mục | Admin | Read |
| GET | `/api/v1/categories/{id}` | Chi tiết danh mục | Admin | Read |
| POST | `/api/v1/categories` | Tạo danh mục | Admin | Write |
| PUT | `/api/v1/categories/{id}` | Cập nhật danh mục | Admin | Write |
| DELETE | `/api/v1/categories/{id}` | Xóa danh mục | Admin | Write |
| gRPC | `GetProductSku` | Point-read 1 SKU | Internal | Read |
| gRPC | `GetBatchProductSkus` | Point-read N SKU | Internal | Read |
| GET | `/api/v1/internal/products/categories/tree` | Category snapshot | Internal | Read |
| GET | `/api/v1/internal/products/export` | Export for reindex | Internal | Read |
| GET | `/api/v1/internal/products/{id}/snapshot` | Product snapshot | Internal | Read |
