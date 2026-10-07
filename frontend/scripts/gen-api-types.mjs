/**
 * Sinh type TypeScript từ các OpenAPI contract mà trình duyệt gọi tới.
 *
 * Chạy: `scripts/node.sh npm run gen:api` (cần mount cả repository để đọc
 * `contracts/`). Kết quả nằm ở `src/lib/api/generated/<name>.ts` và được commit;
 * không sửa tay các file đó.
 *
 * Contract nội bộ (mTLS/workload identity) không được sinh vì trình duyệt không
 * gọi được chúng.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import openapiTS, { astToString } from "openapi-typescript";

const FRONTEND_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const CONTRACTS_HTTP = join(FRONTEND_ROOT, "..", "contracts", "http");
const OUT_DIR = join(FRONTEND_ROOT, "src", "lib", "api", "generated");

/**
 * `optional`: contract đang có lỗi đã biết; sinh thất bại thì giữ nguyên file cũ
 * và chỉ cảnh báo, không làm hỏng cả lệnh.
 */
const CONTRACTS = [
  { name: "auth", spec: "auth/v1/openapi.yaml" },
  { name: "user", spec: "user/v1/openapi.yaml" },
  { name: "cart", spec: "cart/v1/openapi.yaml" },
  { name: "checkout", spec: "checkout/v1/openapi.yaml" },
  { name: "order", spec: "order/v1/openapi.yaml" },
  { name: "promotion", spec: "promotion/v1/openapi.yaml" },
  { name: "review", spec: "review/v1/openapi.yaml" },
  { name: "search", spec: "search/v2/openapi.yaml" },
  { name: "notification", spec: "notification/v1/openapi.yaml" },
  { name: "product", spec: "product/v1/openapi.yaml" },
  { name: "product-detail-bff", spec: "product-detail-bff/v1/openapi.yaml" },
  { name: "inventory-admin", spec: "inventory/admin/v1/openapi.yaml" },
];

function header(spec) {
  return [
    "/**",
    " * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.",
    ` * Nguồn: contracts/http/${spec}`,
    " * Sinh lại bằng `scripts/node.sh npm run gen:api`.",
    " */",
    "",
  ].join("\n");
}

await mkdir(OUT_DIR, { recursive: true });

const failures = [];
for (const { name, spec, optional } of CONTRACTS) {
  const source = pathToFileURL(join(CONTRACTS_HTTP, spec));
  const target = join(OUT_DIR, `${name}.ts`);
  try {
    const ast = await openapiTS(source, {
      // Trường không có trong `required` thật sự có thể vắng mặt: giữ `?`.
      // Enum xuất thành union string để so sánh trực tiếp.
      alphabetize: false,
    });
    await writeFile(target, header(spec) + astToString(ast));
    console.log(`✓ ${name.padEnd(20)} → ${relative(FRONTEND_ROOT, target)}`);
  } catch (error) {
    const reason = error instanceof Error ? error.message.split("\n")[0] : String(error);
    if (optional) {
      console.warn(`! ${name.padEnd(20)} bỏ qua (giữ file cũ nếu có): ${reason}`);
    } else {
      console.error(`✗ ${name.padEnd(20)} ${reason}`);
      failures.push(name);
    }
  }
}

if (failures.length > 0) {
  console.error(`\nKhông sinh được: ${failures.join(", ")}`);
  process.exit(1);
}
