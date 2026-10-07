"use client";

import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { refreshSession } from "@/lib/api/client";
import { hydrateEmailHint, useAuthStore } from "@/store/useAuthStore";

/**
 * Khôi phục phiên đăng nhập khi ứng dụng khởi động, và dọn dữ liệu của người
 * dùng cũ khi phiên kết thúc.
 *
 * Access token chỉ sống trong bộ nhớ nên sau mỗi lần tải lại trang nó biến mất;
 * cookie `auth_refresh` (HttpOnly) mới là thứ giữ phiên. Một lần refresh ở đây
 * đổi cookie đó lấy access token mới và đưa store ra khỏi trạng thái "loading".
 *
 * Với khách chưa từng đăng nhập, request này trả 401 và store chuyển sang
 * "anonymous" — đó là kết quả bình thường, không phải lỗi.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const queryClient = useQueryClient();

  useEffect(() => {
    hydrateEmailHint();
    void refreshSession();
  }, []);

  // Phiên có thể kết thúc ở nhiều nơi (đăng xuất, đổi mật khẩu, token bị thu
  // hồi, refresh thất bại). Dù ở đâu, dữ liệu cá nhân trong cache không được ở
  // lại cho người đăng nhập tiếp theo trên cùng tab.
  useEffect(
    () =>
      useAuthStore.subscribe((state, previous) => {
        const endedSession =
          previous.status === "authenticated" && state.status !== "authenticated";
        const switchedUser =
          state.status === "authenticated" &&
          previous.claims?.sub !== undefined &&
          previous.claims.sub !== state.claims?.sub;
        if (endedSession || switchedUser) queryClient.clear();
      }),
    [queryClient]
  );

  return <>{children}</>;
}
