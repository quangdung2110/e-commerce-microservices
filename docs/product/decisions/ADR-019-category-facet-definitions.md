---
doc_id: product-adr-019
service: product
status: accepted
date: 2026-10-09
decision_owners:
  - Product Service team
  - Search Service team
supersedes: null
evidence:
  - contracts/events/product/v1/schemas/catalog-common.schema.json
  - contracts/http/product/v1/openapi.yaml
  - docs/search/data.md
  - java/services/product-service/src/main/java/com/platform/productservice/service/support/FacetDefinitionResolver.java
  - java/services/product-service/src/main/java/com/platform/productservice/service/CategoryCommandService.java
  - java/services/product-service/src/test/java/com/platform/productservice/integration/CategoryCommandIntegrationTest.java
---

# ADR-019 — Category snapshot mang `facet_definitions`

## Status

`Accepted`. Bổ sung hình dạng Category snapshot/export của [ADR-010](./ADR-010-catalog-event-revision-and-category-reindex.md)
và [ADR-014](./ADR-014-catalog-snapshot-replay-offsets.md); không thay quyết định nào trong hai ADR đó.

## Context

Search HTTP v2 (`GET /api/v2/search/categories/{id}/facets`) hứa `code`, `label` và `type` ổn định cho
mỗi nhóm facet. Search lập `filter_tokens` dạng `code:type:value` từ thuộc tính Product-level và thuộc
tính biến thể, nên không có tên hiển thị nào do Product đặt: nhãn hiện là token đã case-fold, bỏ dấu và
viết hoa chữ đầu. Tài liệu Search ghi `facet_definitions` là nguồn đúng và coi đây là dependency Product
chưa đóng; index `categories` và script `category_upsert.painless` của Search đã có chỗ cho field này.

Tên hiển thị, kiểu giá trị và quan hệ category–attribute đều thuộc Product. Search không có và không nên có
đường đọc ngược về Product để lấy chúng ngoài export + event.

## Decision

Mọi `category-upserted` và mọi item của `GET /internal/v1/categories/export` mang
`facet_definitions`: mảng `{code, label, type}`.

- Mỗi phần tử ứng với một dòng `category_attributes` **của chính category đó**: `code` = `attributes.code`,
  `label` = `attributes.name`, `type` = `attributes.value_type`. Sắp theo `code`. Mảng rỗng (không phải
  field vắng mặt) khi chưa có mapping. Tối đa 100 phần tử.
- Descriptor không mang option, `is_required` hay `is_variant`, và không thừa kế mapping của category cha:
  Product chỉ thuộc category lá, còn label/type thuộc về attribute nên một `code` có cùng descriptor ở mọi
  category. Search tra theo `code` qua các category lá của nhánh đang xem.
- Descriptor là giá trị dẫn xuất, không lưu cột nào. Event và export dùng chung một resolver nên cho cùng
  kết quả.
- Field là `required` trong schema `category` (producer luôn gửi); với consumer đây là thay đổi cộng thêm tương
  thích. Rollout theo thứ tự consumer chấp nhận trước rồi producer phát; Search hiện bỏ qua field lạ.
- **Revision.** Tên attribute nằm trong snapshot nên đổi tên attribute đang được category map làm snapshot lỗi
  thời. `AttributeService.replace` gọi `CategoryCommandService.republishForAttribute` trong cùng transaction:
  mỗi category đang map attribute tăng `category_revision` đúng một lần và phát `category-upserted` với
  `change_kind = CATEGORY_ATTRIBUTE_CHANGED`. Category được lock theo thứ tự ID để hai lần đổi tên đồng thời
  không deadlock. Đổi `code`/`value_type` không cần xử lý riêng vì đã bị chặn bằng `ATTRIBUTE_IN_USE` khi
  attribute còn được map. Không category nào map attribute thì không phát gì.
- Tạo/sửa/xóa option **không** phát event và không đổi descriptor.

Nằm ngoài quyết định này: nhãn từng option (`value_label`) của facet. Search vẫn dẫn xuất nó từ token cho tới
khi một ADR khác quyết định đưa option vào descriptor kèm cơ chế phát lại khi option đổi; việc đó cần fan-out
theo option và nên đo trước.

## Alternatives considered

- **Search gọi Product khi dựng facet.** Thêm dependency đồng bộ vào đường đọc storefront và một route nội bộ
  mới cho Search. Bị loại vì Search có sẵn đường export + event với revision guard.
- **Event riêng cho attribute.** Cho phép Search dựng nhãn độc lập với category, nhưng tạo thêm một stream cần
  ordering/replay/export riêng (v1 cố ý không có event attribute). Descriptor đi theo category tận dụng lại
  `category_revision`, export và replay offsets sẵn có.
- **Mọi mapping của cây cha thừa kế xuống con.** Phức tạp và sai với mô hình hiện hành, nơi Product validate
  chỉ theo mapping trực tiếp của category lá.
- **Bao gồm option ngay.** Nhãn facet đầy đủ hơn nhưng kéo theo fan-out khi option đổi và payload lớn hơn;
  hoãn.

## Consequences

- Nhãn nhóm facet của Search là tên admin đặt thay vì token đã case-fold; nhãn từng option vẫn dẫn xuất.
- Đổi tên một attribute dùng rộng (ví dụ "Màu sắc") phát một snapshot cho mỗi category map nó. Số lượng bị chặn
  bởi số category và chỉ xảy ra khi admin đổi tên, nên chấp nhận.
- Category snapshot lớn thêm tối đa 100 phần tử nhỏ; `x-max-payload-bytes` 131072 của kênh category vẫn rộng
  rãi.

## Migration impact

- Contract: `catalog-common.schema.json#/$defs/category` thêm `facet_definitions` (+ `facet_definition`),
  OpenAPI thêm `FacetDefinition` và field `facet_definitions` của `CategoryExport`, ví dụ
  `catalog-category-upserted.v1.json` cập nhật. Ghi trong `contracts/CHANGELOG.md`.
- Code: `FacetDefinitionResolver`, `CatalogEventPublisher.categoryUpserted`, `CatalogExportReader`,
  `AttributeService.replace`, `CategoryCommandService.republishForAttribute`. Không có migration database.
- Search (đã làm 2026-10-09): `CatalogPayloads.CategorySnapshot` nhận `facet_definitions`, `CatalogProjection`
  ghi nguyên vào index `categories` (mapping và script đã cho phép; snapshot thiếu field giữ giá trị cũ, mảng
  rỗng thay thế), Category export dùng chung đường này khi reindex, và `FacetLabelResolver` đặt `label` nhóm
  facet theo `code` qua danh mục cùng mọi danh mục con. Option vẫn dẫn xuất. Nhãn được cache 60 giây.
- Rollback: Search bỏ qua field lạ nên có thể gỡ producer mà không ảnh hưởng consumer hiện tại.

## Verification

- `CategoryCommandIntegrationTest`: snapshot rỗng khi chưa có mapping, sắp theo `code`, đổi theo mapping,
  đổi tên attribute phát lại đúng các category map nó với `category_revision` tăng một lần, đổi attribute chưa
  map hoặc đổi option không phát event.
- `CatalogExportAndLockingIntegrationTest`: export và `category-upserted` cho cùng `facet_definitions`.
- `CatalogEventContractTest`: event hợp lệ với `category-upserted.schema.json`, kể cả mảng rỗng.
