---
doc_id: inventory-adr-003
service: inventory
status: superseded
date: 2026-09-05
decision_owners:
  - Inventory Service team
supersedes: null
superseded_by: ADR-011-authoritative-consistency, ADR-014-integration-and-search-contracts
evidence:
  - java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql
  - java/services/inventory-service/src/main/resources/debezium/inventory-postgres-connector.json
---

# ADR-003 — Transactional outbox + Debezium CDC cho event public

## Status

`Superseded` by [ADR-011](./ADR-011-authoritative-consistency.md) and [ADR-014](./ADR-014-integration-and-search-contracts.md). This file is historical evidence and is not implementation authority. Code references point to the removed legacy implementation.

## Context

[ADR-002](./ADR-002-redis-stream-outbox.md) giải quyết việc đưa mutation ra khỏi Redis.
Nhưng ba event mà service khác thực sự tiêu thụ — `INVENTORY_STOCK_CHANGED`,
`INVENTORY_RETURN_RECEIVED_ALL`, `INVENTORY_SHIP_TO_CUSTOMER` — được sinh ra từ các
thao tác commit ở PostgreSQL, không phải từ Lua script.

Bài toán dual-write lặp lại ở phía PostgreSQL: nếu application commit transaction rồi
mới produce Kafka, một lần crash ở giữa sẽ khiến tồn kho đã thay đổi mà không ai được
báo. Với `INVENTORY_RETURN_RECEIVED_ALL` hậu quả cụ thể hơn: Order không bao giờ mở
bước hoàn tiền, và khách không được hoàn tiền dù hàng đã về kho.

## Decision

**Ghi event vào bảng `inventory_outbox` trong cùng transaction với thay đổi dữ liệu
sinh ra nó. Debezium đọc WAL của bảng đó và publish lên Kafka.**

Cụ thể:

- `inventory_outbox` là bảng append-only, mỗi dòng là một event chờ phát.
- `partition_key` là **cột riêng**, không phải trường trong JSONB. Debezium lấy thẳng
  giá trị cột đó làm Kafka message key và không bao giờ bóc payload ở tầng Connect.
- `refund_request_id` cũng là cột riêng, vì partial unique index chống double-emit cần
  index trên cột thật để hoạt động hiệu quả.
- Không có nơi nào trong application produce Kafka trực tiếp.
- Một CronJob dọn các dòng cũ hơn watermark để bảng không phình vô hạn.

## Alternatives considered

**Produce Kafka sau khi commit.** Đơn giản, không thêm hạ tầng. Nhưng có cửa sổ mất
event như mô tả ở Context. Không chọn.

**Outbox + polling job.** Application ghi outbox rồi một job định kỳ quét bảng, produce
và đánh dấu đã gửi. Không mất event, không cần Kafka Connect. Nhưng độ trễ bằng chu kỳ
poll, và job tạo tải đọc-ghi liên tục lên chính database đang phục vụ traffic. Debezium
đọc WAL nên không chạm vào bảng.

**Two-phase commit giữa PostgreSQL và Kafka.** Về lý thuyết giải quyết triệt để, nhưng
độ phức tạp vận hành cao và hiệu năng kém. Không tương xứng với vấn đề.

**Để Debezium theo dõi trực tiếp bảng nghiệp vụ thay vì bảng outbox.** Bỏ được một
bảng, nhưng khi đó hình dạng event bị buộc chặt vào hình dạng bảng: mọi thay đổi schema
trở thành breaking change cho consumer. Bảng outbox tách hai thứ đó ra.

## Consequences

**Tích cực:**

- Không có cửa sổ mất event: hoặc cả thay đổi dữ liệu lẫn event cùng commit, hoặc
  không gì cả.
- Không tải thêm lên application; Debezium đọc WAL.
- Hình dạng event độc lập với hình dạng bảng nghiệp vụ.
- `partition_key` là cột nên thứ tự theo key được bảo đảm mà không cần Connect hiểu payload.

**Tiêu cực:**

- Thêm Kafka Connect và một replication slot vào danh sách thành phần phải vận hành.
- Replication slot giữ WAL. Debezium dừng lâu thì đĩa PostgreSQL phình, và trường hợp
  xấu nhất là làm đầy đĩa của chính database đang phục vụ traffic.
- **Xóa replication slot để giải phóng đĩa sẽ mất vĩnh viễn mọi event chưa đọc.** Đây
  là thao tác nguy hiểm nhất trong toàn bộ vận hành service.
- Cleanup job hiện xóa theo **thời gian**, không theo offset CDC đã đọc. Nếu Debezium
  dừng quá watermark, job có thể xóa dòng chưa publish. Đây là rủi ro đã biết và chưa
  được vá; runbook yêu cầu tạm dừng job trong mọi sự cố CDC.
- `ck_inventory_outbox_event_type_enum` giới hạn ba loại event; thêm loại mới cần
  migration — có chủ đích, nhưng cũng là lý do stock snapshot v1 chưa phát được
  (xem [ADR-007](./ADR-007-stock-snapshot-v1-for-search.md)).

## Migration impact

- **Code:** mọi event mới phải đi qua outbox; không được thêm producer trực tiếp.
- **Data:** thêm loại event mới cần nới `ck_inventory_outbox_event_type_enum`.
- **Deployment:** connector phải được cập nhật sau khi bảng outbox có cấu trúc mới, và
  chỉ một connector được chạy trên một slot.
- **Vận hành:** cần alert cho trạng thái connector, độ trễ slot và số dòng outbox.

## Verification

- `inventory_outbox` và hai index của nó nằm trong
  migration V1 (legacy code, removed);
  partial unique index chống double-emit nằm trong
  migration V3 (legacy code, removed).
- Cấu hình connector nằm tại
  `src/main/resources/debezium` (legacy code, removed).
- Cần bổ sung: test crash sau commit trước khi CDC đọc, chứng minh event vẫn tới; và
  sửa cleanup job để dựa trên offset CDC thay vì thời gian. Chưa hạng mục nào hoàn thành.
