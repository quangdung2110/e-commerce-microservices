import type { AccessTokenClaims } from "@/lib/api/jwt";

/**
 * Role trong claim `roles` của access token.
 *
 * Mọi API quản trị trong `contracts/` (Product, Promotion, Order, Review, Search,
 * Auth, Inventory) dùng chung role `ADMIN`. Review cho phép cấu hình tên role
 * nhưng mặc định cũng là `ADMIN`
 * (`java/services/review-service/src/main/resources/application.yml`).
 */
export const ROLES = {
  ADMIN: "ADMIN",
} as const;

export type Role = (typeof ROLES)[keyof typeof ROLES];

/**
 * Claim có role hay không. Chỉ dùng để quyết định hiển thị; chữ ký token không
 * được kiểm ở client nên đây không phải kiểm tra quyền.
 */
export function hasRole(claims: AccessTokenClaims | null, role: Role): boolean {
  return claims?.roles?.includes(role) ?? false;
}
