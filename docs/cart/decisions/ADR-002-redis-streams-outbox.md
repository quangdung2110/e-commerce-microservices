---
doc_id: cart-adr-002
service: cart
status: superseded
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/cart/scripts/add_or_update_item.lua
  - docs/cart/workflows.md
---

# ADR-002 — Outbox bằng Redis Streams thay vì publish Kafka trực tiếp

## Status

`Superseded` bởi [ADR-010](./ADR-010-redis-only-cart-and-abandoned-outbox.md).

> Lịch sử quyết định bên dưới giữ nguyên. Redis Streams vẫn là cơ chế outbox,
> nhưng phạm vi hiện tại chỉ là `CART_ABANDONED`, không phải mọi business write.

## Context

Ở phiên bản đầu, luồng thêm hàng làm ba việc: ghi Redis hash, dựng payload event,
rồi gọi Kafka publisher bất đồng bộ.

Lỗi xảy ra khi pod chết hoặc Kafka timeout sau bước một nhưng trước khi publish
thành công. Hậu quả: Redis đã có sản phẩm trong giỏ, nhưng Kafka không nhận được
event, nên cold store vĩnh viễn không biết đến thao tác này. Khi Redis mất key, phục
hồi từ cold store cho ra một giỏ hàng thiếu hàng — và không có cách nào phát hiện
được điều đó, vì không còn dấu vết nào của event đã mất.

Đây là bài toán dual-write kinh điển. Vì [ADR-001](./ADR-001-redis-first-source-of-truth.md)
đặt trạng thái thật trên Redis chứ không phải trên một database quan hệ, giải pháp
outbox thông thường — ghi event vào một bảng trong cùng transaction với dữ liệu — không
áp dụng trực tiếp được.

## Decision

Event được `XADD` vào một Redis Stream **bên trong chính Lua script** đã thay đổi
cart state. Redis thực thi script như một đơn vị không chia cắt, nên thay đổi trạng
thái và việc sinh event hoặc cùng xảy ra, hoặc cùng không.

Một worker relay riêng đọc stream bằng consumer group, forward sang Kafka, và chỉ
`XACK` sau khi Kafka đã xác nhận. Cấm `XACK` trước khi có xác nhận.

Quyết định này áp dụng cho mọi business write: thêm, cập nhật, xóa, và cả cronjob
đánh dấu giỏ bỏ quên — cronjob cũng phải đi qua đúng đường `XADD` → relay → Kafka
thay vì publish thẳng, để hệ thống chỉ có một mô hình phát event duy nhất.

Ngoại lệ duy nhất là luồng rehydrate: nó là thao tác phục hồi dữ liệu, không phải
business write, nên tuyệt đối không được `XADD`.

## Alternatives considered

**Publish Kafka trực tiếp sau khi ghi Redis, có retry.** Đơn giản, ít thành phần.
Nhưng retry chỉ giúp khi process còn sống; pod bị giết giữa chừng vẫn mất event, và
không có cách nào biết đã mất. Không chọn.

**Bảng outbox trên PostgreSQL.** Mô hình chuẩn và quen thuộc, có thể dùng CDC để
relay. Nhưng nó buộc mỗi thao tác ghi giỏ phải chạm PostgreSQL đồng bộ, đúng thứ mà
[ADR-001](./ADR-001-redis-first-source-of-truth.md) đã tránh. Chọn nó là phá bỏ lý do
tồn tại của kiến trúc Redis-first. Không chọn.

**Redis `MULTI/EXEC`.** Cho phép gom nhiều lệnh, nhưng không cho phép đọc rồi quyết
định dựa trên giá trị đọc được trong cùng khối. Logic cộng dồn số lượng và kiểm tra
trần cần đúng khả năng đó. Không chọn.

**Redis Pub/Sub thay vì Streams.** Nhẹ hơn, nhưng Pub/Sub không lưu trữ: không có
subscriber online tại thời điểm phát thì message biến mất. Đó chính là vấn đề cần
giải. Không chọn.

## Consequences

**Tích cực.** Không còn cửa sổ nào mà cart state đã đổi nhưng event bị mất. Người
dùng nhận phản hồi ngay sau khi Redis ghi xong, hoàn toàn cách ly khỏi độ trễ hay sự
cố của Kafka. Event nằm chờ an toàn trong stream suốt thời gian Kafka gián đoạn.

**Tiêu cực — và đây là hệ quả cần nhớ nhất.** Khi Kafka gián đoạn kéo dài, stream
phình to trong bộ nhớ Redis. Nghĩa là sự cố của Kafka chuyển hóa thành áp lực bộ nhớ
lên chính hệ thống đang giữ nguồn chân lý. Đây là rủi ro vận hành nghiêm trọng nhất
của kiến trúc này và là lý do các cảnh báo về độ dài stream phải được xử lý sớm.

**Phức tạp thêm.** Cần một worker relay, cần quản lý consumer group, và cần một
tác vụ trim riêng — vì `XACK` chỉ chống redelivery chứ không giải phóng bộ nhớ.
Thiếu bước trim là rò rỉ bộ nhớ chậm mà không có triệu chứng rõ ràng cho tới khi
muộn.

**Constraint mới.** Mọi key mà một script chạm tới phải cùng hash tag, nếu không
Redis Cluster từ chối thực thi. Điều này ràng buộc cách đặt tên key trên toàn service.

**Delivery semantic.** Đường đi tổng thể là at-least-once. Consumer bắt buộc phải
dedupe; xem [ADR-009](./ADR-009-bucket-scoped-event-ordering.md).

## Migration impact

**Code.** Mọi đường ghi phải đi qua Lua script; không còn chỗ nào ghi cart state
bằng lệnh Redis rời.

**Data.** Không có migration dữ liệu, nhưng không gian key phải tuân thủ quy ước hash
tag ngay từ đầu.

**Contract.** Message Kafka mang thêm các trường thứ tự lấy từ stream; consumer
downstream cần biết đến chúng.

**Deployment.** Thêm một worker vào topology. Nếu relay không chạy, người dùng không
thấy gì bất thường trong khi cold store lặng lẽ tụt lại — nên tình trạng sống của
relay phải được giám sát chủ động, không chờ người dùng báo.

## Verification

- Có test chứng minh script sinh đúng một event cho mỗi lần ghi thành công.
- Có test chứng minh script rehydrate không sinh event nào.
- Có test chứng minh relay không `XACK` khi produce thất bại.
- Có test crash relay giữa `XREADGROUP` và `XACK`, và chứng minh message được nhặt
  lại đầy đủ.
- Có cảnh báo hoạt động trên độ dài stream và tuổi message pending.
