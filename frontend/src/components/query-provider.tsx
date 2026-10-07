"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

import { isRetryable } from "@/lib/api/problem";

/** Số lần thử lại tối đa cho một query đọc dữ liệu. */
const MAX_QUERY_RETRIES = 2;

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 60 * 1000, // 1 minute
            refetchOnWindowFocus: false,
            // Chỉ thử lại lỗi tạm thời (mất mạng, 429, 5xx). Thử lại 4xx vô ích,
            // và với 401 mỗi lần thử còn kéo theo một lần xoay refresh token.
            retry: (failureCount, error) =>
              failureCount < MAX_QUERY_RETRIES && isRetryable(error),
          },
          mutations: {
            // Lệnh có side effect không bao giờ tự thử lại; `useCommand` quyết
            // định có giữ idempotency key cho lần gửi tiếp theo hay không.
            retry: false,
          },
        },
      })
  );

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
