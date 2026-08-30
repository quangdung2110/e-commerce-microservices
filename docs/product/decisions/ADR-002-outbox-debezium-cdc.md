---
doc_id: product-adr-002
service: product
status: accepted
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
evidence: []
---

# ADR-002 — Transactional outbox phát qua Debezium CDC

## Status

`Accepted`

## Context

Mọi thay đổi sản phẩm phải tới được Search Service, Notification, Promotion và chính
Product Service (self-consume để refresh cache). Publish thẳng lên Kafka trong lúc xử
lý request tạo ra dual-write: transaction commit nhưng publish lỗi thì downstream mất
event, hoặc publish xong nhưng transaction rollback thì downstream nhận event ma.

Thứ tự cũng quan trọng: Search dựng document theo SKU nên hai thay đổi của cùng một sản
phẩm phải tới đúng thứ tự.

## Decision

Mọi event được ghi vào bảng `product_outbox_events` **trong cùng transaction** với thay
đổi dữ liệu. Debezium đọc WAL của PostgreSQL qua logical replication slot và forward
record sang Kafka.

Kafka message key được lấy **trực tiếp từ cột `partition_key`**, không dùng SMT để parse
JSON trong `payload`. Application phải validate `partition_key` khác `NULL` và khác rỗng
trước khi insert. Quy tắc: event cấp Product dùng `product_id`, cấp SKU dùng `sku_id`,
cấp Category dùng `category_id`.

Service không publish trực tiếp tới Kafka trong bất kỳ luồng nghiệp vụ nào.

## Alternatives considered

- **Publish trực tiếp sau commit** (cách Auth Service đang làm): đơn giản, không cần
  Kafka Connect. Nhưng mất event khi retry cạn, và không có bản ghi bền vững để đối
  chiếu. Với Product, event là nguồn duy nhất dựng lại read model của Search nên mất
  event là mất dữ liệu hiển thị.
- **Outbox + polling worker**: không cần Debezium, dễ vận hành hơn. Nhưng tốn CPU và
  connection pool cho vòng lặp `SELECT`, và độ trễ phụ thuộc chu kỳ poll.
- **Debezium đọc trực tiếp bảng nghiệp vụ** thay vì bảng outbox: bỏ được một bảng,
  nhưng payload khi đó là hình dạng của row chứ không phải hợp đồng nghiệp vụ, và
  không biểu diễn được event như `PRODUCT_STATUS_CHANGED` với `sku_ids[]`.

## Consequences

Tích cực: không còn dual-write; thứ tự trong phạm vi một `partition_key` được bảo đảm;
độ trễ thấp mà không tốn tài nguyên của request; bảng outbox là dấu vết để điều tra sự
cố.

Tiêu cực: thêm Kafka Connect vào đường vận hành. Nếu connector dừng, replication slot
giữ WAL lại và có thể làm đầy đĩa PostgreSQL — đây là failure mode nghiêm trọng nhất của
service, xem [Operations](../operations.md#6-failure-modes). Delivery là at-least-once
nên consumer bắt buộc phải idempotent. Bảng outbox cần job trim riêng, và cửa sổ
retention phải dài hơn thời gian tối đa chấp nhận được của một sự cố Connect.

Ràng buộc mới: đổi tên hoặc đổi kiểu cột được connector sử dụng — nhất là
`partition_key` — là breaking change ở tầng hạ tầng, không chỉ ở tầng ứng dụng.

## Migration impact

Schema cần bảng outbox và index theo `created_at`; PostgreSQL cần `wal_level = logical`
và một replication slot. Deployment phải quản lý cấu hình connector như một artifact có
version. Rollback ứng dụng không tự động rollback cấu hình connector.

## Verification

Chứng minh bằng: migration tạo bảng outbox, cấu hình connector, test tích hợp xác nhận
event chỉ tồn tại khi transaction commit, và metric độ trễ outbox → Kafka cùng
replication slot lag.
