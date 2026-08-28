---
doc_id: cart-adr-001
service: cart
status: accepted
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/cart/data.md
  - docs/cart/scripts
---

# ADR-001 — Redis là nguồn chân lý của cart state

## Status

`Accepted`

## Context

Giỏ hàng là màn hình người dùng ra vào liên tục: mỗi lần xem sản phẩm, mỗi lần đổi
số lượng, mỗi lần quay lại từ trang khác. Yêu cầu độ trễ vì vậy khắt khe hơn phần
lớn nghiệp vụ khác, trong khi giá trị của dữ liệu lại thấp hơn: một giỏ hàng bị mất
gây khó chịu, nhưng không phải sự cố tài chính như mất một đơn hàng.

Đọc giỏ cần trả về dữ liệu đủ để render ngay mà không join bảng. Ghi giỏ có đặc thù
read-modify-write (cộng dồn số lượng) nên dễ dính race condition nếu logic nằm ở
tầng ứng dụng.

Nếu đặt PostgreSQL làm nguồn chân lý, mỗi lần bấm tăng số lượng sẽ là một
transaction có khóa dòng, và mỗi lần xem giỏ là một truy vấn join. Ở tải đỉnh của
một đợt khuyến mãi, đây là điểm nghẽn được tạo ra cho phần dữ liệu ít giá trị nhất
trong hệ thống.

## Decision

Redis là nguồn chân lý cho **cart state**: SKU nào, số lượng bao nhiêu, thêm lúc
nào. Mọi thao tác đọc và ghi của API đều diễn ra trên Redis.

PostgreSQL đóng vai trò cold store: một bản chiếu bất đồng bộ, dùng để phục hồi khi
Redis mất key và để phục vụ truy vấn phân tích. Cold store **không** nằm trong
đường request của API, ngoại trừ nhánh phục hồi khi hụt cache.

Quyết định này chỉ áp dụng cho cart state. Nó không đụng tới quyền sở hữu dữ liệu
catalog, vốn thuộc Product Service.

## Alternatives considered

**PostgreSQL là nguồn chân lý, Redis là cache đọc.** Đây là mô hình quen thuộc và
dễ suy luận: dữ liệu bền vững ngay khi commit, không cần cơ chế phục hồi riêng.
Nhược điểm là mọi thao tác ghi phải chịu chi phí transaction và khóa dòng, còn cache
phải được invalidate đúng cách sau mỗi lần ghi — một lớp phức tạp mới với các lỗi
khó tái hiện. Không chọn vì chi phí ghi không tương xứng với giá trị dữ liệu.

**Ghi đồng thời cả hai (dual-write đồng bộ).** Mỗi request ghi cả Redis lẫn
PostgreSQL trước khi trả về. Có vẻ an toàn nhưng thực chất là hai transaction không
nguyên tử: crash giữa hai bước để lại dữ liệu lệch, và độ trễ bằng đúng độ trễ của
thành phần chậm hơn. Không chọn.

**Chỉ dùng Redis, bỏ hẳn cold store.** Đơn giản nhất, nhưng mất key Redis đồng nghĩa
mất giỏ hàng vĩnh viễn, và không có nguồn dữ liệu nào cho phân tích giỏ bị bỏ quên.
Không chọn.

## Consequences

**Tích cực.** Đọc giỏ là một lệnh `HGETALL`. Ghi giỏ là một lần thực thi Lua script.
Không có khóa dòng, không có join, không có lớp invalidate cache.

**Tiêu cực.** Mất Redis là sự cố toàn phần của chức năng giỏ hàng, không có đường
suy giảm. Chúng tôi có cân nhắc đọc cold store làm fallback trong request path và
quyết định không làm: cold store trễ hơn Redis vài giây, và phục vụ trạng thái đã cũ
như thể nó đúng sẽ dẫn tới việc người dùng checkout một giỏ hàng không phản ánh thao
tác gần nhất của họ — tệ hơn là trả lỗi.

**Chi phí vận hành mới.** Redis trở thành hệ thống trạng thái cần persistence, cần
theo dõi bộ nhớ, và cần kế hoạch failover nghiêm túc — khác hẳn một cache có thể xóa
sạch bất cứ lúc nào. Bật AOF là bắt buộc chứ không phải tùy chọn.

**Nợ kỹ thuật được chấp nhận.** Cold store luôn trễ hơn Redis, nên người dùng mất
key Redis đúng lúc consumer đang lag sẽ phục hồi ra một giỏ hàng thiếu vài thao tác
gần nhất. Chúng tôi chấp nhận điều này thay vì làm cold store đồng bộ.

## Migration impact

Không có migration: đây là quyết định nền của service.

Ảnh hưởng tới các quyết định khác: vì Redis giữ trạng thái thật, việc thay đổi trạng
thái và việc phát event phải nguyên tử với nhau, dẫn tới
[ADR-002](./ADR-002-redis-streams-outbox.md). Vì cold store chỉ là bản chiếu, mọi
câu lệnh ghi của consumer phải idempotent và chịu được sai thứ tự, dẫn tới
[ADR-004](./ADR-004-stream-id-ordering.md).

## Verification

- Đường đọc của API không truy vấn PostgreSQL, trừ nhánh rehydrate khi hụt cache.
- Redis được cấu hình persistence, và cấu hình này được kiểm tra ở môi trường
  production.
- Có test chứng minh giỏ hàng được phục hồi đúng từ cold store sau khi cart key bị
  xóa.
- Có bảng đo độ trễ giữa thời điểm ghi Redis và thời điểm dòng tương ứng xuất hiện
  ở cold store.
