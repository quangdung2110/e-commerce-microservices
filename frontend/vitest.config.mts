import { defineConfig } from "vitest/config";

/**
 * Unit test cho logic thuần và hook (docs/frontend/implementation-plan.md §0.11).
 * Mặc định chạy trong Node; file test hook khai báo `@vitest-environment jsdom`.
 * Chạy: `scripts/node.sh npm test`.
 */
export default defineConfig({
  // Đọc alias `@/*` từ tsconfig.json.
  resolve: { tsconfigPaths: true },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    // Ngày giờ trong test so với giờ Việt Nam; cố định TZ của tiến trình để test
    // không phụ thuộc máy chạy.
    env: { TZ: "UTC" },
  },
});
