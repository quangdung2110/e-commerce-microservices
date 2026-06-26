import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/v1/search/:path*",
        destination: "http://search-service:8080/api/v1/search/:path*",
      },
      {
        source: "/api/v1/carts/:path*",
        destination: "http://cart-service:8080/api/v1/carts/:path*",
      },
    ];
  },
};

export default nextConfig;
