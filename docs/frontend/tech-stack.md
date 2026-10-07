# Frontend Architecture & Tech Stack

## Tổng quan
Tài liệu này mô tả các công nghệ frontend được lựa chọn cho hệ thống E-Commerce Microservices Platform.
Dựa trên kiến trúc backend đồ sộ và phức tạp (Microservices, Event Sourcing, CQRS, Go/Java, gRPC), frontend cần đáp ứng các tiêu chí: hiệu năng cao, quản lý state phức tạp, hỗ trợ real-time (WebSockets) tốt và tối ưu hóa SEO tuyệt đối.

## Tech Stack Lựa Chọn: Next.js (React) Ecosystem

Stack này được chọn làm tiêu chuẩn chính để xây dựng các ứng dụng frontend (Storefront, Admin Dashboard, v.v.).

### 1. Core Framework: Next.js (App Router) & React
- **Lý do:** Next.js hiện đang là tiêu chuẩn công nghiệp cho các ứng dụng React, cung cấp hiệu năng tuyệt vời, tối ưu hóa tích hợp sẵn và khả năng SEO vượt trội — những yếu tố sống còn đối với một nền tảng thương mại điện tử.
- **Ngôn ngữ:** **TypeScript** (Bắt buộc) để đảm bảo type safety trên toàn bộ ứng dụng.
- **Chiến lược Rendering:**
  - **SSR / SSG / ISR:** Bước 4 render storefront phía server. Search, facet, PDP và Review dùng `no-store` theo contract; cây danh mục cache 60 giây. Chỉ dùng SSG/ISR cho nội dung hoặc upstream cho phép cache; không cache PDP degrade hay signed Review media. Xem [ADR-002](./decisions/ADR-002-rendering-and-auth-boundary.md).
  - **CSR (Client-Side Rendering):** Dành cho các trang cần đăng nhập như Giỏ hàng (Cart), Thanh toán (Checkout), Hồ sơ người dùng (User Profile) và toàn bộ trang quản trị. Access token chỉ nằm trong bộ nhớ trình duyệt và cookie refresh chỉ gửi tới `/api/v1/auth/sessions`, nên server của Next không xác thực được người dùng để SSR các trang này — xem [ADR-002](./decisions/ADR-002-rendering-and-auth-boundary.md).

### 2. Quản lý State (State Management)
- **Server State (Data Fetching & Caching): React Query (TanStack Query)**
  - Giải pháp hoàn hảo để gọi API, caching, đồng bộ và cập nhật dữ liệu bất đồng bộ từ Traefik API Gateway.
- **Client State (Local UI State): Zustand**
  - Thư viện quản lý state nhẹ, nhanh và dễ mở rộng. Dùng để quản lý các state nội bộ của UI. (Đối với các logic giỏ hàng/thanh toán mang tính global cực kỳ phức tạp, có thể cân nhắc Redux Toolkit).

### 3. Styling & UI Components
- **CSS Framework: Tailwind CSS**
  - Utility-first CSS framework giúp phát triển giao diện siêu tốc và duy trì một design system đồng nhất, không bị phình to CSS.
- **UI Library: Shadcn UI** (hoặc Radix UI / Headless UI kết hợp Tailwind)
  - Cung cấp các component có tính accessibility cao, dễ dàng tùy biến mã nguồn và tích hợp mượt mà với Tailwind CSS.

### 4. Giao tiếp thời gian thực (Real-time Interactions)
- **WebSockets:** Sử dụng Native WebSockets hoặc `socket.io-client` để xử lý các tính năng real-time theo thiết kế của backend:
  - Cart v1 hiện dùng Pull Model: đọc lại khi mở trang, focus, tải mới hoặc sau
    mutation; backend chưa có contract WebSocket cho Cart. Bước 5 không tạo kênh
    realtime hoặc giả lập event giỏ hàng.
  - Nhắn tin trực tuyến (Chat Service).

### 5. Công cụ & Tiện ích (Utilities & Tooling)
- **Giao tiếp Backend (Networking):** `axios` hoặc một wrapper tùy chỉnh dựa trên `fetch` để giao tiếp với Traefik API Gateway.
- **Xử lý Form & Validation:** `react-hook-form` kết hợp với `zod` để validate schema mạnh mẽ (schema này có thể đồng bộ với thiết kế của backend).
- **Testing:** 
  - `Vitest` / `Jest` cho Unit Testing.
  - `Playwright` hoặc `Cypress` để test E2E cho các luồng sống còn (Ví dụ: Checkout, Payment flow).
- **Một ứng dụng cho storefront và admin:** Trang quản trị nằm dưới `/admin` trong cùng ứng dụng Next.js, dùng chung Terra, lớp API và phiên đăng nhập; không dùng monorepo nhiều app ở giai đoạn này — xem [ADR-001](./decisions/ADR-001-admin-in-storefront-app.md).

## Tổng kết
Hệ sinh thái Next.js cung cấp một nền tảng vững chắc, linh hoạt và có khả năng mở rộng cao, bổ trợ hoàn hảo cho kiến trúc Event-Driven Microservices của backend. Sự kết hợp này đảm bảo mang lại trải nghiệm người dùng nhanh, mượt mà, thân thiện với bộ máy tìm kiếm (SEO) đồng thời duy trì năng suất cao cho đội ngũ phát triển.
