# Chat Service - Thiết kế Kiến trúc

**Liên kết nhanh (Related Documents):**
* [Cấu trúc Database](chat_db_schema.md)
* [Cấu trúc Sự kiện (Events)](chat_events.md)
* [Quy trình nghiệp vụ (Workflows)](chat_workflow.md)

---
Tài liệu này chốt thiết kế Chat Service theo hướng Chat là source of truth cho transcript và schema ScyllaDB tối ưu cho truy vấn realtime. Giai đoạn này tập trung vào thử nghiệm chat giữa người dùng với nhau.

## 1. Mục tiêu và phạm vi

Chat Service phục vụ các luồng chính:
1. Người dùng <-> Người dùng (Chat P2P).
2. Quản lý trạng thái hoạt động (Presence - Online/Offline).
3. Đẩy tin nhắn realtime qua WebSocket Gateway.

## 2. Ranh giới dịch vụ (Service boundary)

### 2.1. Quyền sở hữu của Chat Service

Chat Service sở hữu:
1. Vòng đời hội thoại (Conversation lifecycle) và đồ thị người tham gia (participant graph).
2. Lưu trữ tin nhắn, biên nhận đã giao/đã đọc (delivery/read receipts), trạng thái thu hồi/chỉnh sửa.
3. Xuất bản bản ghi (transcript export) và dọn dẹp theo TTL.
4. Quản lý trạng thái hiện diện (Presence) qua Redis.

## 3. Quy ước domain

### 3.1. Loại hội thoại (Conversation type)

1. `USER_TO_USER` (Người dùng <-> Người dùng)

### 3.2. Vai trò người tham gia (Participant role)

1. `USER` (Khách hàng/Người mua)

### 3.3. Trạng thái hội thoại (Conversation state)

1. `OPEN`
2. `CLOSED`

## 4. Kiến trúc dữ liệu và quyết định đã chốt

### 4.1. Chiến lược Inbox (Ưu tiên độ trễ khi đọc)

Quyết định:
1. Ưu tiên độ trễ khi đọc (read-latency) cho inbox.
2. Chấp nhận đường dẫn ghi (write-path) phức tạp hơn để giữ UX ổn định.

Hệ quả thiết kế:
1. Dùng cặp bảng `room_state_by_participant` (canonical) + `inbox_index_by_participant_time` (chỉ mục đã sắp xếp).
2. Đường dẫn ghi bắt buộc: đọc trạng thái cũ -> cập nhật trạng thái mới -> chèn chỉ mục mới -> xóa chỉ mục cũ.
3. Có worker đối soát (reconcile) để sửa lỗi lệch chỉ mục (index drift) nếu thất bại giữa chừng.


### 4.2. Chiến lược biên nhận đã đọc (Read receipt - UX-grade)

Quyết định:
1. Giai đoạn hiện tại coi biên nhận đã đọc là cấp độ UX (UX-grade).
2. Vẫn bắt buộc có bảo vệ phía máy chủ (server-side guard) để tránh gian lận cơ bản.

Bảo vệ bắt buộc:
1. Kiểm tra thành viên: người dùng phải thuộc phòng chat.
2. Kiểm tra phiên/xác thực hợp lệ.
3. Thứ tự tăng dần (Monotonic sequence - `new_seq >= old_seq`).
4. Giới hạn `new_seq <= room.last_message_seq`.

### 4.3. Chiến lược lưu trữ (Retention strategy)

Quyết định:
1. Dọn dẹp dữ liệu theo TTL (Time-To-Live) mặc định.
2. `USER_TO_USER`: transcript 6 tháng.

## 5. Đánh giá ScyllaDB và nguyên tắc mô hình hóa

1. Ưu tiên truy vấn (Query-first), mỗi mô hình đọc có bảng riêng.
2. Tránh phân vùng lớn (large partition) bằng cách gom nhóm thời gian (time bucket).
3. Không dùng counter làm source of truth cho số tin nhắn chưa đọc (unread).
4. Dùng outbox + tính lặp lại của người tiêu dùng (consumer idempotency) để đảm bảo độ tin cậy của sự kiện trong mô hình at-least-once.

## 6. Chiến lược Metadata

`metadata_json` cho phép tin nhắn mang ngữ cảnh bổ sung:
1. Payload thẻ sản phẩm/đơn hàng để UI hiển thị thẻ.
2. Thuộc tính đa phương tiện (thời lượng, kích thước, dung lượng file, loại mime).

Khuyến nghị:
1. Các trường cần lọc/truy vấn thường xuyên phải tách ra cột riêng hoặc bảng chiếu (projection table).

## 7. Mô hình nhất quán và tin cậy

1. Mô hình phân phối: At-Least-Once.
2. Tính lặp lại đầu vào (Ingress idempotency): `client_request_id` + `sender_id`.
3. Tính lặp lại sự kiện: `event_id` cho mỗi người tiêu dùng.
4. Bảo vệ chống sai lệch thứ tự: `message_seq` và `event_sequence_id`.
5. Worker chuyển tiếp outbox (Outbox relay worker) là bắt buộc để tránh mất sự kiện khi gặp sự cố.

## 9. Kiến trúc Presence & Real-time (Bổ sung)

### 9.1. Presence Service (Redis-based)
1. **Cấu trúc lưu trữ:** Dùng Redis để tối ưu tốc độ đọc/ghi tần suất cao.
   - Key: `presence:{user_id}`
   - Value: `last_seen_timestamp`
   - TTL: 120 giây (2 phút).
2. **Logic Online:** Nếu key tồn tại -> `online`.
3. **Logic Offline:** Nếu key hết hạn (expired) -> `offline`. Để hiển thị "last seen X phút trước", hệ thống có thể lưu một key phụ `presence:{user_id}:last_seen` với TTL dài hơn (ví dụ 30 ngày).

### 9.2. WebSocket Gateway (Delivery Layer)
1. **Vai trò:** Giữ kết nối socket và tiêu thụ sự kiện từ Kafka để đẩy xuống client.
2. **Phân tách:** Gateway không chứa logic nghiệp vụ chat, chỉ thực hiện định tuyến tin nhắn theo `user_id`.

## 10. Tiêu chí nghiệm thu

1. Inbox không bị lặp phòng chat khi phòng có tin mới liên tục.
2. Số tin chưa đọc không bị lệch sau khi thử lại hoặc lặp lại sự kiện.
3. Phân trang phòng chat lớn ổn định qua nhiều nhóm thời gian.
4. Việc xuất bản sự kiện không làm mất sự kiện và không phát lặp không kiểm soát.
