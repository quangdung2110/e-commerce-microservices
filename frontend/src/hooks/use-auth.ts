"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";

import { authApi } from "@/lib/api/auth";
import { hasRole, type Role } from "@/lib/auth/roles";
import { useAuthStore } from "@/store/useAuthStore";

/**
 * Trạng thái đăng nhập của phiên hiện tại.
 *
 * `status` có ba giá trị và cả ba đều phải được xử lý ở UI: "loading" (đang
 * khôi phục phiên, chưa biết gì), "authenticated", "anonymous". Coi "loading"
 * như "anonymous" sẽ làm màn hình protected chớp một nhịp redirect sai.
 */
export function useAuth() {
  const router = useRouter();

  const status = useAuthStore((state) => state.status);
  const claims = useAuthStore((state) => state.claims);
  const emailHint = useAuthStore((state) => state.emailHint);

  const logout = useCallback(
    async (redirectTo = "/login") => {
      // Cache của người vừa đăng xuất do AuthProvider dọn khi phiên kết thúc.
      await authApi.logout();
      router.replace(redirectTo);
    },
    [router]
  );

  return {
    status,
    isLoading: status === "loading",
    isAuthenticated: status === "authenticated",
    userId: claims?.sub ?? null,
    sessionId: claims?.sid ?? null,
    roles: claims?.roles ?? [],
    /** Chỉ dùng để ẩn/hiện UI; quyền thật do gateway và service kiểm tra. */
    hasRole: (role: Role) => hasRole(claims, role),
    email: emailHint,
    logout,
  };
}
