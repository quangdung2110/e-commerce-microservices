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
  - **SSG / ISR (Static Site Generation / Incremental Static Regeneration):** Được sử dụng cho các trang danh mục sản phẩm (Product Catalog), chi tiết sản phẩm (PDP) và nội dung bài viết. Đảm bảo thời gian tải trang gần như tức thì và điểm SEO tối đa.
  - **SSR (Server-Side Rendering):** Dành cho các trang cần dữ liệu động liên tục như Giỏ hàng (Cart), Thanh toán (Checkout), và Hồ sơ người dùng (User Profile).

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
  - Cập nhật giỏ hàng theo thời gian thực (Cart Service).
  - Nhắn tin trực tuyến (Chat Service).

### 5. Công cụ & Tiện ích (Utilities & Tooling)
- **Giao tiếp Backend (Networking):** `axios` hoặc một wrapper tùy chỉnh dựa trên `fetch` để giao tiếp với Traefik API Gateway.
- **Xử lý Form & Validation:** `react-hook-form` kết hợp với `zod` để validate schema mạnh mẽ (schema này có thể đồng bộ với thiết kế của backend).
- **Testing:** 
  - `Vitest` / `Jest` cho Unit Testing.
  - `Playwright` hoặc `Cypress` để test E2E cho các luồng sống còn (Ví dụ: Checkout, Payment flow).
- **Monorepo (Khuyên dùng):** Cân nhắc sử dụng **Turborepo** hoặc **Nx** nếu dự án có nhiều frontend apps (ví dụ: Storefront web, Admin CMS web) để dễ dàng chia sẻ chung UI components và helper functions.

## Tổng kết
Hệ sinh thái Next.js cung cấp một nền tảng vững chắc, linh hoạt và có khả năng mở rộng cao, bổ trợ hoàn hảo cho kiến trúc Event-Driven Microservices của backend. Sự kết hợp này đảm bảo mang lại trải nghiệm người dùng nhanh, mượt mà, thân thiện với bộ máy tìm kiếm (SEO) đồng thời duy trì năng suất cao cho đội ngũ phát triển.
