---
doc_id: review-adr-005
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-005 — Tổng hợp rating bất đồng bộ qua self-consume

## Status

`Accepted`

## Context

Trang sản phẩm cần điểm trung bình, tổng số đánh giá và phân bố theo sao. Tính các số
này bằng `GROUP BY` trên bảng `reviews` cho mỗi lượt xem trang là không khả thi với
sản phẩm có hàng chục nghìn đánh giá, nên chúng phải được duy trì sẵn.

Câu hỏi là duy trì ở đâu. Cập nhật aggregate ngay trong transaction ghi đánh giá nghe
có vẻ đơn giản nhất, nhưng nó biến dòng aggregate của một sản phẩm thành điểm tranh
chấp: mọi đánh giá của cùng một sản phẩm bán chạy sẽ nối đuôi chờ cùng một dòng, và
độ trễ của việc gửi đánh giá phụ thuộc vào việc có bao nhiêu người khác đang gửi cho
cùng sản phẩm đó.

Thiết kế trước đã chọn hướng bất đồng bộ, nhưng có ba vấn đề: aggregator không xử lý
`REVIEW_UPDATED`, nên sửa điểm không làm đổi aggregate; câu lệnh cập nhật nhận
`average_rating` do consumer tính sẵn rồi ghi đè, nên hai event xử lý gần nhau ghi đè
kết quả của nhau; và `total_with_media` chỉ tăng, không bao giờ giảm.

## Decision

`product_rating_aggregates` được cập nhật bất đồng bộ bởi một consumer đọc lại chính
`t.review.events` của Review, consumer group `review-rating-aggregator`.

Consumer xử lý `REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_APPROVED`, `REVIEW_HIDDEN`
và `REVIEW_REMOVED`; nó **bỏ qua** `REVIEW_METRICS_UPDATED` — xử lý event này sẽ khiến
nó tự kích hoạt chính mình thành vòng lặp vô hạn.

Ba quy tắc bắt buộc:

1. **Cập nhật ở mức bucket, không ở mức tổng.** Câu lệnh chỉ cộng hoặc trừ trên
   `total_n_star`; `total_reviews` và `average_rating` được derive từ năm bucket ngay
   trong cùng câu lệnh. Không có giá trị tổng hợp nào được tính bên ngoài database rồi
   ghi vào.
2. **`REVIEW_UPDATED` mang cả giá trị cũ và mới** (`old_rating`, `new_rating`,
   `old_has_media`, `new_has_media`), để consumer trừ ở bucket cũ và cộng ở bucket mới
   mà không cần đọc lại trạng thái trước đó.
3. **Ràng buộc database bắt lỗi tính toán.** `ck_aggregate_total_matches_buckets` và
   `ck_aggregate_buckets_non_negative` biến một phép tính sai thành lỗi transaction
   thay vì một con số sai âm thầm.

Consumer xử lý theo micro-batch 100–500 event; nhiều event của cùng một sản phẩm
trong một batch được gộp thành một dòng outbox, vì event mang snapshot đầy đủ chứ
không phải delta.

Consumer này **không idempotent**. Xử lý lại một event sẽ đếm thừa. Đây là hạn chế
được chấp nhận có ý thức, bù bằng đối chiếu hằng ngày và metric
`review_aggregate_drift_total`.

## Alternatives considered

**Cập nhật aggregate trong cùng transaction với đánh giá.** Aggregate luôn chính xác
và tác giả thấy điểm mới ngay. Nhược điểm: mọi đánh giá của cùng một sản phẩm tranh
chấp một dòng, nên sản phẩm càng bán chạy thì việc gửi đánh giá càng chậm — đúng chiều
ngược với mong muốn. Cũng làm transaction ghi dài hơn và giữ lock lâu hơn.

**Consumer idempotent với bảng `processed_events`.** Loại bỏ hoàn toàn khả năng đếm
thừa. Cái giá: một lần ghi và một lần đọc thêm cho mỗi event, cộng với việc dọn bảng
đó theo retention. Với sai số dự kiến rất nhỏ và đã có đối chiếu hằng ngày, chi phí
này chưa xứng đáng — nhưng đây là cách xử lý đã định sẵn nếu độ chính xác tức thời
trở thành yêu cầu.

**Tính lại toàn bộ aggregate của một sản phẩm sau mỗi event.** Idempotent theo bản
chất và luôn đúng. Nhược điểm: mỗi event thành một lần quét toàn bộ đánh giá của sản
phẩm; với sản phẩm có 50.000 đánh giá, điều này không chấp nhận được.

**Materialized view của PostgreSQL với refresh định kỳ.** Đơn giản, nhưng độ trễ theo
chu kỳ refresh và không phát được event cho downstream tại thời điểm thay đổi.

## Consequences

**Tích cực.** Đường ghi đánh giá nhanh và không phụ thuộc vào lượng đánh giá đã có của
sản phẩm. Không có tranh chấp lock giữa những người cùng đánh giá một sản phẩm. Việc
phát `REVIEW_METRICS_UPDATED` nằm cùng chỗ với việc tính toán, nên hai thứ không thể
lệch nhau.

**Tiêu cực.** Aggregate stale 1–5 giây: người dùng vừa gửi đánh giá sẽ thấy đánh giá
của mình trong danh sách nhưng chưa thấy điểm trung bình đổi. Giao diện cần được thiết
kế với hiểu biết đó.

**Aggregate có thể lệch.** Crash giữa commit và ACK làm batch được xử lý lại và đếm
thừa. Mục tiêu vận hành là dưới 0,1% sản phẩm lệch trong mỗi lần đối chiếu; vượt ngưỡng
thì cảnh báo.

**Consumer này là worker duy nhất không an toàn khi replay.** Muốn dựng lại aggregate
thì phải đếm lại từ bảng `reviews`, không được replay event. Điều này phải xuất hiện
trong mọi runbook liên quan.

## Migration impact

Không có dữ liệu để migrate. Ảnh hưởng chính là lên contract: `REVIEW_UPDATED` bắt
buộc mang `old_rating` và `old_has_media`. Consumer nhận event thiếu các trường này
phải từ chối xử lý và đưa vào dead letter thay vì đoán giá trị.

## Verification

Test phải chứng minh chuỗi tạo → sửa điểm → ẩn → hiện lại → gỡ cho ra aggregate khớp
chính xác với `GROUP BY` trên bảng `reviews`. Test phải chứng minh aggregator bỏ qua
`REVIEW_METRICS_UPDATED` và không tạo vòng lặp. Test phải chứng minh bucket không bao
giờ âm và `average_rating` khớp công thức trên mọi phân bố. Test đối chiếu phải phát
hiện được lệch do xử lý lại event, và bản sửa phải tăng `review_revision`.
