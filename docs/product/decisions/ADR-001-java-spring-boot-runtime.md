---
doc_id: product-adr-001
service: product
status: accepted
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
evidence: []
---

# ADR-001 — Java và Spring Boot cho Product Service

## Status

`Accepted`

## Context

Product Service là write model của catalog: nhiều transaction chạm 3–4 bảng cùng lúc,
một state machine có guard, logic tính giá markdown theo lô, và ba luồng consumer
Kafka. Hệ thống có cả service viết bằng Go (Auth, Cart, User) nên runtime của Product
là một lựa chọn có thật chứ không mặc định.

## Decision

Product Service dùng Java 21 với Spring Boot 3.x, Spring Data JPA, Spring Kafka,
Spring Statemachine và gRPC.

Quyết định này bao gồm việc dùng `@Transactional` làm ranh giới transaction và Spring
Statemachine làm nơi khai báo FSM. Nó **không** bao gồm lựa chọn cơ chế phát event
(xem [ADR-002](./ADR-002-outbox-debezium-cdc.md)) hay mô hình concurrency của luồng
inventory (xem [ADR-003](./ADR-003-two-phase-lock-free-inventory-sync.md)).

## Alternatives considered

- **Go với gRPC**, giống Auth và Cart: nhị phân nhẹ, khởi động nhanh, chi phí bộ nhớ
  thấp. Nhưng phải tự cài đặt FSM bằng switch-case hoặc map transition, và phải quản
  lý transaction nhiều bảng thủ công. Với một service mà phần lớn rủi ro nằm ở
  transaction và guard, chi phí đó rơi đúng vào chỗ dễ sai nhất.
- **Java không dùng Spring Statemachine**, tự viết FSM: ít phụ thuộc hơn nhưng mất
  phần khai báo guard/transition tập trung, đúng điểm mạnh cần cho vòng đời sản phẩm.

## Consequences

Tích cực: transaction nhiều bảng và FSM được biểu diễn ngắn gọn; hệ sinh thái JPA/Kafka
sẵn có; Debezium và Kafka Connect là môi trường quen thuộc với stack JVM.

Tiêu cực: footprint bộ nhớ và thời gian khởi động lớn hơn Go, ảnh hưởng tới tốc độ scale
consumer; JPA che giấu SQL nên dễ vô tình phá lock order nếu không kiểm soát; đội phải
duy trì song song hai hệ sinh thái runtime.

Ràng buộc mới: lock order và các câu lệnh lock phải được viết tường minh, không phó mặc
cho JPA sinh câu lệnh.

## Migration impact

Không có migration: đây là ghi nhận quyết định đã áp dụng từ đầu cho service. Ảnh hưởng
chủ yếu ở khâu vận hành (tuning JVM, cấu hình pool) và ở việc chia sẻ kiến thức giữa các
đội đang dùng Go.

## Verification

Chứng minh bằng: `pom.xml` và cấu hình Spring của service, cấu hình Spring Statemachine
cho FSM sản phẩm, và test tích hợp cho transaction nhiều bảng. Chưa có evidence được
xác minh trong phạm vi refactor tài liệu này.
