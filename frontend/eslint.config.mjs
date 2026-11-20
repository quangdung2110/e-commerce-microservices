import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

/**
 * Code storefront cũ dựng trên Search v1 (backend đã gỡ) và Cart API lệch
 * contract. Chúng được viết lại ở bước 4–5 của
 * docs/frontend/implementation-plan.md; tới lúc đó lỗi lint của chúng chỉ là
 * cảnh báo để không chặn lint của phần còn lại. XOÁ file khỏi danh sách này khi
 * viết lại — không thêm file mới vào đây.
 */
const LEGACY_STOREFRONT = [
  "src/components/cart/**",
  "src/hooks/use-cart.ts",
  "src/lib/api/cart.ts",
  "src/store/useCartStore.ts",
];

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    // CommonJS bootstrap patches Node's HTTP server before loading standalone server.js.
    files: ["client-ip-server.cjs"],
    rules: { "@typescript-eslint/no-require-imports": "off" },
  },
  {
    files: LEGACY_STOREFRONT,
    rules: {
      "@typescript-eslint/no-explicit-any": "warn",
      "react-hooks/set-state-in-effect": "warn",
      "@next/next/no-html-link-for-pages": "warn",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Sinh tự động từ contracts/ bằng `npm run gen:api`.
    "src/lib/api/generated/**",
  ]),
]);

export default eslintConfig;
