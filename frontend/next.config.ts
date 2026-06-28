import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/v1/search/:path*",
        destination: "http://api-gateway:80/api/v1/search/:path*",
      },
      {
        source: "/api/v1/carts/:path*",
        destination: "http://api-gateway:80/api/v1/carts/:path*",
      },
    ];
  },
};

export default nextConfig;
