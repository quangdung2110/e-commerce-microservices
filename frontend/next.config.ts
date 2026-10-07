import type { NextConfig } from "next";

/**
 * Upstream của API Gateway (Traefik).
 *
 * - Trong docker-compose: `http://api-gateway:80` (mặc định).
 * - Chạy `next dev` ngoài Docker: đặt `API_UPSTREAM=http://localhost:8088`
 *   trong `.env.local` — 8088 là cổng host mà compose map ra cho Traefik.
 */
const API_UPSTREAM = process.env.API_UPSTREAM ?? "http://api-gateway:80";

const nextConfig: NextConfig = {
  output: "standalone",

  /**
   * Toàn bộ API đi qua rewrite để trình duyệt luôn nói chuyện với chính origin
   * của Next. Điều này giải quyết ba việc cùng lúc:
   *
   *  1. Không cần CORS, không cần `withCredentials` xuyên origin.
   *  2. Cookie `auth_refresh` (HttpOnly, SameSite=Strict, Path=/api/v1/auth/sessions)
   *     được gửi đúng như thiết kế của Auth Service.
   *  3. Địa chỉ gateway không rò rỉ ra bundle phía client.
   *
   * Mảng trả về được áp dụng SAU filesystem route, nên route handler nội bộ của
   * Next (nếu sau này có) vẫn thắng.
   */
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${API_UPSTREAM}/api/:path*`,
      },
      /**
       * CDN giả cho ảnh Product ở môi trường local: gateway map `/media/**` vào
       * bucket MinIO `product-media` (docs/gateway/runtime-contract.md). Production
       * phát media từ domain CDN riêng qua `NEXT_PUBLIC_MEDIA_BASE_URL`.
       */
      {
        source: "/media/:path*",
        destination: `${API_UPSTREAM}/media/:path*`,
      },
    ];
  },
};

export default nextConfig;
