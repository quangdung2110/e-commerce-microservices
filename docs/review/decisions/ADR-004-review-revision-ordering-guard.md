---
doc_id: review-adr-004
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
---

# ADR-004 — `review_revision` làm ordering guard cho downstream

## Status

`Accepted`

## Context

Rating của một sản phẩm được nhiều hệ thống hiển thị lại: Search giữ nó trong document
SKU, Product giữ nó trong read-model trang sản phẩm. Cả hai đều phải quyết định, cho
mỗi bản cập nhật nhận được, rằng nó mới hơn hay cũ hơn thứ họ đang có.

Có ba nguồn khiến thứ tự không hiển nhiên:

- Delivery là at-least-once, nên cùng một event có thể tới hai lần.
- Consumer retry sau lỗi có thể xử lý lại một event đã cũ.
- Trong lúc Search chạy full reindex, snapshot export và event replay ghi vào cùng
  document song song — hai đường hoàn toàn khác nhau cho cùng một dữ liệu.

`updated_at` không dùng làm khóa thứ tự được: hai mutation có thể trùng timestamp, và
đồng hồ không phải nguồn thứ tự đáng tin trong hệ phân tán. Partition key của Kafka
bảo đảm thứ tự trong một partition, nhưng không nói gì về trường hợp snapshot import
chạy song song với replay — snapshot không đi qua Kafka.

Search ADR-015 yêu cầu mọi upstream cấp một revision đơn điệu; ADR này là phần Review
thực hiện yêu cầu đó.

## Decision

`product_rating_aggregates` mang cột `review_revision` kiểu `BIGINT`, tăng đơn điệu
trong phạm vi một `product_id`, bắt đầu từ 1.

Mọi thay đổi làm đổi aggregate — kể cả reconciliation sửa lệch, kể cả cập nhật chỉ
đổi nội dung mà không đổi điểm — phải tăng `review_revision` trong **cùng transaction**
với giá trị mới và với dòng outbox (`REVIEW-INV-006`).

Cả hai đường cấp dữ liệu cho downstream mang cùng một giá trị revision cho cùng một
trạng thái:

- Event `com.ecommerce.review.metrics-updated.v1`.
- Snapshot của `GET /internal/v1/reviews/rating-aggregates`.

Consumer áp dụng khi và chỉ khi `event.review_revision > document.review_revision`.
Giá trị bằng hoặc nhỏ hơn là no-op.

`observed_at` chỉ dùng cho quan sát và gỡ lỗi, không phải khóa thứ tự.

## Alternatives considered

**Dựa hoàn toàn vào partition ordering.** Đủ cho trường hợp bình thường, nhưng không
xử lý được việc snapshot import chạy song song với replay — chính là tình huống mà
full reindex của Search tạo ra.

**Dùng `updated_at` làm guard.** Không cần cột mới. Nhưng hai mutation trùng timestamp
là chuyện có thật ở tần suất ghi cao, và khi đó guard sẽ bỏ qua bản cập nhật đúng.

**Dùng offset của Kafka làm revision.** Có sẵn và đơn điệu, nhưng chỉ tồn tại cho
event; snapshot export không có offset tương ứng, nên hai đường không so sánh được với
nhau.

**Version theo từng đánh giá thay vì theo sản phẩm.** Chi tiết hơn, nhưng downstream
lưu rating ở mức sản phẩm nên sẽ phải tự gộp — chuyển độ phức tạp sang mọi consumer.

## Consequences

**Tích cực.** Downstream hội tụ đúng bất kể thứ tự nhận, số lần nhận, hay việc snapshot
và event chạy song song. Full reindex của Search trở nên an toàn. Guard là một phép so
sánh số nguyên, rẻ và dễ cài đúng.

**Tiêu cực.** Mọi consumer phải cài guard; consumer quên cài sẽ hoạt động đúng trong
điều kiện bình thường và sai đúng vào lúc reindex — một lớp lỗi khó phát hiện.

**Cạm bẫy vận hành.** Sửa aggregate mà **không** tăng revision sẽ khiến downstream từ
chối bản sửa và giữ số cũ vĩnh viễn. Đây là sai lầm dễ mắc nhất khi xử lý sự cố drift,
và được ghi rõ trong [runbook §10.3](../operations.md#103-aggregate-lệch).

**Constraint mới.** Chỉ được sửa `review_revision` bằng biểu thức `review_revision + 1`.
Sửa tay giá trị này — đặc biệt là đặt xuống thấp — làm downstream mắc kẹt vĩnh viễn.

## Migration impact

Contract đã định nghĩa `review_revision` với `minimum: 1`, và schema có ràng buộc
tương ứng. Search mapping dùng `review_revision`; tên trước đó là `review_version` và
việc đổi tên là thay đổi mapping, chỉ có hiệu lực qua versioned-index reindex — chi
tiết ở [Search ADR-016](../../search/decisions/ADR-016-review-rating-snapshot-reindex.md).

## Verification

Contract test phải chứng minh snapshot export và event phát ra cho **cùng một trạng
thái** mang cùng một `review_revision`. Test consumer phải bao phủ: event trùng, event
cũ hơn, event tới không đúng thứ tự, và snapshot import chạy song song với replay.
Test của Review phải chứng minh không tồn tại đường code nào sửa `review_revision`
ngoài dạng tăng thêm một.
