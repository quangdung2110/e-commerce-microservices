# Notification Service Architecture Design

## 1. Overview
Notification Service là một microservice trung tâm chịu trách nhiệm quản lý và phân phối thông báo đến người dùng thông qua nhiều kênh khác nhau (hiện tại hỗ trợ **Email** và **In-app**). Service này hoạt động hoàn toàn theo mô hình **Event-Driven**, tiêu thụ các sự kiện (events) từ các dịch vụ khác thông qua Kafka, xử lý nghiệp vụ, render nội dung dựa trên template có sẵn và gửi thông báo, đồng thời đảm bảo tính toàn vẹn, không gửi trùng lặp.

## 2. Core Architecture Patterns

### 2.1 Event-Driven & Outbox Pattern
- Nhận dữ liệu dưới dạng **Fat Events** thông qua chuẩn **CloudEvents**. Các service khác (Order, Auth, Payment,...) tạo event chưa đầy đủ thông tin (người dùng, tóm tắt đơn hàng, nội dung chi tiết) đẩy qua bảng Outbox của mình. Debezium CDC sẽ capture các thay đổi này và đẩy lên Kafka.
- **Fat Events** giúp Notification Service không cần gọi ngược (sync REST/gRPC) để lấy thông tin, giảm coupling (phụ thuộc) và tăng hiệu năng xử lý.

### 2.2 Kafka Topics & Priority Routing
Để đảm bảo thông báo quan trọng (OTP) không bị nghẽn bởi các thông báo marketing, hệ thống sử dụng nhiều Topic riêng biệt:
- `notification.high-priority.events`: Chứa OTP, cảnh báo bảo mật, đăng nhập thiết bị mới (SLA < 5s).
- `notification.low-priority.events`: Chứa cập nhật đơn hàng, thanh toán, khuyến mãi, tin báo từ system (SLA < 30s hoặc vài phút).

### 2.3 Single Deployment Architecture (API + Worker)
Trong giai đoạn đầu, API Server và Kafka/Background Worker được gộp chung trong **1 Deployment** để đơn giản hóa CI/CD và quản lý tài nguyên.
- **REST/gRPC API:** Cung cấp APIs để quản lý `templates`, lấy danh sách `notifications` (cho ứng dụng lấy lịch sử), và cấu hình `user_preferences`.
- **WebSocket Server:** Quản lý các kết nối thời gian thực (real-time) tới client cho kênh In-app.
- **Kafka Consumers / Workers:** Lắng nghe event, render template, gọi API bên thứ 3 (Email Provider), và Publish message nội bộ.

### 2.4 WebSocket Cluster & Redis Pub/Sub
Để giải quyết bài toán scale WebSocket khi có nhiều instances (pods) trong cùng 1 Deployment:
- **Redis Pub/Sub** đóng vai trò làm xương sống giao tiếp giữa các node.
- Luồng gửi In-app:
  1. Client kết nối tới một Node (Pod A) qua WebSocket. Thông tin connection mapping được lưu tại bộ nhớ của Pod A.
  2. Khi Worker (ở Pod B) xử lý event In-app, nó sẽ:
     - Ghi lịch sử vào PostgreSQL (để user có thể đọc nếu offline).
     - **Publish** 1 message vào Redis Pub/Sub channel: `notification:inapp:{user_id}`.
  3. Tất cả các Node trong cluster đều subscribe Redis Pub/Sub. Pod A nhận được message, kiểm tra thấy user này đang giữ WebSocket connection tại mình, liền Push data xuống cho Client. Các Pod khác bỏ qua message.

## 3. Reliability & Resilience (Độ Tin Cậy)

### 3.1 Idempotency & Deduplication (Chặn gửi trùng lặp)
Vì Kafka đảm bảo **At-least-once**, system có thể nhận trùng event.
- Sử dụng `event_id` của CloudEvents làm idempotency key.
- Redis cache (TTL 24h) được dùng để lọc nhanh các `event_id` đã xử lý. Nếu vượt qua Redis, database sẽ sử dụng constraint `UNIQUE(event_id)` trên bảng log/tracking để đảm bảo **Exactly-once delivery**.

### 3.2 Error Handling & Retry Mechanism
- Phân loại lỗi từ External Providers (VD: SendGrid, AWS SES):
  - **Network Errors (Timeout, 5xx), Rate-Limit (429):** Thực hiện **Retry với Exponential Backoff** (Ví dụ: thử lại sau 5s, 15s, 45s...).
  - **Data Errors (400 Bad Request, Bounced Email):** Đẩy thông điệp vào **Dead Letter Queue (DLQ)** để admin phân tích, không retry lãng phí tài nguyên.
- **Circuit Breaker:** Tích hợp (như Resilience4J) với các Email Providers. Nếu Provider A gặp sự cố (tỉ lệ lỗi > 50%), mạch sẽ mở, service tạm ngưng/chuyển sang Provider dự phòng (Ví dụ: từ SendGrid qua SES).

### 3.3 Rate Limiting
Bảo vệ user khỏi spam và bảo vệ budget của hệ thống bằng thuật toán **Token Bucket** quản lý trong Redis:
- Giới hạn OTP: Tối đa 3 Email trong 5 phút.
- Giới hạn Marketing: 1 Email / ngày / user.

## 4. Workflow

1. Dịch vụ (Order/Auth) tạo record vào `outbox_events` -> Debezium capture đổi -> Kafka.
2. Notification Worker (trong Pod) tiêu thụ event.
3. Kích hoạt Idempotency Check bằng `event_id` qua Redis.
4. Lấy dữ liệu `user_preferences` để quyết định gửi qua kênh nào.
5. Nếu là In-app: 
   - Lưu DB -> Push `Redis Pub/Sub` -> Node giữ connection qua nhận -> WebSocket -> Client.
6. Nếu là Email:
   - Render template (bằng engine trong code) với payload JSON.
   - Gửi qua Provider (áp dụng Circuit Breaker / Retry).
7. Lưu log vào `delivery_logs` (thành công/thất bại) cho mục đích Audit.
