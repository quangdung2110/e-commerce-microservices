---
doc_id: cart-adr-003
service: cart
status: accepted
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/cart/data.md
  - docs/cart/workflows.md
---

# ADR-003 — Global sharded stream thay vì stream theo từng giỏ

## Status

`Accepted`

## Context

[ADR-002](./ADR-002-redis-streams-outbox.md) chốt việc dùng Redis Streams làm outbox
nhưng chưa trả lời: bao nhiêu stream?

Thiết kế đầu tiên cho mỗi người dùng một stream riêng. Nghe hợp lý — mỗi giỏ một
hàng đợi — nhưng kéo theo cả một bộ máy để relay biết stream nào đang có việc: một
tập hợp ghi nhận ý định publish, một tập hợp các stream đang hoạt động, một kênh
thông báo, và bước dọn dẹp sau khi xử lý. Với một triệu người dùng, đó là một triệu
stream cộng một triệu mục theo dõi, và bốn tầng trạng thái phải luôn nhất quán với
nhau.

Chi phí thật không nằm ở bộ nhớ mà ở số cách hệ thống có thể hỏng: mỗi tầng theo dõi
là một chỗ có thể lệch khỏi thực tế, để lại stream có event mà không ai biết để đọc.

## Decision

Dùng một số nhỏ cố định các stream dùng chung — hiện là 4 shard — thay cho stream
theo từng giỏ. Shard đích được chọn bằng một hàm băm ổn định trên `user_id` chia dư
cho số shard, nên mọi event của một người dùng luôn vào cùng một shard và giữ nguyên
thứ tự tương đối.

Mỗi shard có consumer group riêng. Relay đọc song song trên các shard bằng
`XREADGROUP`; Redis Streams tự quản lý pending và redelivery.

Toàn bộ các tầng theo dõi phụ trợ bị loại bỏ: không còn tập hợp ý định, không còn
danh sách stream hoạt động, không còn kênh thông báo, không còn bước dọn dẹp sau xử
lý. Luồng rút gọn còn hai bước: script ghi và `XADD`; relay đọc, produce, `XACK`.

Tên shard vẫn mang hash tag `{cart}` để nằm cùng slot với cart key, điều kiện bắt
buộc để một script chạm cả hai.

## Alternatives considered

**Một stream cho mỗi giỏ.** Cô lập tuyệt đối giữa các người dùng và thứ tự tự nhiên
theo giỏ. Nhưng cần cơ chế riêng để phát hiện stream nào có việc, và số lượng stream
tăng tuyến tính theo số người dùng. Không chọn vì chi phí vận hành và số điểm hỏng.

**Một stream duy nhất, không shard.** Đơn giản nhất. Nhưng chỉ một consumer group
nghĩa là relay không song song hóa được, và toàn hệ thống có đúng một điểm nghẽn.
Không chọn.

**Shard theo `cart_id` thay vì `user_id`.** Cho kết quả tương đương vì `cart_id` được
dựng từ `user_id`. Chọn `user_id` vì nó là dữ liệu gốc, không phụ thuộc vào định dạng
key hiện tại.

**Số shard động, tự tăng theo tải.** Hấp dẫn về lý thuyết, nhưng đổi số shard làm đổi
ánh xạ người dùng sang shard, và event của một người có thể nằm rải trên hai shard
với thứ tự không còn bảo đảm. Không chọn cho giai đoạn hiện tại.

## Consequences

**Tích cực.** Số lượng stream là hằng số, không phụ thuộc quy mô người dùng. Relay
đơn giản hơn hẳn: không còn tầng trạng thái nào phải giữ đồng bộ. Throughput tăng
tuyến tính theo số shard trong giới hạn đã chọn.

**Tiêu cực — mất cô lập.** Một shard bị kẹt làm chậm **toàn bộ** người dùng thuộc
shard đó, không chỉ một người. Với stream theo từng giỏ, sự cố chỉ ảnh hưởng đúng
một người. Đây là cái giá trực tiếp của việc gom chung, và nó biến một sự cố cục bộ
thành sự cố ảnh hưởng khoảng một phần tư người dùng.

**Giới hạn scale cứng.** Số instance relay hữu ích không vượt quá số shard. Thêm pod
ngoài mức đó không tăng throughput, chỉ tăng chi phí — một điểm dễ hiểu nhầm khi
xử lý sự cố dưới áp lực.

**Người dùng nóng.** Nếu một người dùng tạo lượng event bất thường, shard chứa họ
chịu tải lệch mà không có cách nào tách riêng.

**Constraint mới.** Số shard trở thành một phần của contract dữ liệu chứ không phải
tham số chỉnh tự do.

## Migration impact

**Code.** Backend phải tính shard trước khi gọi bất kỳ script nào và truyền đúng key
shard vào.

**Data.** Đổi số shard là thay đổi phá vỡ tính liên tục: event của một người dùng
chuyển sang shard mới trong khi shard cũ có thể còn event chưa xử lý, dẫn tới khả
năng đảo thứ tự. Quy trình an toàn cho việc này — nhiều khả năng cần bước drain toàn
bộ shard cũ trước khi chuyển — **chưa được thiết kế**, và đây là khoảng trống đã biết
của service.

**Deployment.** Số shard phải giống nhau ở mọi instance; hai phiên bản dùng số shard
khác nhau sẽ ghi vào các shard khác nhau cho cùng một người dùng.

## Verification

- Có test chứng minh cùng một `user_id` luôn cho ra cùng shard.
- Có test chứng minh relay xử lý song song nhiều shard mà vẫn giữ thứ tự trong từng shard.
- Có metric độ dài và độ trễ tách riêng theo từng shard, đủ để phát hiện lệch tải.
- Quy trình đổi số shard được thiết kế và diễn tập trước khi có nhu cầu thật.
