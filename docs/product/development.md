---
doc_id: product-development
service: product
scope: current
status: proposed
owners:
  - Product Service team
evidence: []
verified_against_commit: null
---

# Product Service — Development Guide

> File này phục vụ việc phát triển và kiểm thử service. Refactor tài liệu này không
> đối chiếu với code, nên không command, version hay đường dẫn nào được bịa ra: phần
> chưa xác minh được đánh dấu `TBD`.

## 1. Prerequisites và local dependencies

Runtime dự kiến theo thiết kế: JDK 21, Maven, Docker. Phiên bản chính xác phải lấy từ
`pom.xml` và cấu hình CI của service.

Dependency cần chạy khi phát triển:

| Dependency | Vì sao cần | Ghi chú |
|---|---|---|
| PostgreSQL | Write store và outbox | Phải bật `wal_level = logical` nếu muốn chạy CDC local |
| Redis có RedisJSON | Point-read cache, ZSET pending, lock | Redis thường (không có module JSON) sẽ làm hỏng luồng cache |
| Kafka | Event in/out | Cần cho consumer và cho Debezium |
| Kafka Connect + Debezium | Forward outbox → Kafka | Có thể bỏ qua khi chỉ test luồng ghi DB |

Có thể mock trong unit test: Kafka, Redis và các service ngoài. Không nên mock
PostgreSQL vì phần lớn rủi ro nằm ở lock, transaction và constraint.

Port, compose file và script khởi tạo chính thức: `TBD` — liên kết tới compose/script
của service thay vì chép cấu hình vào đây.

## 2. Chạy local và cấu hình

Các bước copy-and-run để cài dependency, build, khởi động, kiểm tra health endpoint và
cleanup là `TBD`. Khi bổ sung, chúng phải trùng với command mà CI sử dụng.

Cấu hình local cần khai báo: kết nối PostgreSQL/Redis/Kafka, tên consumer group, TTL
cache, chu kỳ và kích thước lô của Phase 2, `lock_timeout`, lịch cronjob. Danh sách
biến đầy đủ ở [Operations](./operations.md#3-configuration).

Tuyệt đối không commit: credential thật, endpoint production, dump dữ liệu production.

## 3. Build, quality và tests

Command build, lint, format và test là `TBD` và phải trùng CI.

Bộ test cần bao phủ, theo thứ tự ưu tiên rủi ro:

| Loại | Nội dung bắt buộc |
|---|---|
| Unit | Công thức markdown (`PERCENTAGE` có/không cap, `FIXED_AMOUNT`, clamp về 0), suy ra `best_price`, suy ra `sku_status` theo tồn kho, guard của FSM |
| Integration DB | Lock order Product → SKU, transaction theo từng product, conditional update Phase 2, ràng buộc unique, validate `partition_key` trước khi insert outbox |
| Integration Redis | Lua script Phase 1 với các nhánh apply/stale/miss, TTL, chống stampede khi nhiều reader cùng miss |
| Integration Kafka | Allowlist theo consumer group, retry và DLQ, dedupe theo poll-batch, cấm ghi DB trong nhánh SKU của Event Handler |
| Workflow | Auto-hide khi toàn bộ SKU hết hàng; không auto-reactivate khi `admin_hidden = true`; markdown pending gate; expiry self-healing; cascade `CATEGORY_DELETED` |
| Contract | Provider test cho gRPC theo proto; contract test cho event khi đã có AsyncAPI |

Fixture không được chứa dữ liệu production hay PII.

## 4. Contract và schema development

gRPC contract nằm tại
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto).
Khi sửa proto: cập nhật contract trước, generate lại stub, chạy breaking-change check,
rồi mới sửa implementation. Tool version và command generate là `TBD`.

HTTP và event chưa có contract machine-readable. Bất kỳ thay đổi nào trên admin API
hoặc payload event hiện chỉ được ghi nhận trong [Interfaces](./interfaces.md), nên thay
đổi phải được thông báo tới consumer bằng tay. Ưu tiên bổ sung OpenAPI và AsyncAPI
trước khi mở rộng thêm interface mới.

Migration: nguồn schema là thư mục migration của service
(`src/main/resources/db/migration`). Mọi thay đổi schema phải backward compatible với
phiên bản đang chạy, có bước chạy local, và có kế hoạch rollback. Đổi tên cột được
Debezium sử dụng (nhất là `partition_key`) là breaking change ở tầng hạ tầng.

Lua script Phase 1 có bản thiết kế tại
[`scripts/inventory_fast_sync.lua`](./scripts/inventory_fast_sync.lua). Khi sửa
script trong code, phải cập nhật bản này để hai bên không lệch nhau về hợp đồng trả về
và về danh sách field được phép ghi.

## 5. Debugging

Điểm quan sát hữu ích khi debug local:

- Bảng `product_outbox_events`: kiểm tra event có được ghi trong đúng transaction và
  `partition_key` có đúng không.
- Redis: `JSON.GET product:sku:{sku_id}` để xem snapshot, `ZCARD`/`ZRANGE` trên
  `product:inventory:pending` để xem hàng đợi Phase 2.
- Kafka: đọc topic bằng console consumer để xác minh message key trùng `partition_key`.
- Trạng thái connector và replication slot khi event không tới nơi.

Thao tác cần tránh trên môi trường dùng chung: xóa key Redis hàng loạt (gây stampede),
xóa record outbox thủ công, và drop replication slot.

## 6. Các lỗi local phổ biến

| Triệu chứng | Nguyên nhân thường gặp | Cách kiểm tra |
|---|---|---|
| Lệnh `JSON.*` lỗi | Redis không có module RedisJSON | Kiểm tra module list của Redis |
| Ghi DB thành công nhưng không có event | Connector chưa chạy hoặc slot chưa tạo | Kiểm tra trạng thái Kafka Connect và slot |
| Event của cùng product rơi nhiều partition | `partition_key` sai hoặc rỗng | Kiểm tra record outbox và cấu hình connector |
| Deadlock khi chạy worker và admin API | Sai lock order | Xem log deadlock của PostgreSQL |
| Tồn kho không xuống DB | Phase 2 không chạy hoặc ZSET tồn đọng | `ZCARD product:inventory:pending` |
| Cache không đổi sau khi sửa giá | Event bị guard là stale | So `updated_at` của event với `price_updated_at` trong cache |

## 7. Definition of Done

Một thay đổi của Product Service chỉ hoàn thành khi, tương ứng với phạm vi thay đổi:

- Build, lint và test đã pass.
- Contract được cập nhật nếu interface thay đổi (proto ngay lập tức; OpenAPI/AsyncAPI
  khi đã có).
- Migration và [Data](./data.md) được cập nhật nếu schema thay đổi.
- [Workflows](./workflows.md) và [Domain](./domain.md) được cập nhật nếu hành vi
  nghiệp vụ thay đổi.
- ADR mới được thêm nếu có quyết định kiến trúc; ADR cũ được đánh dấu `Superseded` nếu
  bị thay thế.
- Backward compatibility đã được kiểm tra cho cả schema và event.
- Ownership của cache và quy tắc phát event vẫn được giữ nguyên
  ([PRODUCT-INV-002](./README.md#7-critical-invariants) đến `PRODUCT-INV-004`).
- Evidence và trạng thái của tài liệu liên quan được cập nhật.
