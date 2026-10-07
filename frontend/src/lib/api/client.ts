import axios, {
  AxiosError,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from "axios";
import { v4 as uuidv4 } from "uuid";

import type { components } from "@/lib/api/generated/auth";
import { problemCode } from "@/lib/api/problem";
import { useAuthStore } from "@/store/useAuthStore";

/**
 * Client của trình duyệt cho mọi API qua gateway.
 *
 * Request đi qua rewrite của Next (`next.config.ts`) nên URL là đường dẫn tương
 * đối, không có CORS, và cookie `auth_refresh` (HttpOnly, SameSite=Strict,
 * Path=/api/v1/auth/sessions) được gửi đúng như thiết kế.
 *
 * `baseURL` để trống vì các service không chung prefix (`/api/v1`, `/api/v2`);
 * mỗi module trong `lib/api/` tự khai báo đường dẫn đầy đủ.
 *
 * Chỉ dùng trong Client Component. Server Component dùng `lib/api/server.ts`
 * (ADR-002).
 */

/** Response của `POST /api/v1/auth/sessions` và `POST …/sessions/refresh`. */
export type AccessTokenResponse = components["schemas"]["AccessTokenResponse"];

function withRequestId(config: InternalAxiosRequestConfig) {
  // Mọi service đều log và echo lại X-Request-Id; gửi sẵn giúp lần vết xuyên hệ thống.
  config.headers.set("X-Request-Id", uuidv4());
  return config;
}

/**
 * Client "trần" dành riêng cho refresh: nó không có interceptor 401, nếu không
 * một lần refresh thất bại sẽ tự gọi lại chính nó.
 */
const bareClient: AxiosInstance = axios.create({
  headers: { "Content-Type": "application/json" },
  withCredentials: true,
});
bareClient.interceptors.request.use(withRequestId);

/**
 * Client dùng cho mọi endpoint, công khai lẫn cần danh tính. Gắn access token
 * nếu có; route công khai của gateway bỏ qua nó.
 */
export const api: AxiosInstance = axios.create({
  headers: { "Content-Type": "application/json" },
  withCredentials: true,
});

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.set("Authorization", `Bearer ${token}`);
  return withRequestId(config);
});

/**
 * Refresh đang chạy dở, để nhiều request 401 cùng lúc chỉ tạo đúng một lần
 * rotate. Auth Service chỉ cho mỗi refresh token dùng một lần, nên gọi song
 * song sẽ khiến các lần sau thất bại và đá người dùng ra ngoài oan.
 */
let inflightRefresh: Promise<string | null> | null = null;

/**
 * Đổi cookie refresh lấy access token mới.
 *
 * Trả `null` khi phiên đã thực sự kết thúc — lúc đó store chuyển sang
 * `anonymous` và caller chịu trách nhiệm điều hướng.
 */
export function refreshSession(): Promise<string | null> {
  inflightRefresh ??= bareClient
    .post<AccessTokenResponse>("/api/v1/auth/sessions/refresh")
    .then((response) => {
      useAuthStore.getState().setSession(response.data.access_token);
      return response.data.access_token;
    })
    .catch(() => {
      useAuthStore.getState().markAnonymous();
      return null;
    })
    .finally(() => {
      inflightRefresh = null;
    });

  return inflightRefresh;
}

/** Các mã 401 đáng thử refresh. Mã khác nghĩa là thử lại cũng vô ích. */
const REFRESHABLE = new Set(["UNAUTHORIZED", "TOKEN_INVALID"]);

interface RetriableConfig extends InternalAxiosRequestConfig {
  /** Đánh dấu để một request chỉ được thử lại đúng một lần. */
  _retried?: boolean;
}

api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!(error instanceof AxiosError) || error.response?.status !== 401) {
      throw error;
    }

    const config = error.config as RetriableConfig | undefined;
    const code = problemCode(error);

    // INVALID_CREDENTIALS (đăng nhập sai) và TOKEN_REVOKED (phiên bị thu hồi)
    // đều là 401 nhưng refresh không giải quyết được gì.
    if (!config || config._retried || !code || !REFRESHABLE.has(code)) {
      if (code === "TOKEN_REVOKED") useAuthStore.getState().markAnonymous("revoked");
      throw error;
    }

    const token = await refreshSession();
    if (!token) throw error;

    config._retried = true;
    config.headers.set("Authorization", `Bearer ${token}`);
    return api.request(config);
  }
);
