---
doc_id: review-adr-003
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-003 — Outbox và Debezium CDC cho việc phát event

## Status

`Accepted`

## Context

Mỗi thao tác ghi của Review đều phải làm hai việc: thay đổi dữ liệu trong PostgreSQL,
và cho các service khác biết điều đó. Làm hai việc này bằng hai hệ thống khác nhau
trong một luồng xử lý là bài toán dual-write: nếu ghi database thành công rồi publish
thất bại, downstream không bao giờ biết; nếu publish trước rồi ghi thất bại, downstream
biết về một sự việc chưa xảy ra.

Với Review, hệ quả cụ thể là điểm trung bình của sản phẩm ngừng phản ánh các đánh giá
mới, và Search hiển thị số liệu sai vô thời hạn — không có gì tự phát hiện được.

Auth Service trong hệ thống này phát event trực tiếp sau commit và ghi nhận đó là một
gap cần xác minh trước khi lên production. Review có tần suất ghi cao hơn và có một
consumer nội bộ phụ thuộc chặt vào event của chính mình, nên chấp nhận cùng rủi ro đó
là tốn kém hơn.

## Decision

Mọi event rời khỏi Review đều đi qua bảng `review_outbox`, được ghi trong **cùng
transaction** với thay đổi dữ liệu nghiệp vụ sinh ra nó. Không có Kafka producer nào
trong code nghiệp vụ (`REVIEW-INV-007`).

Debezium đọc WAL của PostgreSQL qua replication slot, dựng envelope CloudEvents từ các
cột metadata của outbox, và publish sang `t.review.events` với message key lấy từ cột
`partition_key`. `partition_key` luôn là `product_id`, kể cả với event có
`aggregate_id` là `review_id`.

Publication chỉ bao gồm bảng outbox; các bảng nghiệp vụ không đi qua CDC.

Bảng outbox partition theo tháng trên `created_at`. Cleanup là `DROP` partition sau
khi qua safety gate, không phải `DELETE`.

## Alternatives considered

**Publish trực tiếp sau commit.** Đơn giản nhất, không cần Debezium, không có
replication slot. Nhược điểm: cửa sổ giữa commit và publish là nơi event có thể mất
vĩnh viễn, và không có gì phát hiện được ngoài việc so sánh thủ công về sau.

**Outbox với worker polling.** Không cần Kafka Connect; một job đọc bảng outbox theo
chu kỳ rồi publish. Nhược điểm: hoặc poll dày và đốt CPU cùng IO liên tục, hoặc poll
thưa và tăng độ trễ. Cũng cần tự xử lý việc đánh dấu đã publish, khóa giữa nhiều
instance, và retry — tức là viết lại phần Debezium đã làm.

**CDC trực tiếp trên bảng nghiệp vụ, không có outbox.** Bỏ được một bảng, nhưng khi
đó hình dạng event bị buộc vào hình dạng schema: mọi lần đổi cột trở thành breaking
change của contract, và không thể phát một event mang cả giá trị cũ lẫn giá trị mới
như `REVIEW_UPDATED` cần.

**Transactional messaging của Kafka.** Không giải quyết được vấn đề, vì transaction
của Kafka và transaction của PostgreSQL vẫn là hai thứ tách rời.

## Consequences

**Tích cực.** Event không bao giờ mất khi database đã commit. Kafka hỏng không làm
đường ghi dừng: đánh giá vẫn được ghi và hiển thị, chỉ downstream là trễ. Hình dạng
event độc lập với schema database. Thứ tự theo sản phẩm được bảo đảm qua
`partition_key`.

**Tiêu cực.** Thêm Debezium và Kafka Connect vào hạ tầng vận hành. Cần
`wal_level = logical`. Bảng outbox tăng nhanh và cần cleanup theo lịch.

**Rủi ro vận hành lớn nhất.** Replication slot không tiến sẽ giữ WAL lại, và đĩa của
primary có thể đầy — lúc đó toàn bộ service dừng. Vì vậy cảnh báo connector đặt ở mức
Critical với ngưỡng 2 phút. Xóa replication slot để giải phóng đĩa đồng nghĩa với mất
vĩnh viễn mọi event chưa publish; đó là một quyết định phải có người chịu trách nhiệm,
không phải thao tác thường quy.

**Chi phí thứ hai.** Thiếu partition outbox làm **mọi** thao tác ghi thất bại, vì
transaction nào cũng chèn một dòng outbox. Việc tạo partition trước là bắt buộc và
phải được giám sát.

## Migration impact

Schema đã bao gồm bảng outbox và publication. Mọi thay đổi cấu trúc `review_outbox`
về sau phải phối hợp với cấu hình Debezium; đổi tên cột metadata sẽ làm connector
ngừng dựng được envelope.

## Verification

Integration test phải chứng minh mỗi transaction ghi để lại đúng số dòng outbox mong
đợi, với `partition_key = product_id`. Test end-to-end với Debezium phải chứng minh
message xuất hiện trên `t.review.events` đúng key. Test khôi phục phải chứng minh:
dừng connector, ghi vài đánh giá, khởi động lại connector, và mọi event đều tới nơi
đúng thứ tự.
