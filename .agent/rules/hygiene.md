---
trigger: always_on
---

# Code Generation & Workspace Hygiene Protocol

## 1. Core Principles
* **Zero-Clutter Philosophy**: Các Agent phải ưu tiên giữ cho không gian làm việc sạch sẽ bằng cách hạn chế tối đa việc tạo các tệp không thiết yếu.
* **Artifact-First Communication**: Sử dụng giao diện **Antigravity Artifacts** cho tất cả các thông tin tạm thời, bao gồm: kế hoạch triển khai (Implementation Plans), danh sách tác vụ (Task Lists), và nhật ký thay đổi (Logs). Tuyệt đối không tạo file `.md` cho các mục đích này.
* **Single Source of Truth**: Ưu tiên chỉnh sửa trực tiếp (In-place edits) và sử dụng cơ chế Diff thay vì tạo ra các phiên bản tệp mới (ví dụ: tránh tạo `file_v2.sql`).

## 2. File Management & Lifecycle
* **No Intermediate Markdown**: Không tạo các tệp `.md` trung gian trong quá trình thực hiện tác vụ. 
* **Automatic Cleanup**: Mọi tệp tạm thời phát sinh trong quá trình chạy test hoặc biên dịch (ví dụ: `.tmp`, `.bak`, `.log`) phải được xóa tự động ngay sau khi tác vụ hoàn thành.
* **Selective Creation**: Chỉ tạo các tệp mã nguồn thực sự cần thiết cho hệ thống (ví dụ: `.go`, `.sql`, `.java`, `.proto`).

## 3. Documentation Standards
* **Inline Over External**: Tài liệu hướng dẫn phải được nhúng trực tiếp vào mã nguồn dưới dạng comment chuẩn (ví dụ: GoDoc cho Go, Javadoc cho Java, hoặc OpenAPI/Swagger annotations cho API).
* **README Strategy**: 
    * Chỉ cho phép một tệp `README.md` chính ở thư mục gốc.
    * Mỗi microservice (ví dụ: `/services/order-service`) được phép có một `README.md` ngắn gọn mô tả trách nhiệm của dịch vụ đó.
* **Docs Directory**: Nếu người dùng yêu cầu tài liệu rời, chúng phải được đặt trong thư mục `/docs`, giữ nội dung súc tích (< 5000 ký tự mỗi tệp) và tập trung vào vận hành.

## 4. Database & Migration Specifics
* **Sequential Integrity**: Các tệp migration (ví dụ cho `golang-migrate`) phải tuân thủ nghiêm ngặt quy tắc đặt tên tuần tự (ví dụ: `V001__initial_schema.sql`).
* **Preservation of History**: Agent không được tự ý gộp (squash) các tệp migration cũ trừ khi có yêu cầu tái cấu trúc hệ thống toàn diện.
* **Schema Impact**: Mọi thay đổi về Database Schema phải được giải thích rõ về ảnh hưởng tới hiệu suất (Performance) và tính nhất quán (Consistency) trong phần mô tả của Artifact trước khi thực thi.

## 5. Execution & Enforcement
* **Active Monitoring**: Agent phải chủ động theo dõi không gian làm việc. Nếu phát hiện tệp rác tích tụ, Agent phải đề xuất một "Cleanup Mission" để dọn dẹp.
* **Context Awareness**: Quy tắc này được kích hoạt mặc định cho tất cả các phiên chat trong Editor và Agent Manager.

---
> **Activation Tags**: #code-gen, #refactoring, #database-design, #microservices, #clean-architecture, #migration