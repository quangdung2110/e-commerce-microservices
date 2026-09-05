---
doc_id: search-adr-010
service: search
status: superseded
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
superseded_by: ADR-013-product-category-snapshots
evidence:
  - docs/product/data.md
---

# ADR-010 — Search tự dẫn xuất `path`, `level` và `is_leaf` của cây danh mục (Superseded)

## Context

Search cần đường dẫn danh mục để hiển thị breadcrumb, lọc theo nhánh và dựng
sidebar. Product chỉ lưu `category_id`, `parent_id`, `name` và `slug`; `path` và
`is_leaf` là giá trị dẫn xuất và Product cố tình không lưu chúng.

Thiết kế Search trước đây kỳ vọng nhận `path`, `level` và `is_leaf` trong payload
event, và coi cấu trúc cây là bất biến sau khi tạo. Hệ quả là khi admin di chuyển
một nhánh, tài liệu cũ yêu cầu chạy full reindex thủ công — một thao tác nặng cho
một thay đổi nhỏ.

## Decision

Search tự tính `path`, `level` và `is_leaf` từ quan hệ `parent_id` trong chính
index `categories`. Sau mỗi thay đổi danh mục, Search chạy lại bước tính cây trên
toàn bộ node (quy mô hàng trăm) và ghi kết quả kèm `tree_version` tăng dần.

Khi `name` hoặc `path` của một danh mục đổi, Search đồng bộ `category_name` và
`category_path` xuống các SKU document thuộc danh mục đó bằng `_update_by_query`
có throttle.

## Alternatives considered

- **Yêu cầu Product phát `path` và `is_leaf`.** Buộc Product lưu dữ liệu dẫn xuất
  mà nó đã quyết định không lưu, và tạo hai nguồn cho cùng một giá trị.
- **Coi cấu trúc cây là bất biến và dùng full reindex khi cây đổi.** Đây là hành vi
  của thiết kế cũ: chi phí vận hành lớn và dễ bị quên, dẫn tới đường dẫn sai âm
  thầm.
- **Tính `path` lúc query bằng cách đi ngược `parent_id`.** Bỏ được bước đồng bộ
  nhưng làm mọi truy vấn lọc theo nhánh trở nên đắt và không lọc được ở tầng
  Elasticsearch.

## Consequences

Tích cực: di chuyển nhánh danh mục trở thành thao tác thường ngày, không cần
reindex; Search không phụ thuộc vào field mà Product không sở hữu; bước tính cây
cũng là công cụ sửa lệch chạy tay được.

Tiêu cực: Search phải tự phát hiện chu trình và node mồ côi rồi từ chối áp dụng cây
sai; đổi tên một node cha là thao tác ghi diện rộng trên index `products`, cần
throttle; giữa lúc đồng bộ, một số SKU có thể mang `category_path` cũ trong thời
gian ngắn.

## Migration impact

Bỏ `path`, `level`, `is_leaf` khỏi kỳ vọng payload event; thêm `tree_version` vào
mapping danh mục; thêm bước recompute vào luồng xử lý event danh mục. Bỏ ghi chú
“cấu trúc cây là bất biến” khỏi tài liệu và khỏi runbook.

## Verification

Test bắt buộc: đổi tên node cha thì `category_path` của SKU cháu được đồng bộ; di
chuyển node cập nhật `level` và `path` của cả nhánh; cây có chu trình bị từ chối và
giữ nguyên giá trị cũ; node mồ côi không làm hỏng response.
